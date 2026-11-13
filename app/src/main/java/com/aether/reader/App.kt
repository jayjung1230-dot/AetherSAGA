package com.aether.reader

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aether.reader.data.*
import com.aether.reader.platform.HardwareProfileManager
import com.aether.reader.platform.display.DisplayProfileManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class App : Application() {
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS completed_books (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookPath TEXT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        completedAt INTEGER NOT NULL,
                        completedDate TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_completed_books_completedDate ON completed_books (completedDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_completed_books_title ON completed_books (title)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE highlights ADD COLUMN bookTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE highlights ADD COLUMN bookAuthor TEXT NOT NULL DEFAULT ''")
            }
        }
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var prefs: Prefs
    lateinit var db: AppDatabase
    lateinit var profiles: DisplayProfileManagerImpl
    lateinit var sync: ExportSync
    lateinit var covers: CoverCache
    lateinit var hardware: HardwareProfileManager

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        L10n.init(this, prefs)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "aether.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    runCatching {
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_books_lastRead ON books (lastRead DESC)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_books_title ON books (title COLLATE NOCASE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_highlights_bookPath_chapter ON highlights (bookPath, chapter, startChar)")
                    }
                }
            })
            .fallbackToDestructiveMigration()
            .build()
        profiles = DisplayProfileManagerImpl(this, darkModeProvider = { prefs.dark })
        covers = CoverCache(this)
        sync = ExportSync(this, prefs, db)
        sync.registerNetworkTrigger()
        hardware = HardwareProfileManager(this, profiles)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = java.io.File(filesDir, "crash.log")
                f.writeText("${System.currentTimeMillis()}: ${e.stackTraceToString()}")
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(t, e)
        }

        // 1. Apply hardware-optimized default options on first run
        if (!prefs.hwDefaultsApplied) {
            prefs.applyHardwareDefaults(hardware.spec)
        }

        // 2. Migrate to user-curated bookshelf: remove any bulk auto-scanned storage files
        if (!prefs.manualShelfMigrated) {
            prefs.manualShelfMigrated = true
            appScope.launch {
                try {
                    db.books().deleteUnopenedAutoScannedBooks()
                } catch (_: Throwable) {}
            }
        }

        // 3. Purge legacy third-party font files from app private font directory
        appScope.launch {
            try {
                val destDir = java.io.File(filesDir, "fonts")
                if (destDir.exists() && destDir.isDirectory) {
                    val legacyNames = setOf(
                        "kopubworlddotum_pro_medium.otf", "kopubworldbatang_pro_medium.otf",
                        "nanumgothic_regular.ttf", "nanummyeongjo_regular.ttf",
                        "maruburi_regular.ttf", "chosunilbo_myungjo.ttf",
                        "batang.otf", "kodia.ttf",
                        "notosans_regular.ttf", "notoserif_regular.ttf",
                        "roboto_regular.ttf", "merriweather_regular.ttf",
                        "arimo_regular.ttf", "inter_regular.ttf", "literata_regular.ttf",
                        "cafe24_gounbam_m.ttf", "gounbam.ttf"
                    )
                    destDir.listFiles()?.forEach { f ->
                        if (f.name.lowercase() in legacyNames) {
                            f.delete()
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    @Volatile private var pendingStatsJob: Job? = null

    /**
     * Records reading page & time deltas in memory immediately,
     * and batches/debounces SQLite database disk writes by 4 seconds
     * to eliminate storage write contention during fast page flipping.
     */
    fun recordReadingSession(pagesDelta: Int, msDelta: Long) {
        prefs.recordReading(pagesDelta, msDelta)
        val today = prefs.statsDate.ifBlank {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        }
        val p = prefs.todayPages
        val m = prefs.todayMinutes

        synchronized(this) {
            pendingStatsJob?.cancel()
            pendingStatsJob = appScope.launch {
                delay(4000L)
                runCatching {
                    db.stats().upsert(DailyReadingStatsEntity(today, m, p))
                }
            }
        }
    }

    /**
     * Instantly flushes any buffered in-memory reading stats to SQLite on lifecycle pause/destroy.
     */
    fun flushReadingStatsImmediate() {
        val today = prefs.statsDate.ifBlank {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        }
        val p = prefs.todayPages
        val m = prefs.todayMinutes
        synchronized(this) {
            pendingStatsJob?.cancel()
            pendingStatsJob = null
        }
        appScope.launch {
            runCatching {
                db.stats().upsert(DailyReadingStatsEntity(today, m, p))
            }
        }
    }

    fun recordBookCompleted(path: String, title: String, author: String = "", timestamp: Long = System.currentTimeMillis()) {
        val cleanTitle = title.ifBlank { java.io.File(path).nameWithoutExtension }
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(timestamp))
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = db.completedBooks().findByTitle(cleanTitle)
                if (existing == null) {
                    db.completedBooks().insert(
                        CompletedBookEntity(
                            bookPath = path,
                            title = cleanTitle,
                            author = author,
                            completedAt = timestamp,
                            completedDate = dateStr
                        )
                    )
                }
            }
        }
    }

    suspend fun syncExistingCompletedBooks() = withContext(Dispatchers.IO) {
        runCatching {
            val all = db.books().all()
            for (b in all) {
                if (b.progress >= 0.98f) {
                    val cleanTitle = b.title.ifBlank { java.io.File(b.path).nameWithoutExtension }
                    if (db.completedBooks().findByTitle(cleanTitle) == null) {
                        val ts = if (b.lastRead > 0) b.lastRead else if (b.addedAt > 0) b.addedAt else System.currentTimeMillis()
                        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ts))
                        db.completedBooks().insert(
                            CompletedBookEntity(
                                bookPath = b.path,
                                title = cleanTitle,
                                author = b.author,
                                completedAt = ts,
                                completedDate = dateStr
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun syncHighlightBookMetadata() = withContext(Dispatchers.IO) {
        runCatching {
            val allH = db.highlights().all()
            val needMeta = allH.filter { it.bookTitle.isBlank() }
            if (needMeta.isNotEmpty()) {
                val pathMap = db.books().all().associateBy { it.path }
                for (h in needMeta) {
                    val b = pathMap[h.bookPath] ?: pathMap[h.bookPath.removePrefix("file://")]
                    if (b != null) {
                        db.highlights().updateBookMeta(h.bookPath, b.title, b.author)
                    }
                }
            }
        }
    }

    /**
     * Centralized, failsafe check to determine if the active display mode is LCD/OLED.
     * Always returns false for any E-Ink display mode (forced or detected hardware).
     */
    fun isLcdMode(strategy: com.aether.reader.domain.display.DisplayStrategy? = null): Boolean {
        // 1. Check user override in prefs
        val forced = prefs.forcedKind
        if (forced == com.aether.reader.domain.display.DisplayKind.MONO_EINK.name ||
            forced == com.aether.reader.domain.display.DisplayKind.COLOR_EINK.name) {
            return false
        }
        if (forced == com.aether.reader.domain.display.DisplayKind.LCD_OLED.name) {
            return true
        }

        // 2. Check preset
        val preset = prefs.displayDevicePreset
        val einkPresets = setOf(
            "EINK_60_300", "EINK_78_300", "STANDARD_6", "EINK_60_HD", "EINK_68_300",
            "EINK_70_300", "LARGE_103", "MAX_133",
            "MONO_EINK", "COLOR_EINK"
        )
        if (preset in einkPresets) return false

        // 3. Check strategy if provided
        if (strategy != null) {
            return strategy.kind == com.aether.reader.domain.display.DisplayKind.LCD_OLED
        }

        // 4. Fallback to hardware spec
        return hardware.spec.displayKind == com.aether.reader.domain.display.DisplayKind.LCD_OLED
    }
}
