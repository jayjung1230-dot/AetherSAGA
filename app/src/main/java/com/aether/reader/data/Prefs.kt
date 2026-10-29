package com.aether.reader.data

import android.content.Context
import com.aether.reader.domain.display.DisplayKind
import com.aether.reader.domain.display.DisplaySettings
import com.aether.reader.domain.display.PageTransition
import com.aether.reader.platform.HardwareSpec
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class ReaderAction { NONE, NEXT, PREV, FULL_REFRESH, BOOKMARK, MENU, TOGGLE_DARK }

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("aether", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { sp.edit().putInt(key, value).apply() }
    }
    private fun long(key: String, def: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getLong(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) { sp.edit().putLong(key, value).apply() }
    }
    private fun float(key: String, def: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getFloat(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) { sp.edit().putFloat(key, value).apply() }
    }
    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { sp.edit().putString(key, value).apply() }
    }
    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    }

    // Language: "AUTO" (initial, follows system), "ko" (Korean), "en" (English)
    var language by str("appLanguage", "AUTO")

    fun getEffectiveLanguage(context: Context): String {
        val saved = language
        if (saved == "ko" || saved == "en") return saved
        val sysLocale = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            android.content.res.Resources.getSystem().configuration.locale
        }
        val isKorean = sysLocale.language.lowercase().startsWith("ko")
        val initialLang = if (isKorean) "ko" else "en"
        language = initialLang
        return initialLang
    }

    // Typography & Viewer Baseline Defaults (8pt, 2.0x line height, 2dp paragraph gap, 20dp margin, boldness 1)
    var useDocumentDefaults by bool("useDocumentDefaults", false)
    var fontSizePt by int("fontSizePt", 8)
    var lineHeight by float("lineHeight", 2.0f)
    var paraSpacingDp by int("paraSpacingDp", 2)
    var letterSpacingEm by float("letterSpacingEm", 0f)
    var marginDp by int("marginDp", 20)
    var fontPath by str("fontPath", "")
    var colorTag by int("colorTag", 0)
    var txtEncoding by str("txtEncoding", "AUTO") // AUTO, UTF-8, x-windows-949, EUC-KR, Shift_JIS

    // Comic & Manga Settings
    var comicDirection by str("comicDirectionV2", "LTR") // LTR (default, same as EPUB/PDF), RTL (Manga), VERTICAL (Webtoon)
    var comicSplitMode by str("comicSplitMode", "AUTO_SPLIT") // AUTO_SPLIT, FIT_WIDTH, FIT_HEIGHT
    var comicContrastBoost by bool("comicContrastBoost", true) // Gamma +2.0 and pencil line enhancement for E-Ink

    // Display
    var boldness by int("boldness", 1)
    var refreshEvery by int("refreshEvery", 6)
    var transition by str("transition", PageTransition.INSTANT.name)
    var forcedKind by str("forcedKind", "")
    var displayDevicePreset by str("displayDevicePreset", "AUTO") // AUTO, MONO_EINK, COLOR_EINK, LCD_OLED
    var readingTheme by str("readingTheme", "WHITE") // WHITE, SEPIA, DARK, OLED_BLACK
    var dark by bool("dark", false)
    var ghostClearEnabled by bool("ghostClearEnabled", true) // LCD/OLED defaults to false (see applyHardwareDefaults)
    var orientationLocked by bool("orientationLocked", false)
    var lockedOrientation by int("lockedOrientation", -1) // ActivityInfo.SCREEN_ORIENTATION_* captured when locking
    var floatingPickerNavEnabled by bool("floatingPickerNavEnabled", true) // User-configurable floating back button on file picker

    // Advanced Feature Toggles & Hardware Overrides
    var dropCaps by bool("dropCaps", false)
    var readingRuler by bool("readingRuler", false)
    var pomodoroTimer by bool("pomodoroTimer", false)
    var autoTurnEnabled by bool("autoTurnEnabled", false)
    var autoTurnSeconds by int("autoTurnSeconds", 20)
    var turboPaging by bool("turboPaging", false)
    var experimentalOverride by bool("experimentalOverride", false)

    // Daily Reading Stats
    var statsDate by str("statsDate", "")
    var todayMinutes by int("todayMinutes", 0)
    var todayPages by int("todayPages", 0)
    var todayMillis by long("todayMillis", 0L)

    fun recordReading(pagesDelta: Int, msDelta: Long) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (statsDate != today) {
            statsDate = today
            todayMinutes = 0
            todayPages = 0
            todayMillis = 0L
        }
        todayPages += pagesDelta
        todayMillis += msDelta.coerceAtLeast(0L)
        todayMinutes = (todayMillis / 60_000L).toInt()
    }

    // Hardware
    var followSystemBrightness by bool("followSystemBrightness", true)
    var brightness by float("brightness", 0.5f)
    var warmth by float("warmth", 0f)
    var ctmBroadcastAction by str("ctmAction", "")
    var ctmBroadcastExtra by str("ctmExtra", "value")
    var idleDimMinutes by int("idleDimMin", 3)

    // Library & Migration Flags
    var libraryMode by int("libMode", 0)
    var librarySort by int("libSort", 0)   // 0 recent, 1 title, 2 author
    var scanned by bool("scanned", false)
    var hwDefaultsApplied by bool("hwDefaultsApplied_v4", false)
    var manualShelfMigrated by bool("manualShelfMigrated_v1", false)

    // Sync & Share
    var shareEmail by str("shareEmail", "")
    var smtpUser by str("smtpUser", "")
    var smtpPass by str("smtpPass", "")
    var smtpHost by str("smtpHost", "")
    var smtpPort by int("smtpPort", 465)
    var webdavUrl by str("webdavUrl", "")
    var webdavUser by str("webdavUser", "")
    var webdavPass by str("webdavPass", "")

    /**
     * Applies baseline viewer defaults (Font 8pt, LineHeight 2.0x, ParaSpacing 2dp, Margin 20dp, Boldness 1)
     * alongside hardware-aware performance settings.
     */
    fun applyHardwareDefaults(spec: HardwareSpec) {
        useDocumentDefaults = false
        fontSizePt = 8
        lineHeight = 2.0f
        paraSpacingDp = 2
        marginDp = 20
        boldness = 1
        letterSpacingEm = 0f
        dropCaps = false
        readingRuler = false
        pomodoroTimer = false

        when {
            spec.isLowSpecEink -> {
                refreshEvery = 6
                transition = PageTransition.INSTANT.name
                dark = false
                idleDimMinutes = 3
                turboPaging = false
            }
            spec.isHighSpecEink -> {
                refreshEvery = if (spec.displayKind == DisplayKind.COLOR_EINK) 5 else 8
                transition = PageTransition.INSTANT.name
                dark = false
                idleDimMinutes = 5
                turboPaging = true
            }
            else -> { // LCD / OLED Tablets
                ghostClearEnabled = false // LCD/OLED has no ghosting: disabled by default
                refreshEvery = 30
                transition = PageTransition.SLIDE.name
                dark = false
                idleDimMinutes = 10
                turboPaging = true
            }
        }
        hwDefaultsApplied = true
    }

    /** Resets typography to the standard baseline (8pt, 2.0x, 2dp, 20dp, boldness 1). */
    fun resetToDefaultStyle() {
        useDocumentDefaults = false
        fontSizePt = 8
        lineHeight = 2.0f
        paraSpacingDp = 2
        marginDp = 20
        boldness = 1
        letterSpacingEm = 0f
        dropCaps = false
    }

    fun displaySettings() = DisplaySettings(
        fullRefreshEveryNPages = refreshEvery.coerceIn(1, 30),
        boldnessLevel = boldness.coerceIn(0, 10),
        preferredTransition = try { PageTransition.valueOf(transition) } catch (_: Exception) { PageTransition.INSTANT },
        forcedKind = try { if (forcedKind.isEmpty()) null else DisplayKind.valueOf(forcedKind) } catch (_: Exception) { null },
        devicePreset = displayDevicePreset,
        ghostClearEnabled = ghostClearEnabled
    )

    // Key remapping: short and long press per key code.
    private val defaultShort = mapOf(
        24 to ReaderAction.PREV, 25 to ReaderAction.NEXT, 92 to ReaderAction.PREV, 93 to ReaderAction.NEXT,
        19 to ReaderAction.PREV, 20 to ReaderAction.NEXT, 21 to ReaderAction.PREV, 22 to ReaderAction.NEXT,
        23 to ReaderAction.MENU, 66 to ReaderAction.MENU, 87 to ReaderAction.NEXT, 88 to ReaderAction.PREV,
        85 to ReaderAction.NEXT, 79 to ReaderAction.NEXT
    )
    private val defaultLong = mapOf(24 to ReaderAction.FULL_REFRESH, 25 to ReaderAction.BOOKMARK)

    fun keyAction(code: Int, long: Boolean): ReaderAction {
        val k = (if (long) "key_l_" else "key_s_") + code
        val s = sp.getString(k, null)
        if (s != null) return try { ReaderAction.valueOf(s) } catch (_: Exception) { ReaderAction.NONE }
        return (if (long) defaultLong else defaultShort)[code] ?: ReaderAction.NONE
    }
    fun setKeyAction(code: Int, long: Boolean, a: ReaderAction) {
        sp.edit().putString((if (long) "key_l_" else "key_s_") + code, a.name).apply()
    }

    // 3x3 touch zones (row-major). Defaults: left column prev, right column next, centre menu, bottom-centre full refresh.
    fun zoneAction(row: Int, col: Int): ReaderAction {
        val s = sp.getString("zone_${row}_$col", null)
        if (s != null) return try { ReaderAction.valueOf(s) } catch (_: Exception) { ReaderAction.NONE }
        return when {
            col == 0 -> ReaderAction.PREV
            col == 2 -> ReaderAction.NEXT
            row == 2 -> ReaderAction.FULL_REFRESH
            else -> ReaderAction.MENU
        }
    }
    fun setZoneAction(row: Int, col: Int, a: ReaderAction) { sp.edit().putString("zone_${row}_$col", a.name).apply() }

    // Custom Bookshelves & Book-to-Shelf Mapping
    var selectedShelf by str("selectedShelf_v1", "") // "" means "전체 도서"

    fun getShelves(): List<String> {
        val json = sp.getString("custom_shelves_v1", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).trim()
                if (s.isNotEmpty() && !list.contains(s)) list.add(s)
            }
            list
        } catch (_: Throwable) { emptyList() }
    }

    fun addShelf(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val current = getShelves().toMutableList()
        if (current.contains(trimmed)) return false
        current.add(trimmed)
        val arr = org.json.JSONArray()
        current.forEach { arr.put(it) }
        sp.edit().putString("custom_shelves_v1", arr.toString()).apply()
        return true
    }

    fun renameShelf(oldName: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || oldName == trimmed) return false
        val current = getShelves().toMutableList()
        val idx = current.indexOf(oldName)
        if (idx < 0) return false
        current[idx] = trimmed
        val arr = org.json.JSONArray()
        current.forEach { arr.put(it) }

        val map = getBookShelfMap().toMutableMap()
        for ((path, shelf) in map) {
            if (shelf == oldName) map[path] = trimmed
        }
        val mapJson = org.json.JSONObject()
        map.forEach { (k, v) -> mapJson.put(k, v) }

        sp.edit()
            .putString("custom_shelves_v1", arr.toString())
            .putString("book_shelf_map_v1", mapJson.toString())
            .apply()

        if (selectedShelf == oldName) selectedShelf = trimmed
        return true
    }

    fun deleteShelf(name: String): Boolean {
        val current = getShelves().toMutableList()
        if (!current.remove(name)) return false
        val arr = org.json.JSONArray()
        current.forEach { arr.put(it) }

        val map = getBookShelfMap().toMutableMap()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value == name) it.remove()
        }
        val mapJson = org.json.JSONObject()
        map.forEach { (k, v) -> mapJson.put(k, v) }

        sp.edit()
            .putString("custom_shelves_v1", arr.toString())
            .putString("book_shelf_map_v1", mapJson.toString())
            .apply()

        if (selectedShelf == name) selectedShelf = ""
        return true
    }

    private fun getBookShelfMap(): Map<String, String> {
        val json = sp.getString("book_shelf_map_v1", "{}") ?: "{}"
        return try {
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.getString(k)
            }
            map
        } catch (_: Throwable) { emptyMap() }
    }

    fun getBookShelf(bookPath: String): String {
        return getBookShelfMap()[bookPath] ?: ""
    }

    fun setBookShelf(bookPath: String, shelfName: String) {
        val map = getBookShelfMap().toMutableMap()
        if (shelfName.isBlank()) {
            map.remove(bookPath)
        } else {
            map[bookPath] = shelfName.trim()
        }
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        sp.edit().putString("book_shelf_map_v1", obj.toString()).apply()
    }

    fun setBooksShelf(bookPaths: Collection<String>, shelfName: String) {
        val map = getBookShelfMap().toMutableMap()
        val s = shelfName.trim()
        for (path in bookPaths) {
            if (s.isBlank()) {
                map.remove(path)
            } else {
                map[path] = s
            }
        }
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        sp.edit().putString("book_shelf_map_v1", obj.toString()).apply()
    }
}
