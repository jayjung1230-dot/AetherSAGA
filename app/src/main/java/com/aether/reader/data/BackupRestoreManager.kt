package com.aether.reader.data

import android.content.Context
import android.os.Environment
import com.aether.reader.data.AppDatabase
import com.aether.reader.data.BookEntity
import com.aether.reader.data.HighlightEntity
import com.aether.reader.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configuration & User Data Backup / Restore Manager
 * Exports typography prefs, hardware key bindings, reading positions, and highlights to a timestamped JSON file.
 */
class BackupRestoreManager(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: Prefs
) {
    suspend fun createBackup(): File = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("appName", "AetherSAGA")
        root.put("timestamp", System.currentTimeMillis())

        // 1. Settings & Typography
        val pObj = JSONObject().apply {
            put("fontSizePt", prefs.fontSizePt)
            put("lineHeight", prefs.lineHeight.toDouble())
            put("paraSpacingDp", prefs.paraSpacingDp)
            put("marginDp", prefs.marginDp)
            put("boldness", prefs.boldness)
            put("refreshEvery", prefs.refreshEvery)
            put("dropCaps", prefs.dropCaps)
            put("readingRuler", prefs.readingRuler)
            put("followSystemBrightness", prefs.followSystemBrightness)
            put("displayDevicePreset", prefs.displayDevicePreset)
            put("dark", prefs.dark)
        }
        root.put("preferences", pObj)

        // 2. Reading Progress & Books
        val booksArr = JSONArray()
        for (b in db.books().allPaths()) {
            val entity = db.books().get(b) ?: continue
            val bObj = JSONObject().apply {
                put("path", entity.path)
                put("title", entity.title)
                put("author", entity.author)
                put("chapter", entity.chapter)
                put("charOffset", entity.charOffset)
                put("progress", entity.progress.toDouble())
                put("lastRead", entity.lastRead)
            }
            booksArr.put(bObj)
        }
        root.put("books", booksArr)

        // 3. Highlights & Notes
        val hlArr = JSONArray()
        for (p in db.books().allPaths()) {
            val list = db.highlights().forBook(p)
            for (h in list) {
                val hObj = JSONObject().apply {
                    put("bookPath", h.bookPath)
                    put("kind", h.kind)
                    put("chapter", h.chapter)
                    put("startChar", h.startChar)
                    put("endChar", h.endChar)
                    put("quote", h.quote)
                    put("note", h.note)
                    put("style", h.style)
                    put("colorTag", h.colorTag)
                    put("chapterTitle", h.chapterTitle)
                    put("createdAt", h.createdAt)
                }
                hlArr.put(hObj)
            }
        }
        root.put("highlights", hlArr)

        // 4. Completed Books History
        val compArr = JSONArray()
        for (c in db.completedBooks().all()) {
            val cObj = JSONObject().apply {
                put("bookPath", c.bookPath)
                put("title", c.title)
                put("author", c.author)
                put("completedAt", c.completedAt)
                put("completedDate", c.completedDate)
            }
            compArr.put(cObj)
        }
        root.put("completedBooks", compArr)

        // Save to Documents/AetherSAGA
        val docsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AetherSAGA")
        docsDir.mkdirs()
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val backupFile = File(docsDir, "AetherSAGA_Backup_$dateStr.json")
        backupFile.writeText(root.toString(2))
        backupFile
    }

    suspend fun restoreBackup(jsonFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonFile.readText())
            if (!root.has("version")) return@withContext false

            // Restore prefs
            if (root.has("preferences")) {
                val p = root.getJSONObject("preferences")
                prefs.fontSizePt = p.optInt("fontSizePt", prefs.fontSizePt)
                prefs.lineHeight = p.optDouble("lineHeight", prefs.lineHeight.toDouble()).toFloat()
                prefs.paraSpacingDp = p.optInt("paraSpacingDp", prefs.paraSpacingDp)
                prefs.marginDp = p.optInt("marginDp", prefs.marginDp)
                prefs.boldness = p.optInt("boldness", prefs.boldness)
                prefs.refreshEvery = p.optInt("refreshEvery", prefs.refreshEvery)
                prefs.dropCaps = p.optBoolean("dropCaps", prefs.dropCaps)
                prefs.readingRuler = p.optBoolean("readingRuler", prefs.readingRuler)
                prefs.followSystemBrightness = p.optBoolean("followSystemBrightness", prefs.followSystemBrightness)
                prefs.displayDevicePreset = p.optString("displayDevicePreset", prefs.displayDevicePreset)
                prefs.dark = p.optBoolean("dark", prefs.dark)
            }

            // Restore progress
            if (root.has("books")) {
                val arr = root.getJSONArray("books")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val path = o.getString("path")
                    val ch = o.getInt("chapter")
                    val off = o.getLong("charOffset")
                    val prog = o.getDouble("progress").toFloat()
                    val t = o.optLong("lastRead", System.currentTimeMillis())
                    db.books().updateProgress(path, t, ch, off, prog)
                }
            }

            // Restore highlights
            if (root.has("highlights")) {
                val arr = root.getJSONArray("highlights")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val h = HighlightEntity(
                        bookPath = o.getString("bookPath"),
                        kind = o.getInt("kind"),
                        chapter = o.getInt("chapter"),
                        startChar = o.getLong("startChar"),
                        endChar = o.getLong("endChar"),
                        quote = o.getString("quote"),
                        note = o.optString("note", ""),
                        style = o.getInt("style"),
                        colorTag = o.optInt("colorTag", 0),
                        progress = 0f,
                        chapterTitle = o.optString("chapterTitle", ""),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                    db.highlights().insert(h)
                }
            }

            // Restore completed books
            if (root.has("completedBooks")) {
                val arr = root.getJSONArray("completedBooks")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val title = o.getString("title")
                    if (db.completedBooks().findByTitle(title) == null) {
                        db.completedBooks().insert(
                            CompletedBookEntity(
                                bookPath = o.optString("bookPath", ""),
                                title = title,
                                author = o.optString("author", ""),
                                completedAt = o.optLong("completedAt", System.currentTimeMillis()),
                                completedDate = o.optString("completedDate", "")
                            )
                        )
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
