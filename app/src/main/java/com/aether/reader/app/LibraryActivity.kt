package com.aether.reader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.aether.reader.App
import com.aether.reader.data.*
import com.aether.reader.domain.display.DisplayKind
import com.aether.reader.domain.display.DisplayStrategy
import com.aether.reader.engine.Containers
import com.aether.reader.platform.NavAssistService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.util.Locale
import java.util.LinkedHashSet

class LibraryActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val p = Prefs(newBase)
        val lang = p.getEffectiveLanguage(newBase)
        val localized = L10n.applyLocale(newBase, lang)
        super.attachBaseContext(localized)
    }

    private lateinit var app: App
    private lateinit var grid: GridView
    private lateinit var emptyBox: LinearLayout
    private lateinit var continueCard: LinearLayout
    private lateinit var continueTitle: TextView
    private lateinit var continueMeta: TextView
    private lateinit var continueProgress: ProgressBar
    private lateinit var summaryText: TextView
    private lateinit var shelfBtn: Button
    private lateinit var filterBtn: Button
    private lateinit var sortBtn: Button
    private lateinit var searchBtn: Button
    private lateinit var strategy: DisplayStrategy
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var allBooks: List<BookEntity> = emptyList()
    private var items: List<BookEntity> = emptyList()
    private var statusFilter = 0 // 0: 전체, 1: 읽는 중, 2: 읽기 전, 3: 완독
    private var searchQuery = ""

    private var isSelectMode = false
    private val selectedPaths = LinkedHashSet<String>()
    private lateinit var selectBtn: Button
    private lateinit var selectionBottomBar: LinearLayout
    private lateinit var selectAllBtn: Button
    private lateinit var moveShelfBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var emailBtn: Button

    private val thumbCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceIn(4096, 65536)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight: Int = options.outHeight / 2
                val halfWidth: Int = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            options.inSampleSize = inSampleSize.coerceAtLeast(1)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565
            return BitmapFactory.decodeFile(path, options)
        } catch (_: Throwable) {
            return null
        }
    }

    private val fileImporter by lazy { FileImportManager(this, app.db, app.covers) }
    private val backupManager by lazy { BackupRestoreManager(this, app.db, app.prefs) }
    private var cachedShelfMap: Map<String, String> = emptyMap()

    // Active palette colors
    private var isDark = false
    private var bgCol = ReaderTheme.EINK_PAPER
    private var inkCol = ReaderTheme.EINK_INK
    private var mutedCol = ReaderTheme.EINK_MUTED
    private var cardBgCol = ReaderTheme.EINK_CARD_BG
    private var borderCol = ReaderTheme.EINK_BORDER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        L10n.applyLocale(this, app.prefs.getEffectiveLanguage(this))
        overridePendingTransition(0, 0)

        applyThemeColors()
        buildUi()
        window.decorView.hideSystemBars()

        scope.launch(Dispatchers.IO) {
            try {
                app.db.books().normalizeFilePaths()
                app.db.highlights().normalizeFilePaths()
                migrateExistingContentBooks()
                if (!app.prefs.isDefaultGuideInstalled) {
                    seedDefaultGuideBookIfNeeded()
                }
            } catch (_: Throwable) {}
        }

        scope.launch {
            app.db.books().observeAll().collectLatest { list ->
                delay(80)
                allBooks = list
                updateList()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            thumbCache.evictAll()
        }
    }

    private fun applyThemeColors() {
        strategy = app.profiles.detect(app.prefs.displaySettings())
        isDark = app.prefs.dark && (strategy.layoutSpec.allowDarkMode || strategy.kind == DisplayKind.LCD_OLED)
        if (isDark) {
            bgCol = if (strategy.layoutSpec.trueBlackBackground) Color.BLACK else ReaderTheme.DARK_SURFACE
            inkCol = ReaderTheme.DARK_TEXT
            mutedCol = ReaderTheme.DARK_MUTED
            cardBgCol = ReaderTheme.DARK_CARD
            borderCol = ReaderTheme.DARK_BORDER
        } else {
            bgCol = ReaderTheme.EINK_PAPER
            inkCol = ReaderTheme.EINK_INK
            mutedCol = ReaderTheme.EINK_MUTED
            val isLcd = (application as App).isLcdMode(strategy)
            cardBgCol = if (isLcd) ReaderTheme.EINK_CARD_BG_LIGHT else ReaderTheme.EINK_CARD_BG
            borderCol = if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_BORDER_CONTRAST
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCol)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        // 1. Top App Bar (Clean Minimalist clean minimalist header)
        val header = RelativeLayout(this).apply {
            setPadding(0, dp(4), 0, dp(10))
        }

        val brandLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = View.generateViewId()
        }
        val appTitle = TextView(this).apply {
            val brand = "Aether SAGA"
            val verText = "  Ver. 2.6"
            val subText = "\njourneys through endless stories"
            val ssb = android.text.SpannableStringBuilder(brand).apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, brand.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val verStart = length
                append(verText)
                setSpan(android.text.style.RelativeSizeSpan(0.55f), verStart, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(mutedCol), verStart, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val subStart = length
                append(subText)
                setSpan(android.text.style.RelativeSizeSpan(0.60f), subStart, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(mutedCol), subStart, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.StyleSpan(Typeface.ITALIC), subStart, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = ssb
            textSize = 21f
            setTextColor(inkCol)
            typeface = Typeface.create("serif", Typeface.NORMAL)
            letterSpacing = 0.02f
        }
        summaryText = TextView(this).apply {
            text = L10n.t("내 책장 (0권)", "My Bookshelf (0 books)")
            textSize = 12f
            setTextColor(mutedCol)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, dp(2), 0, 0)
            setOnClickListener {
                val isEink = if (::strategy.isInitialized) strategy.kind != DisplayKind.LCD_OLED else true
                StatsDashboardDialog.show(this@LibraryActivity, app, isEink)
            }
        }
        brandLayout.addView(appTitle)
        brandLayout.addView(summaryText)

        val headerActions = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        selectBtn = pillButton(L10n.t("선택", "Select"), inkCol, cardBgCol, borderCol) {
            if (isSelectMode) exitSelectMode() else enterSelectMode()
        }
        val importBtn = pillButton(L10n.t("+ 책 추가", "+ Add Book"), if (isDark) Color.BLACK else Color.WHITE, if (isDark) Color.WHITE else ReaderTheme.ACCENT_CHARCOAL) {
            showBookPicker()
        }
        val settingsBtn = pillButton(L10n.t("설정", "Settings"), inkCol, cardBgCol, borderCol) { settingsMenu() }

        headerActions.addView(selectBtn)
        headerActions.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        headerActions.addView(importBtn)
        headerActions.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        headerActions.addView(settingsBtn)

        val actionParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            addRule(RelativeLayout.CENTER_VERTICAL)
        }
        val brandParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.LEFT_OF, headerActions.id)
            rightMargin = dp(8)
        }

        header.addView(headerActions, actionParams)
        header.addView(brandLayout, brandParams)
        root.addView(header)

        // 2. Continue Reading Banner (Modern Minimalist Home style)
        continueCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        val continueTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val continueBadge = TextView(this).apply {
            text = L10n.t("최근 읽던 책 이어보기", "Continue Reading")
            textSize = 10.5f
            setTextColor(ReaderTheme.ACCENT_PRIMARY_BLUE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        continueTitle = TextView(this).apply {
            textSize = 14.5f
            setTextColor(inkCol)
            typeface = Typeface.create("serif", Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(2))
        }
        continueMeta = TextView(this).apply {
            textSize = 11.5f
            setTextColor(mutedCol)
            maxLines = 1
        }
        continueProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val isEink = app.hardware.spec.displayKind != DisplayKind.LCD_OLED

            // 1. Full-width 100% background track visible across the entire card
            background = GradientDrawable().apply {
                setColor(if (isEink) 0xFFDCDCDC.toInt() else 0xFFE2E8F0.toInt())
                cornerRadius = dp(3).toFloat()
                if (isEink) {
                    setStroke(dp(1), 0xFF9E9E9E.toInt())
                }
            }

            // 2. LayerDrawable for clipped progress fill
            val trackBg = GradientDrawable().apply {
                setColor(if (isEink) 0xFFDCDCDC.toInt() else 0xFFE2E8F0.toInt())
                cornerRadius = dp(3).toFloat()
            }
            val progressG = GradientDrawable().apply {
                setColor(if (isEink) Color.BLACK else ReaderTheme.ACCENT_PRIMARY_BLUE)
                cornerRadius = dp(3).toFloat()
            }
            val clip = android.graphics.drawable.ClipDrawable(progressG, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            val layer = android.graphics.drawable.LayerDrawable(arrayOf(trackBg, clip)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            progressDrawable = layer
        }
        continueTextCol.addView(continueBadge)
        continueTextCol.addView(continueTitle)
        continueTextCol.addView(continueMeta)
        continueTextCol.addView(continueProgress, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(6) })

        val continueBtn = pillButton(L10n.t("이어읽기 ▶", "Resume ▶"), inkCol, bgCol, borderCol) {
            allBooks.filter { it.lastRead > 0 }.maxByOrNull { it.lastRead }?.let { openBook(it) }
        }
        continueCard.addView(continueTextCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
        continueCard.addView(continueBtn)
        root.addView(continueCard, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // 3. Sub-bar: Status Filter, Sort & Search Chips
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(10))
        }

        shelfBtn = pillButton(L10n.t("📚 책장: 전체", "📚 Shelf: All"), inkCol, cardBgCol, borderCol) {
            showShelfFilterDialog()
        }
        filterBtn = pillButton(L10n.t("전체 도서", "All Books"), if (isDark) Color.BLACK else Color.WHITE, if (isDark) Color.WHITE else ReaderTheme.ACCENT_CHARCOAL) {
            statusFilter = (statusFilter + 1) % 4
            updateList()
        }
        sortBtn = pillButton("", inkCol, cardBgCol, borderCol) {
            app.prefs.librarySort = (app.prefs.librarySort + 1) % 3
            updateList()
        }
        searchBtn = pillButton(L10n.t("🔍 검색", "🔍 Search"), inkCol, cardBgCol, borderCol) {
            if (searchQuery.isNotBlank()) {
                searchQuery = ""
                updateList()
            } else {
                inputDialog(L10n.t("책장 내 도서 검색 (제목/저자)", "Search library (Title/Author)"), searchQuery) { q ->
                    searchQuery = q.trim()
                    updateList()
                }
            }
        }

        val highlightsBtn = pillButton(L10n.t("🖍 독서 기록", "🖍 Notes & Bookmarks"), inkCol, cardBgCol, borderCol) {
            showMainHighlightsDialog()
        }
        val shareNotesBtn = pillButton(L10n.t("📢 독서 기록 공유", "📢 Share Reading Notes"), inkCol, cardBgCol, borderCol) {
            showMainNotesShareDialog()
        }

        filterBar.addView(shelfBtn)
        filterBar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        filterBar.addView(filterBtn)
        filterBar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        filterBar.addView(sortBtn)
        filterBar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        filterBar.addView(searchBtn)
        filterBar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        filterBar.addView(highlightsBtn)
        filterBar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        filterBar.addView(shareNotesBtn)
        filterScroll.addView(filterBar)
        root.addView(filterScroll)

        // Divider
        val divider = View(this).apply { setBackgroundColor(borderCol) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))

        // 4. Content Frame (GridView + Empty State View)
        val contentFrame = FrameLayout(this)

        val displayW = resources.displayMetrics.widthPixels
        val colWidth = dp(130)
        val columns = (displayW / colWidth).coerceIn(2, 6)

        grid = GridView(this).apply {
            numColumns = columns
            horizontalSpacing = dp(14)
            verticalSpacing = dp(18)
            setPadding(0, dp(14), 0, dp(16))
            clipToPadding = false
            enableVisibleScrollbar(isDark = isDark, widthDp = 6)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = BookGridAdapter()
            setOnItemClickListener { _, _, pos, _ ->
                if (pos in items.indices) {
                    val book = items[pos]
                    if (isSelectMode) {
                        toggleBookSelection(book.path)
                    } else {
                        openBook(book)
                    }
                }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                if (pos in items.indices) {
                    val book = items[pos]
                    if (isSelectMode) {
                        toggleBookSelection(book.path)
                    } else {
                        bookContextMenu(book)
                    }
                }
                true
            }
        }
        contentFrame.addView(grid, FrameLayout.LayoutParams(-1, -1))

        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
            visibility = View.GONE

            val emptyIcon = TextView(context).apply {
                text = "📚"
                textSize = 36f
                gravity = Gravity.CENTER
            }
            val emptyTitle = TextView(context).apply {
                text = L10n.t("책장에 추가된 도서가 없습니다", "Your library is empty")
                textSize = 17f
                setTextColor(inkCol)
                typeface = Typeface.create("serif", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(6))
            }
            val emptyDesc = TextView(context).apply {
                text = L10n.t("아래 버튼을 눌러 기기 또는 SD카드에서 원하는 전자책 파일(EPUB, PDF, TXT, CBZ)을 선택해 나만의 책장에 추가해 보세요.", "Tap the button below to add your e-book files (EPUB, PDF, TXT, CBZ) from storage to your library.")
                textSize = 13f
                setTextColor(mutedCol)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
                setPadding(0, 0, 0, dp(18))
            }
            val addFileBtn = pillButton(L10n.t("+ 파일 불러와서 책장에 추가하기", "+ Import Books to Library"), if (isDark) Color.BLACK else Color.WHITE, if (isDark) Color.WHITE else ReaderTheme.ACCENT_CHARCOAL) {
                showBookPicker()
            }
            val guideBtn = pillButton(L10n.t("📖 공식 사용 설명서 열기", "📖 Open Official User Guide"), inkCol, cardBgCol, borderCol) {
                scope.launch {
                    val guide = seedDefaultGuideBookIfNeeded() ?: allBooks.firstOrNull { it.path.contains("AetherSAGA_User_Guide") }
                    if (guide != null) {
                        openBook(guide)
                    }
                }
            }
            addView(emptyIcon)
            addView(emptyTitle)
            addView(emptyDesc)
            addView(addFileBtn)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
            addView(guideBtn)
        }
        contentFrame.addView(emptyBox, FrameLayout.LayoutParams(-1, -1))

        root.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        // 5. Selection Bottom Bar (Batch Delete / Email Book Files)
        selectionBottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }

        selectAllBtn = pillButton(L10n.t("전체 선택", "Select All"), inkCol, bgCol, borderCol) { toggleSelectAll() }
        moveShelfBtn = pillButton(L10n.t("📁 책장 이동 (0)", "📁 Move Shelf (0)"), inkCol, bgCol, borderCol) { moveSelectedBooksToShelf() }
        emailBtn = pillButton(L10n.t("✉ 메일 (0)", "✉ Email (0)"), Color.WHITE, ReaderTheme.ACCENT_PRIMARY_BLUE) { emailSelectedBooks() }
        deleteBtn = pillButton(L10n.t("🗑 삭제 (0)", "🗑 Delete (0)"), Color.WHITE, 0xFFC62828.toInt()) { deleteSelectedBooks() }
        val cancelSelectionBtn = pillButton("✕", inkCol, bgCol, borderCol) { exitSelectMode() }.apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        selectionBottomBar.addView(selectAllBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        selectionBottomBar.addView(moveShelfBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply { rightMargin = dp(4) })
        selectionBottomBar.addView(emailBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
        selectionBottomBar.addView(deleteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
        selectionBottomBar.addView(cancelSelectionBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(selectionBottomBar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.hideSystemBars()
    }

    private fun updateList() {
        val p = app.prefs

        val selShelf = p.selectedShelf
        if (selShelf.isBlank()) {
            shelfBtn.text = L10n.t("📚 책장: 전체", "📚 Shelf: All")
            shelfBtn.setTextColor(inkCol)
            shelfBtn.background = GradientDrawable().apply {
                setColor(cardBgCol)
                setStroke(dp(1).coerceAtLeast(1), borderCol)
                cornerRadius = dp(16).toFloat()
            }
        } else if (selShelf == "__UNCLASSIFIED__") {
            shelfBtn.text = L10n.t("📁 책장: 미분류", "📁 Shelf: Unclassified")
            shelfBtn.setTextColor(if (isDark) Color.BLACK else Color.WHITE)
            shelfBtn.background = GradientDrawable().apply {
                setColor(if (isDark) Color.WHITE else ReaderTheme.ACCENT_CHARCOAL)
                cornerRadius = dp(16).toFloat()
            }
        } else {
            shelfBtn.text = L10n.t("📁 책장: $selShelf", "📁 Shelf: $selShelf")
            shelfBtn.setTextColor(if (isDark) Color.BLACK else Color.WHITE)
            shelfBtn.background = GradientDrawable().apply {
                setColor(if (isDark) Color.WHITE else ReaderTheme.ACCENT_CHARCOAL)
                cornerRadius = dp(16).toFloat()
            }
        }

        filterBtn.text = when (statusFilter) {
            1 -> L10n.t("분류: 읽는 중", "Filter: Reading")
            2 -> L10n.t("분류: 읽기 전", "Filter: Unread")
            3 -> L10n.t("분류: 완독", "Filter: Finished")
            else -> L10n.t("분류: 전체 도서", "Filter: All Books")
        }

        sortBtn.text = when (p.librarySort) {
            0 -> L10n.t("정렬: 최근 읽은 순", "Sort: Recent")
            1 -> L10n.t("정렬: 제목순", "Sort: Title")
            else -> L10n.t("정렬: 작가순", "Sort: Author")
        }

        searchBtn.text = if (searchQuery.isNotBlank()) "🔍 \"$searchQuery\" ✕" else L10n.t("🔍 검색", "🔍 Search")

        // Update Continue Reading card
        val recent = allBooks.filter { it.lastRead > 0 }.maxByOrNull { it.lastRead }
        if (recent != null && searchQuery.isBlank() && selShelf.isBlank()) {
            continueCard.visibility = View.VISIBLE
            continueTitle.text = recent.title
            val pct = (recent.progress * 100).toInt().coerceIn(0, 100)
            continueMeta.text = "${if (recent.author.isNotBlank()) "${recent.author}  •  " else ""}${recent.format}  •  진행률 ${pct}%"
            continueProgress.progress = pct.coerceAtLeast(2)
            continueCard.setOnClickListener { openBook(recent) }
        } else {
            continueCard.visibility = View.GONE
        }

        val shelfMap = p.getAllBookShelves()
        cachedShelfMap = shelfMap
        val rawList = allBooks.filter { b ->
            val bookShelf = shelfMap[b.path].orEmpty()
            val matchShelf = when (selShelf) {
                "" -> true
                "__UNCLASSIFIED__" -> bookShelf.isBlank()
                else -> bookShelf == selShelf
            }
            val matchStatus = when (statusFilter) {
                1 -> b.progress > 0f && b.progress < 0.98f
                2 -> b.progress <= 0f
                3 -> b.progress >= 0.98f
                else -> true
            }
            val matchQuery = searchQuery.isBlank() ||
                b.title.contains(searchQuery, ignoreCase = true) ||
                b.author.contains(searchQuery, ignoreCase = true)
            matchShelf && matchStatus && matchQuery
        }

        scope.launch {
            val res: List<BookEntity> = withContext(Dispatchers.Default) {
                when (p.librarySort) {
                    0 -> rawList.sortedWith(compareByDescending<BookEntity> { it.lastRead }.thenByDescending { it.addedAt }.thenBy { it.title.lowercase() })
                    1 -> rawList.sortedBy { it.title.lowercase() }
                    else -> rawList.sortedWith(compareBy<BookEntity> { it.author.ifBlank { "~" }.lowercase() }.thenBy { it.title.lowercase() })
                }
            }
            items = res
            (grid.adapter as BaseAdapter).notifyDataSetChanged()

            if (items.isEmpty()) {
                emptyBox.visibility = View.VISIBLE
                grid.visibility = View.GONE
            } else {
                emptyBox.visibility = View.GONE
                grid.visibility = View.VISIBLE
            }

            if (isSelectMode) {
                selectedPaths.retainAll(items.map { it.path }.toSet())
                updateSelectionUi()
            } else {
                val shelfName = when (selShelf) {
                    "" -> L10n.t("내 책장", "My Bookshelf")
                    "__UNCLASSIFIED__" -> L10n.t("[미분류] 책장", "[Unclassified] Bookshelf")
                    else -> L10n.t("[$selShelf] 책장", "[$selShelf] Bookshelf")
                }
                val baseSummary = if (rawList.size != allBooks.size) {
                    if (L10n.isKorean) "$shelfName ${rawList.size}권 (전체 ${allBooks.size}권)"
                    else "$shelfName ${rawList.size} (Total ${allBooks.size})"
                } else {
                    if (L10n.isKorean) "$shelfName ${allBooks.size}권"
                    else "$shelfName ${allBooks.size} books"
                }
                val p = app.prefs
                val todayStr = if (p.todayPages > 0 || p.todayMinutes > 0) {
                    if (L10n.isKorean) "  •  오늘 독서 ${p.todayMinutes}분 (${p.todayPages}p)"
                    else "  •  Today: ${p.todayMinutes}m (${p.todayPages}p)"
                } else ""
                summaryText.text = "$baseSummary$todayStr"
            }
        }
    }

    private fun showBookPicker() {
        FolderPickerHelper.show(
            context = this,
            dialogTitle = L10n.t("도서 파일 선택 (+ 책 추가)", "Select Book Files (+ Add Books)"),
            allowedExtensions = FolderPickerHelper.BOOK_EXTENSIONS,
            onFilesSelected = { files ->
                if (files.isEmpty()) return@show
                val uris = files.map { Uri.fromFile(it) }
                summaryText.text = L10n.t("${files.size}권의 도서 추가 중...", "Importing ${files.size} books...")
                scope.launch {
                    val count = fileImporter.importUris(uris) { c ->
                        runOnUiThread { summaryText.text = L10n.t("책장에 추가 중... $c/${files.size}권", "Adding to library... $c/${files.size}") }
                    }
                    Toast.makeText(this@LibraryActivity, L10n.t("${count}권의 도서가 책장에 추가되었습니다.", "${count} books added to library."), Toast.LENGTH_SHORT).show()
                    updateList()
                }
            },
            onSystemPickerFallback = {
                openDocumentPicker()
            }
        )
    }

    private fun showFontPicker() {
        FolderPickerHelper.show(
            context = this,
            dialogTitle = "폰트 파일 선택 (.ttf/.otf)",
            allowedExtensions = FolderPickerHelper.FONT_EXTENSIONS,
            onFileSelected = { file ->
                scope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        try {
                            val destDir = File(filesDir, "fonts").apply { mkdirs() }
                            val outFile = File(destDir, file.name)
                            file.copyTo(outFile, overwrite = true)
                            Typeface.createFromFile(outFile)
                            outFile
                        } catch (_: Throwable) { null }
                    }
                    if (imported != null) {
                        app.prefs.fontPath = imported.absolutePath
                        Toast.makeText(this@LibraryActivity, "'${imported.nameWithoutExtension}' 폰트 적용 완료", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@LibraryActivity, "유효한 .ttf 또는 .otf 폰트 파일을 선택해 주세요.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSystemPickerFallback = {
                showSystemNavigationBar()
                val intent = Intent(this@LibraryActivity, DocumentPickerActivity::class.java).apply {
                    putExtra("EXTRA_MODE", "FONT")
                }
                startActivityForResult(intent, 901)
            }
        )
    }

    private fun openDocumentPicker() {
        showSystemNavigationBar()
        val intent = Intent(this@LibraryActivity, DocumentPickerActivity::class.java).apply {
            putExtra("EXTRA_MODE", "BOOK")
        }
        startActivityForResult(intent, 888)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 888 && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.data?.let { uris.add(it) }
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    uris.add(clip.getItemAt(i).uri)
                }
            }
            if (uris.isNotEmpty()) {
                summaryText.text = L10n.t("${uris.size}권의 도서 추가 중...", "Importing ${uris.size} books...")
                scope.launch {
                    val count = fileImporter.importUris(uris) { c ->
                        runOnUiThread { summaryText.text = L10n.t("책장에 추가 중... $c 권", "Adding to library... $c books") }
                    }
                    Toast.makeText(this@LibraryActivity, L10n.t("${count}권의 도서가 책장에 추가되었습니다.", "${count} books added to library."), Toast.LENGTH_SHORT).show()
                    updateList()
                }
            }
        } else if (requestCode == 901 && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.data?.let { uris.add(it) }
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    uris.add(clip.getItemAt(i).uri)
                }
            }
            if (uris.isNotEmpty()) {
                scope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        val destDir = File(filesDir, "fonts").apply { mkdirs() }
                        val list = ArrayList<File>()
                        for (uri in uris) {
                            try {
                                var name = "custom_font_${System.currentTimeMillis()}.ttf"
                                contentResolver.query(uri, null, null, null, null)?.use { c ->
                                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (idx >= 0 && c.moveToFirst()) {
                                        val n = c.getString(idx)
                                        if (!n.isNullOrBlank()) name = n
                                    }
                                }
                                val ext = name.substringAfterLast('.', "").lowercase()
                                if (ext != "ttf" && ext != "otf") continue
                                val outFile = File(destDir, name)
                                contentResolver.openInputStream(uri)?.use { input ->
                                    outFile.outputStream().use { output -> input.copyTo(output) }
                                }
                                Typeface.createFromFile(outFile)
                                list.add(outFile)
                            } catch (_: Throwable) {}
                        }
                        list
                    }
                    if (imported.isNotEmpty()) {
                        val lastFont = imported.last()
                        app.prefs.fontPath = lastFont.absolutePath
                        Toast.makeText(
                            this@LibraryActivity,
                            "폰트 ${imported.size}종 추가 및 '${lastFont.nameWithoutExtension}' 적용 완료",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@LibraryActivity,
                            "유효한 .ttf 또는 .otf 폰트 파일을 선택해 주세요.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private suspend fun migrateExistingContentBooks() {
        try {
            val all = app.db.books().all()
            for (b in all) {
                if (b.path.startsWith("content://")) {
                    try {
                        val fmt = runCatching { com.aether.reader.engine.BookFormat.valueOf(b.format) }.getOrNull() ?: com.aether.reader.engine.BookFormat.TXT
                        val local = Containers.resolveContentToFile(b.path, this, fmt, b.title)
                        if (local.isNotBlank() && File(local).exists() && local != b.path) {
                            app.db.books().updatePath(b.path, local)
                            app.db.highlights().updateBookPath(b.path, local)
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    private suspend fun seedDefaultGuideBookIfNeeded(): BookEntity? = withContext(Dispatchers.IO) {
        try {
            val booksDir = File(filesDir, "books").apply { mkdirs() }
            val guideFile = File(booksDir, "AetherSAGA_User_Guide.epub")
            if (!guideFile.exists() || guideFile.length() == 0L || !app.prefs.isDefaultGuideInstalled) {
                assets.open("books/AetherSAGA_User_Guide.epub").use { input ->
                    guideFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            if (guideFile.exists() && guideFile.length() > 0L) {
                val path = Containers.normalizePath(guideFile.absolutePath)
                val existing = app.db.books().get(path)
                if (existing != null) {
                    if (existing.sizeBytes != guideFile.length()) {
                        val updated = existing.copy(sizeBytes = guideFile.length())
                        app.db.books().insertAll(listOf(updated))
                        app.prefs.isDefaultGuideInstalled = true
                        return@withContext updated
                    }
                    app.prefs.isDefaultGuideInstalled = true
                    return@withContext existing
                }
                var title = "Aether SAGA 사용 설명서"
                var author = "Aether SAGA 개발팀"
                var coverPath: String? = null
                try {
                    Containers.open(path, this@LibraryActivity, hintFormat = com.aether.reader.engine.BookFormat.EPUB).use { c ->
                        if (c.title.isNotBlank()) title = c.title
                        if (c.author.isNotBlank()) author = c.author
                        coverPath = c.coverBytes()?.let { CoverCache(this@LibraryActivity).store(path, it) }
                    }
                } catch (_: Throwable) {}
                val entity = BookEntity(
                    path = path,
                    title = title,
                    author = author,
                    format = com.aether.reader.engine.BookFormat.EPUB.name,
                    sizeBytes = guideFile.length(),
                    addedAt = System.currentTimeMillis(),
                    coverPath = coverPath
                )
                app.db.books().insertAll(listOf(entity))
                app.prefs.isDefaultGuideInstalled = true
                return@withContext entity
            }
        } catch (e: Throwable) {
            android.util.Log.e("AetherLibrary", "Failed to seed default guide book", e)
        }
        null
    }

    private fun openBook(book: BookEntity) {
        val resolved = resolveOnDemandBookPath(book.path, book.sizeBytes)
        val cleanPath = Containers.normalizePath(resolved)
        try {
            val intent = Intent(this, ReaderActivity::class.java).apply {
                putExtra("path", cleanPath)
                if (cleanPath.startsWith("content://")) {
                    data = Uri.parse(cleanPath)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(intent)
        } catch (_: SecurityException) {
            try {
                val fallbackIntent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra("path", cleanPath)
                }
                startActivity(fallbackIntent)
            } catch (t: Throwable) {
                toast("도서를 열 수 없습니다: ${t.message}")
            }
        } catch (t: Throwable) {
            toast("도서를 열 수 없습니다: ${t.message}")
        }
    }

    private fun bookContextMenu(b: BookEntity) {
        val pct = (b.progress * 100).toInt()
        val curShelf = app.prefs.getBookShelf(b.path)
        val shelfDesc = if (curShelf.isBlank()) L10n.t("미분류", "Uncategorized") else curShelf
        val isLcd = (application as App).isLcdMode(if (::strategy.isInitialized) strategy else null)

        val optOpen = L10n.t("도서 열기 (진행률 $pct%)", "Open Book ($pct%)")
        val optShelf = L10n.t("📁 책장 이동 / 변경 (현재: $shelfDesc)", "📁 Move / Change Shelf (Current: $shelfDesc)")
        val optEmail = L10n.t("✉ 이메일로 도서 파일 전송", "✉ Send Book File via Email")
        val optZip = L10n.t("📦 하이라이트 압축파일(ZIP) 이메일 전송", "📦 Export Highlights (ZIP) via Email")
        val optShare = L10n.t("📢 독서 기록 공유 (카드 · 이메일)", "📢 Share Reading Notes (Card · Email)")
        val optMulti = L10n.t("☑ 도서 다중 선택 (일괄 관리)", "☑ Multi-Select Books (Batch)")
        val optComplete = L10n.t("완독으로 표시 (100%)", "Mark as Completed (100%)")
        val optReset = L10n.t("읽기 기록 초기화 (0%)", "Reset Reading Progress (0%)")
        val optDelete = L10n.t("책장에서 제거", "Remove from Library")
        val optCancel = L10n.t("취소", "Cancel")

        val menuOptions = mutableListOf(optOpen, optShelf, optEmail, optZip, optShare, optMulti, optComplete, optReset, optDelete, optCancel)

        listDialog(b.title, menuOptions) { i ->
            val opt = menuOptions[i]
            when (opt) {
                optOpen -> openBook(b)
                optShelf -> showMoveSingleBookShelfDialog(b)
                optEmail -> showEmailBookDialog(listOf(b))
                optZip -> {
                    scope.launch {
                        val hl = withContext(Dispatchers.IO) {
                            val list = app.db.highlights().forBook(b.path)
                            if (list.isNotEmpty()) list else app.db.highlights().forBook("file://${b.path}")
                        }
                        if (hl.isEmpty()) {
                            toast(L10n.t("이 도서에 저장된 하이라이트/메모가 없습니다", "No saved highlights or notes for this book"))
                        } else {
                            showEmailHighlightsZipDialog(app, b.title, b.author, hl)
                        }
                    }
                }
                optShare -> {
                    scope.launch {
                        val hl = withContext(Dispatchers.IO) {
                            val list = app.db.highlights().forBook(b.path)
                            if (list.isNotEmpty()) list else app.db.highlights().forBook("file://${b.path}")
                        }
                        withContext(Dispatchers.Main) {
                            showReadingNotesShareDialog(
                                app = app,
                                book = b,
                                highlights = hl,
                                totalChapters = 0,
                                rawCoverBytes = null,
                                isLcd = isLcd
                            )
                        }
                    }
                }
                optMulti -> enterSelectMode(b.path)
                optComplete -> scope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    app.db.books().updateProgress(b.path, now, b.chapter, b.charOffset, 1.0f)
                    app.recordBookCompleted(b.path, b.title, b.author, now)
                }
                optReset -> scope.launch(Dispatchers.IO) {
                    app.db.books().updateProgress(b.path, 0L, 0, 0L, 0f)
                }
                optDelete -> confirmDeleteBookWithHighlightOption(b)
            }
        }
    }

    private fun confirmDeleteBookWithHighlightOption(b: BookEntity) {
        scope.launch {
            val hlList = withContext(Dispatchers.IO) {
                val list = app.db.highlights().forBook(b.path)
                if (list.isNotEmpty()) list else app.db.highlights().forBook("file://${b.path}")
            }
            if (hlList.isNotEmpty()) {
                AlertDialog.Builder(this@LibraryActivity)
                    .setTitle(L10n.t("책장에서 제거 및 하이라이트 보관", "Remove from Library & Keep Highlights"))
                    .setMessage(L10n.t(
                        "'${b.title}' 도서를 책장에서 제거하시겠습니까?\n(기기의 원본 파일은 삭제되지 않습니다)\n\n이 책에 작성된 하이라이트/독서 메모(${hlList.size}건)를 삭제 후에도 책 정보와 함께 보관하시겠습니까?",
                        "Remove '${b.title}' from library?\n(Original file on device will not be deleted)\n\nKeep highlights and notes (${hlList.size}) in archive after removal?"
                    ))
                    .setPositiveButton(L10n.t("하이라이트 보관 및 책 제거", "Keep Highlights & Remove")) { _, _ ->
                        scope.launch(Dispatchers.IO) {
                            app.db.highlights().updateBookMeta(b.path, b.title, b.author)
                            app.db.books().delete(b.path)
                            withContext(Dispatchers.Main) {
                                toast(L10n.t("'${b.title}'의 하이라이트(${hlList.size}건)를 보관하고 책장에서 제거했습니다.", "'${b.title}' removed from library. Highlights (${hlList.size}) archived."))
                            }
                        }
                    }
                    .setNegativeButton(L10n.t("하이라이트도 함께 삭제", "Delete Highlights Too")) { _, _ ->
                        scope.launch(Dispatchers.IO) {
                            app.db.highlights().deleteForBook(b.path)
                            app.db.books().delete(b.path)
                            withContext(Dispatchers.Main) {
                                toast(L10n.t("'${b.title}' 도서 및 하이라이트를 삭제했습니다.", "'${b.title}' and highlights deleted."))
                            }
                        }
                    }
                    .setNeutralButton(L10n.t("취소", "Cancel"), null)
                    .create()
                    .showWithOptimalWidth(this@LibraryActivity)
            } else {
                AlertDialog.Builder(this@LibraryActivity)
                    .setTitle(L10n.t("책장에서 제거", "Remove from Library"))
                    .setMessage(L10n.t("'${b.title}' 도서를 책장에서 제거하시겠습니까?\n(기기의 원본 파일은 삭제되지 않습니다)", "Remove '${b.title}' from library?\n(Original file on device will not be deleted)"))
                    .setPositiveButton(L10n.t("제거", "Remove")) { _, _ ->
                        scope.launch(Dispatchers.IO) {
                            app.db.books().delete(b.path)
                        }
                    }
                    .setNegativeButton(L10n.t("취소", "Cancel"), null)
                    .create()
                    .showWithOptimalWidth(this@LibraryActivity)
            }
        }
    }

    private fun showShelfFilterDialog() {
        val shelves = app.prefs.getShelves()
        val currentShelf = app.prefs.selectedShelf
        val unclassifiedCount = allBooks.count { app.prefs.getBookShelf(it.path).isBlank() }

        val options = mutableListOf<Pair<String, () -> Unit>>()
        val allMark = if (currentShelf.isBlank()) "✓ " else ""
        options.add("📚 ${allMark}전체 도서 보기 (${allBooks.size}권)" to {
            app.prefs.selectedShelf = ""
            updateList()
        })
        val unclassMark = if (currentShelf == "__UNCLASSIFIED__") "✓ " else ""
        options.add("📁 ${unclassMark}[미분류] 도서 (${unclassifiedCount}권)" to {
            app.prefs.selectedShelf = "__UNCLASSIFIED__"
            updateList()
        })

        for (s in shelves) {
            val count = allBooks.count { app.prefs.getBookShelf(it.path) == s }
            val mark = if (currentShelf == s) "✓ " else ""
            options.add("📁 $mark$s (${count}권)" to {
                app.prefs.selectedShelf = s
                updateList()
            })
        }

        options.add(L10n.t("➕ 새 책장 만들기...", "➕ Create New Shelf...") to {
            inputDialog(L10n.t("새 책장 만들기", "Create New Shelf"), "") { name ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    if (app.prefs.addShelf(trimmed)) {
                        toast(L10n.t("[$trimmed] 책장이 생성되었습니다.", "Shelf [$trimmed] created."))
                        app.prefs.selectedShelf = trimmed
                        updateList()
                    } else {
                        toast(L10n.t("이미 존재하거나 유효하지 않은 책장 이름입니다.", "Shelf already exists or name invalid."))
                    }
                }
            }
        })

        if (shelves.isNotEmpty()) {
            options.add(L10n.t("⚙ 책장 관리 (이름 변경 / 삭제)...", "⚙ Manage Shelves (Rename / Delete)...") to {
                showShelfManagementDialog()
            })
        }

        listDialog(L10n.t("📚 책장 선택", "📚 Select Shelf"), options.map { it.first }) { idx ->
            options[idx].second.invoke()
        }
    }

    private fun showShelfManagementDialog() {
        val shelves = app.prefs.getShelves()
        if (shelves.isEmpty()) {
            toast(L10n.t("관리할 사용자 정의 책장이 없습니다.", "No custom shelves to manage."))
            return
        }
        listDialog(L10n.t("⚙ 책장 관리 (편집/삭제할 책장 선택)", "⚙ Manage Shelves (Select Shelf)"), shelves.map { "📁 $it" }) { idx ->
            val targetShelf = shelves[idx]
            val actions = listOf(
                L10n.t("✏ 책장 이름 변경", "✏ Rename Shelf"),
                L10n.t("🗑 책장 삭제 (도서는 보존)", "🗑 Delete Shelf (Keep Books)"),
                L10n.t("취소", "Cancel")
            )
            listDialog(L10n.t("[$targetShelf] 책장 관리", "[$targetShelf] Manage Shelf"), actions) { action ->
                when (action) {
                    0 -> {
                        inputDialog(L10n.t("책장 이름 변경", "Rename Shelf"), targetShelf) { newName ->
                            val trimmed = newName.trim()
                            if (trimmed.isNotBlank() && trimmed != targetShelf) {
                                if (app.prefs.renameShelf(targetShelf, trimmed)) {
                                    toast(L10n.t("책장 이름이 '[$trimmed]' (으)로 변경되었습니다.", "Shelf renamed to '[$trimmed]'."))
                                    updateList()
                                } else {
                                    toast(L10n.t("이미 존재하는 책장 이름이거나 유효하지 않습니다.", "Shelf name already exists or is invalid."))
                                }
                            }
                        }
                    }
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle(L10n.t("책장 삭제", "Delete Shelf"))
                            .setMessage(L10n.t(
                                "정말 '[$targetShelf]' 책장을 삭제하시겠습니까?\n\n(책장에 포함된 도서는 삭제되지 않고 '미분류'로 안전하게 보존됩니다)",
                                "Delete shelf '[$targetShelf]'?\n\n(Books in this shelf will remain safe in 'Unclassified')"
                            ))
                            .setPositiveButton(L10n.t("삭제", "Delete")) { _, _ ->
                                if (app.prefs.deleteShelf(targetShelf)) {
                                    toast(L10n.t("[$targetShelf] 책장이 삭제되었습니다.", "Shelf [$targetShelf] deleted."))
                                    updateList()
                                }
                            }
                            .setNegativeButton(L10n.t("취소", "Cancel"), null)
                            .create()
                            .showWithOptimalWidth(this)
                    }
                }
            }
        }
    }

    private fun showMoveSingleBookShelfDialog(b: BookEntity) {
        val shelves = app.prefs.getShelves()
        val curShelf = app.prefs.getBookShelf(b.path)
        val options = mutableListOf<Pair<String, () -> Unit>>()

        val unclassMark = if (curShelf.isBlank()) L10n.t(" (현재)", " (Current)") else ""
        options.add(L10n.t("📁 [미분류] 기본 서재로 이동$unclassMark", "📁 Move to [Unclassified]$unclassMark") to {
            app.prefs.setBookShelf(b.path, "")
            toast(L10n.t("'${b.title}' 도서가 [미분류]로 이동되었습니다.", "'${b.title}' moved to [Unclassified]."))
            updateList()
        })

        for (s in shelves) {
            val mark = if (curShelf == s) L10n.t(" (현재 위치)", " (Current)") else ""
            options.add("📁 $s$mark" to {
                app.prefs.setBookShelf(b.path, s)
                toast(L10n.t("'${b.title}' 도서가 [$s] 책장으로 이동되었습니다.", "'${b.title}' moved to [$s]."))
                updateList()
            })
        }

        options.add(L10n.t("➕ 새 책장 만들어 이동...", "➕ Create New Shelf & Move...") to {
            inputDialog(L10n.t("새 책장 이름 입력", "Enter New Shelf Name"), "") { name ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    app.prefs.addShelf(trimmed)
                    app.prefs.setBookShelf(b.path, trimmed)
                    toast(L10n.t("'${b.title}' 도서가 [$trimmed] 책장으로 이동되었습니다.", "'${b.title}' moved to [$trimmed]."))
                    updateList()
                }
            }
        })

        listDialog(L10n.t("도서 책장 이동 / 재분류", "Move / Reclassify Shelf"), options.map { it.first }) { idx ->
            options[idx].second.invoke()
        }
    }

    private fun moveSelectedBooksToShelf() {
        val targets = items.filter { selectedPaths.contains(it.path) }
        if (targets.isEmpty()) {
            toast(L10n.t("이동할 도서를 먼저 선택해주세요.", "Please select books to move first."))
            return
        }
        val shelves = app.prefs.getShelves()
        val options = mutableListOf<Pair<String, () -> Unit>>()

        options.add(L10n.t("📁 [미분류] 기본 서재로 이동", "📁 Move to [Uncategorized]") to {
            app.prefs.setBooksShelf(selectedPaths, "")
            toast(L10n.t("선택한 ${targets.size}권의 도서가 [미분류]로 이동되었습니다.", "${targets.size} books moved to [Uncategorized]."))
            exitSelectMode()
            updateList()
        })

        for (s in shelves) {
            options.add(L10n.t("📁 $s 책장으로 이동", "📁 Move to [$s]") to {
                app.prefs.setBooksShelf(selectedPaths, s)
                toast(L10n.t("선택한 ${targets.size}권의 도서가 [$s] 책장으로 이동되었습니다.", "${targets.size} books moved to [$s]."))
                exitSelectMode()
                updateList()
            })
        }

        options.add(L10n.t("➕ 새 책장 만들어 이동...", "➕ Create New Shelf & Move...") to {
            inputDialog(L10n.t("새 책장 이름 입력", "Enter New Shelf Name"), "") { name ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    app.prefs.addShelf(trimmed)
                    app.prefs.setBooksShelf(selectedPaths, trimmed)
                    toast(L10n.t("선택한 ${targets.size}권의 도서가 [$trimmed] 책장으로 이동되었습니다.", "${targets.size} books moved to [$trimmed]."))
                    exitSelectMode()
                    updateList()
                }
            }
        })

        listDialog(L10n.t("선택한 ${targets.size}권 책장 이동 / 재분류", "Move ${targets.size} Selected Books"), options.map { it.first }) { idx ->
            options[idx].second.invoke()
        }
    }

    private fun enterSelectMode(initialPath: String? = null) {
        isSelectMode = true
        selectedPaths.clear()
        if (initialPath != null) {
            selectedPaths.add(initialPath)
        }
        selectBtn.text = L10n.t("완료", "Done")
        selectBtn.setTextColor(Color.WHITE)
        selectBtn.background = GradientDrawable().apply {
            setColor(ReaderTheme.ACCENT_CHARCOAL)
            cornerRadius = dp(16).toFloat()
        }
        selectionBottomBar.visibility = View.VISIBLE
        updateSelectionUi()
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun exitSelectMode() {
        isSelectMode = false
        selectedPaths.clear()
        selectBtn.text = L10n.t("선택", "Select")
        selectBtn.setTextColor(inkCol)
        selectBtn.background = GradientDrawable().apply {
            setColor(cardBgCol)
            setStroke(dp(1).coerceAtLeast(1), borderCol)
            cornerRadius = dp(16).toFloat()
        }
        selectionBottomBar.visibility = View.GONE
        updateList()
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun toggleSelectAll() {
        if (selectedPaths.size == items.size && items.isNotEmpty()) {
            selectedPaths.clear()
        } else {
            selectedPaths.clear()
            items.forEach { selectedPaths.add(it.path) }
        }
        updateSelectionUi()
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        val count = selectedPaths.size
        val allCount = items.size
        selectAllBtn.text = if (count == allCount && allCount > 0) L10n.t("전체 해제", "Deselect All") else L10n.t("전체 선택", "Select All")
        moveShelfBtn.text = L10n.t("📁 이동 ($count)", "📁 Move ($count)")
        emailBtn.text = L10n.t("✉ 메일 ($count)", "✉ Email ($count)")
        deleteBtn.text = L10n.t("🗑 삭제 ($count)", "🗑 Delete ($count)")
        moveShelfBtn.alpha = if (count > 0) 1.0f else 0.5f
        emailBtn.alpha = if (count > 0) 1.0f else 0.5f
        deleteBtn.alpha = if (count > 0) 1.0f else 0.5f
        summaryText.text = if (isSelectMode) {
            if (L10n.isKorean) "${count}권 선택됨 (전체 ${items.size}권)"
            else "$count selected (of ${items.size})"
        } else {
            val selShelf = app.prefs.selectedShelf
            val shelfName = when (selShelf) {
                "" -> L10n.t("내 책장", "My Bookshelf")
                "__UNCLASSIFIED__" -> L10n.t("[미분류] 책장", "[Unclassified]")
                else -> "[$selShelf]"
            }
            val base = if (L10n.isKorean) "$shelfName ${items.size}권 (전체 ${allBooks.size}권)"
                       else "$shelfName: ${items.size} books (Total: ${allBooks.size})"
            val todayStr = if (app.prefs.todayPages > 0 || app.prefs.todayMinutes > 0) {
                if (L10n.isKorean) "  •  오늘 독서 ${app.prefs.todayMinutes}분 (${app.prefs.todayPages}p)"
                else "  •  Today: ${app.prefs.todayMinutes}m (${app.prefs.todayPages}p)"
            } else ""
            "$base$todayStr"
        }
    }

    private fun toggleBookSelection(path: String) {
        if (selectedPaths.contains(path)) {
            selectedPaths.remove(path)
        } else {
            selectedPaths.add(path)
        }
        updateSelectionUi()
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun deleteSelectedBooks() {
        val targets = items.filter { selectedPaths.contains(it.path) }
        if (targets.isEmpty()) {
            toast("삭제할 도서를 먼저 선택해 주세요.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle(L10n.t("선택 도서 삭제 (${targets.size}권)", "Delete Selected Books (${targets.size})"))
            .setMessage(L10n.t("선택한 ${targets.size}권의 도서를 책장에서 삭제하시겠습니까?\n\n(참고: 기기의 원본 저장소 파일은 안전하게 유지됩니다)", "Remove ${targets.size} selected books from library?\n\n(Note: Original storage files on device will remain safe)"))
            .setPositiveButton(L10n.t("하이라이트 보관 후 삭제", "Keep Highlights & Remove")) { _, _ ->
                val count = targets.size
                scope.launch {
                    withContext(Dispatchers.IO) {
                        for (b in targets) {
                            app.db.highlights().updateBookMeta(b.path, b.title, b.author)
                            app.db.books().delete(b.path)
                        }
                    }
                    exitSelectMode()
                    updateList()
                    toast(L10n.t("${count}권의 도서를 책장에서 삭제하고 하이라이트는 안전하게 보관했습니다.", "${count} books removed from library. Highlights archived."))
                }
            }
            .setNegativeButton(L10n.t("하이라이트도 함께 삭제", "Delete Highlights Too")) { _, _ ->
                val count = targets.size
                scope.launch {
                    withContext(Dispatchers.IO) {
                        for (b in targets) {
                            try {
                                app.db.highlights().deleteForBook(b.path)
                            } catch (_: Throwable) {}
                            app.db.books().delete(b.path)
                        }
                    }
                    exitSelectMode()
                    updateList()
                    toast(L10n.t("${count}권의 도서 및 하이라이트를 삭제했습니다.", "${count} books and highlights deleted."))
                }
            }
            .setNeutralButton(L10n.t("취소", "Cancel"), null)
            .create()
            .showWithOptimalWidth(this)
    }

    private fun emailSelectedBooks() {
        val targets = items.filter { selectedPaths.contains(it.path) }
        if (targets.isEmpty()) {
            toast("전송할 도서를 먼저 선택해 주세요.")
            return
        }
        showEmailBookDialog(targets)
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun prepareBookAttachmentUri(book: BookEntity): Uri? = try {
        val dir = ShareFileProvider.shareDir(this)
        val cleanPath = Containers.normalizePath(book.path)
        val ext = when {
            book.format.isNotBlank() -> book.format.lowercase(Locale.ROOT)
            cleanPath.contains(".") -> cleanPath.substringAfterLast('.').lowercase(Locale.ROOT)
            else -> "epub"
        }
        val safeBase = "${book.title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(24).ifBlank { "book" }}_${Math.abs(book.path.hashCode()) % 10000}"
        val targetFile = File(dir, "$safeBase.$ext")

        if (cleanPath.startsWith("content://")) {
            val uri = Uri.parse(cleanPath)
            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            val realPath = if (cleanPath.startsWith("file://")) cleanPath.removePrefix("file://") else cleanPath
            val src = File(realPath)
            if (src.exists()) {
                src.copyTo(targetFile, overwrite = true)
            }
        }
        if (targetFile.exists() && targetFile.length() > 0L) {
            ShareFileProvider.uriForFile(this, targetFile)
        } else null
    } catch (_: Throwable) {
        null
    }

    private fun showEmailBookDialog(booksToSend: List<BookEntity>) {
        if (booksToSend.isEmpty()) {
            toast("전송할 도서를 선택해 주세요")
            return
        }

        val isLcd = (application as App).isLcdMode(strategy)
        val dialogRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val outerScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            enableVisibleScrollbar(isDark)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(10), dp(8))
        }
        outerScroll.addView(root)
        dialogRoot.addView(outerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 1. Book Card
        val bookCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        if (booksToSend.size == 1) {
            val singleBook = booksToSend.first()
            val coverBmp: Bitmap = singleBook.coverPath?.let { path ->
                thumbCache.get(path) ?: decodeSampledBitmap(path, dp(58), dp(82))?.also { b -> thumbCache.put(path, b) }
            } ?: thumbCache.get("gen_${singleBook.path}") ?: renderDefaultBookCover(
                dp(58), dp(82), singleBook.title, singleBook.author, isDark
            ).also { thumbCache.put("gen_${singleBook.path}", it) }

            val coverIv = ImageView(this).apply {
                setImageBitmap(coverBmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = cardDrawable(Color.WHITE, 4, borderCol)
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            bookCard.addView(coverIv, LinearLayout.LayoutParams(dp(54), dp(76)).apply { rightMargin = dp(12) })

            val metaCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val metaBadgeTv = TextView(this).apply {
                text = "📚 함께 전송될 전자책 파일"
                setPtSize(7.5f)
                setTextColor(ReaderTheme.ACCENT_PRIMARY_BLUE)
                typeface = ReaderTheme.uiFontBold
            }
            val metaTitleTv = TextView(this).apply {
                text = singleBook.title
                setPtSize(9.2f)
                setTextColor(inkCol)
                typeface = ReaderTheme.uiFontBold
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, dp(1))
            }
            val metaAuthorTv = TextView(this).apply {
                val authorStr = if (singleBook.author.isNotBlank()) "${singleBook.author}  •  " else ""
                val sizeStr = if (singleBook.sizeBytes > 0L) "  •  ${formatFileSize(singleBook.sizeBytes)}" else ""
                val pct = (singleBook.progress * 100).toInt()
                text = "${authorStr}${singleBook.format}$sizeStr (${pct}%)"
                setPtSize(7.8f)
                setTextColor(mutedCol)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            metaCol.addView(metaBadgeTv)
            metaCol.addView(metaTitleTv)
            metaCol.addView(metaAuthorTv)
            bookCard.addView(metaCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            val countTv = TextView(this).apply {
                text = "📚\n${booksToSend.size}권"
                gravity = Gravity.CENTER
                setPtSize(9f)
                setTextColor(ReaderTheme.ACCENT_PRIMARY_BLUE)
                typeface = ReaderTheme.uiFontBold
                background = cardDrawable(bgCol, 6, borderCol)
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            bookCard.addView(countTv, LinearLayout.LayoutParams(dp(54), dp(76)).apply { rightMargin = dp(12) })

            val metaCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val metaBadgeTv = TextView(this).apply {
                text = "📚 함께 전송될 전자책 파일 목록 (${booksToSend.size}권)"
                setPtSize(7.5f)
                setTextColor(ReaderTheme.ACCENT_PRIMARY_BLUE)
                typeface = ReaderTheme.uiFontBold
            }
            val totalBytes = booksToSend.sumOf { it.sizeBytes }
            val metaSummaryTv = TextView(this).apply {
                val sizeStr = if (totalBytes > 0L) " (총 ${formatFileSize(totalBytes)})" else ""
                text = "총 ${booksToSend.size}권의 전자책 파일$sizeStr"
                setPtSize(9f)
                setTextColor(inkCol)
                typeface = ReaderTheme.uiFontBold
                setPadding(0, dp(2), 0, dp(2))
            }
            val previewTitlesTv = TextView(this).apply {
                text = booksToSend.take(3).joinToString("\n") { "• ${it.title} [${it.format}]" } +
                    if (booksToSend.size > 3) "\n• 외 ${booksToSend.size - 3}권..." else ""
                setPtSize(7.5f)
                setTextColor(mutedCol)
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            metaCol.addView(metaBadgeTv)
            metaCol.addView(metaSummaryTv)
            metaCol.addView(previewTitlesTv)
            bookCard.addView(metaCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        root.addView(bookCard, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        // 2. Email Address Input
        val emailLabel = TextView(this).apply {
            text = "받는 사람 이메일 주소"
            setPtSize(8.5f)
            setTextColor(inkCol)
            typeface = ReaderTheme.uiFontBold
            setPadding(0, 0, 0, dp(4))
        }
        val emailInput = EditText(this).apply {
            setText(app.prefs.shareEmail)
            hint = "example@email.com"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPtSize(9f)
            setTextColor(inkCol)
            setHintTextColor(mutedCol)
            background = cardDrawable(cardBgCol, 6, borderCol)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setSingleLine(true)
        }
        root.addView(emailLabel)
        root.addView(emailInput)

        // 3. Email Guide Note
        val emailGuideTv = TextView(this).apply {
            text = "※ [이메일 전송] 버튼을 누르면 기기의 이메일 앱(Gmail, 삼성 이메일 등)의 작성 화면으로 받는 사람, 도서 정보 및 전자책 원본 파일이 자동으로 첨부되어 바로 열립니다. 확인 후 '보내기'만 누르시면 즉시 발송됩니다."
            setPtSize(7.6f)
            setTextColor(mutedCol)
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(emailGuideTv)

        lateinit var dlg: AlertDialog
        val executeSend = {
            val email = emailInput.text.toString().trim()
            if (email.isBlank() || !email.contains("@")) {
                toast("올바른 이메일 주소를 입력해주세요")
            } else {
                app.prefs.shareEmail = email
                dlg.dismiss()

                scope.launch {
                    toast("도서 파일을 이메일 첨부용으로 준비 중입니다...")
                    val (attachmentUris, mimeType) = withContext(Dispatchers.IO) {
                        val uris = ArrayList<Uri>()
                        for (b in booksToSend) {
                            val u = prepareBookAttachmentUri(b)
                            if (u != null) uris.add(u)
                        }
                        val mime = if (uris.size == 1) {
                            when (booksToSend[0].format.uppercase(Locale.ROOT)) {
                                "EPUB" -> "application/epub+zip"
                                "PDF" -> "application/pdf"
                                "TXT" -> "text/plain"
                                "MD" -> "text/markdown"
                                "CBZ" -> "application/x-cbz"
                                "CBR" -> "application/x-cbr"
                                else -> "application/octet-stream"
                            }
                        } else {
                            "*/*"
                        }
                        Pair(uris, mime)
                    }

                    if (attachmentUris.isEmpty()) {
                        toast("도서 파일을 읽을 수 없어 첨부하지 못했습니다.")
                        return@launch
                    }

                    val subject = if (booksToSend.size == 1) {
                        val b = booksToSend[0]
                        val authorPart = if (b.author.isNotBlank()) " (${b.author})" else ""
                        "[Aether SAGA] 도서 파일 전달: ${b.title}$authorPart"
                    } else {
                        "[Aether SAGA] 도서 파일 전달: ${booksToSend[0].title} 외 ${booksToSend.size - 1}권"
                    }

                    val bodySb = StringBuilder()
                    bodySb.append("■ Aether SAGA 도서 파일 공유\n\n")
                    bodySb.append("[전송 도서 파일 목록]\n")
                    for ((idx, b) in booksToSend.withIndex()) {
                        val authorPart = if (b.author.isNotBlank()) " (${b.author})" else ""
                        val sizePart = if (b.sizeBytes > 0L) " - ${formatFileSize(b.sizeBytes)}" else ""
                        bodySb.append("${idx + 1}. ${b.title}${authorPart} [${b.format}${sizePart}]\n")
                    }
                    bodySb.append("\n— Aether SAGA E-Book Viewer에서 전송됨\n")
                    bodySb.append("※ 본 메일의 첨부파일을 기기에 저장하여 전자책 뷰어에서 읽으실 수 있습니다.\n")

                    val opened = AndroidShareHelper.openEmailAppWithFiles(
                        ctx = this@LibraryActivity,
                        toEmail = email,
                        subject = subject,
                        body = bodySb.toString(),
                        attachmentUris = attachmentUris,
                        mimeType = mimeType
                    )

                    if (opened) {
                        toast("📨 이메일 앱이 열렸습니다. 첨부파일 확인 후 '보내기'를 눌러주세요.")
                    } else {
                        AlertDialog.Builder(this@LibraryActivity)
                            .setTitle("이메일 앱 실행 실패")
                            .setMessage("기기에서 이메일 앱(Gmail, 삼성 이메일 등)을 열 수 없습니다.\n이메일 앱이 설치되어 있고 기본 계정이 설정되어 있는지 확인해주세요.")
                            .setPositiveButton("확인", null)
                            .create()
                            .showWithOptimalWidth(this@LibraryActivity)
                    }
                }
            }
        }

        val sendActionBtn = pillButton(
            "📨 이메일 전송하기",
            Color.WHITE,
            if (isLcd) ReaderTheme.ACCENT_PRIMARY_BLUE else ReaderTheme.ACCENT_CHARCOAL,
            if (isLcd) ReaderTheme.ACCENT_PRIMARY_BLUE else ReaderTheme.EINK_INK
        ) {
            executeSend()
        }.apply {
            setPtSize(9.5f)
            typeface = ReaderTheme.uiFontBold
            setPadding(dp(16), dp(10), dp(16), dp(10))
            if (!isLcd) {
                background = GradientDrawable().apply {
                    setColor(ReaderTheme.ACCENT_CHARCOAL)
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(2), ReaderTheme.EINK_INK)
                }
            }
        }
        val bottomSep = View(this).apply { setBackgroundColor(borderCol) }
        dialogRoot.addView(bottomSep, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        dialogRoot.addView(sendActionBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dlg = AlertDialog.Builder(this)
            .setTitle(if (booksToSend.size == 1) L10n.t("이메일로 도서 파일 전송", "Send Book File via Email") else L10n.t("이메일로 선택 도서 ${booksToSend.size}권 전송", "Send ${booksToSend.size} Selected Books via Email"))
            .setView(dialogRoot)
            .create()
        val emailBookDlgH = (resources.displayMetrics.heightPixels * 0.72f).toInt().coerceAtLeast(dp(360))
        dlg.showWithOptimalWidth(this, customHeight = emailBookDlgH)
    }

    private fun settingsMenu() {
        val hw = app.hardware.spec
        val p = app.prefs
        val hwTier = when {
            hw.isLowSpecEink -> L10n.t("저전력 E-Ink 단말기", "Low-Power E-Ink Device")
            hw.isHighSpecEink -> L10n.t("고성능 E-Ink", "High-Performance E-Ink")
            else -> L10n.t("LCD/OLED 태블릿", "LCD/OLED Tablet")
        }

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            enableVisibleScrollbar(isDark)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        scroll.addView(box)
        lateinit var dlg: AlertDialog

        val isLcd = (application as App).isLcdMode(strategy)
        val cardBg = if (isDark) ReaderTheme.DARK_CARD else (if (isLcd) cardBgCol else ReaderTheme.EINK_CARD_BG)
        val cardBorder = if (isDark) ReaderTheme.DARK_BORDER else (if (isLcd) borderCol else ReaderTheme.EINK_BORDER_CONTRAST)
        val btnBg = ReaderTheme.einkBtnBg(isLcd, isDark)
        val btnBorder = ReaderTheme.einkBtnBorder(isLcd, isDark)
        val dividerColor = cardBorder
        val strokePx = ReaderTheme.einkStrokePx(this, isLcd)

        fun secHeader(title: String) = TextView(this).apply {
            text = title
            setPtSize(8f)
            setTextColor(mutedCol)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(2), dp(8), dp(2), dp(4))
        }

        fun makeCard() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = cardDrawable(cardBg, 8, cardBorder, strokePx)
        }

        fun makeDivider(): View = if (isLcd) {
            View(this).apply {
                setBackgroundColor(dividerColor)
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
            }
        } else {
            DottedDividerView(this, dotColor = 0xFF888888.toInt(), strokePx = dp(1)).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(3)).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
            }
        }

        // 0. 언어 설정 (Language Settings)
        box.addView(secHeader(L10n.t("언어 설정 (Language)", "Language Settings")))
        val langCard = makeCard()
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val langCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val langTitle = TextView(this).apply {
            text = L10n.t("앱 표시 언어", "App Language")
            setPtSize(9f)
            setTextColor(inkCol)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val curLangDisplay = if (L10n.isKorean) "한국어 (Korean)" else "English (영어)"
        val langDesc = TextView(this).apply {
            text = L10n.t("현재 언어: $curLangDisplay", "Current: $curLangDisplay")
            setPtSize(7.5f)
            setTextColor(mutedCol)
        }
        langCol.addView(langTitle)
        langCol.addView(langDesc)

        val langBtn = pillButton(curLangDisplay, inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            val languages = arrayOf("한국어 (Korean)", "English (영어)")
            val langCodes = arrayOf("ko", "en")
            val currentIdx = if (L10n.isKorean) 0 else 1
            AlertDialog.Builder(this)
                .setTitle(L10n.t("언어 선택 (Select Language)", "Select Language"))
                .setSingleChoiceItems(languages, currentIdx) { d, which ->
                    d.dismiss()
                    val selectedCode = langCodes[which]
                    p.language = selectedCode
                    L10n.setLanguage(this@LibraryActivity, selectedCode, p)
                    recreate()
                }
                .setNegativeButton(L10n.t("취소", "Cancel"), null)
                .create()
                .showWithOptimalWidth(this, onBack = { settingsMenu() })
        }
        langRow.addView(langCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        langRow.addView(langBtn)
        langCard.addView(langRow)
        box.addView(langCard)

        // 1. 기기 및 디스플레이 최적화 카드
        box.addView(secHeader(L10n.t("디스플레이 및 테마 ($hwTier • RAM ${hw.totalRamMb}MB)", "Display & Theme ($hwTier • RAM ${hw.totalRamMb}MB)")))
        val dispCard = makeCard()

        // Device & Resolution Profile Row
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val presetCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val presetTitle = TextView(this).apply {
            text = L10n.t("디스플레이 해상도 / 기기 프로필", "Display Resolution & Device Profile")
            setPtSize(9f)
            setTextColor(inkCol)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val currentKind = p.forcedKind
        val isAuto = currentKind.isEmpty()
        val presetName = when {
            isAuto -> L10n.t("자동 감지 (기기 맞춤 권장)", "Auto Detect (Recommended)")
            currentKind == DisplayKind.MONO_EINK.name -> L10n.t("흑백 E-Ink 모드", "Mono E-Ink Mode")
            currentKind == DisplayKind.COLOR_EINK.name -> L10n.t("컬러 E-Ink (Kaleido 3)", "Color E-Ink (Kaleido 3)")
            currentKind == DisplayKind.LCD_OLED.name -> L10n.t("LCD / OLED 태블릿", "LCD / OLED Tablet")
            else -> L10n.t("자동 감지 (기기 맞춤 권장)", "Auto Detect (Recommended)")
        }
        val presetDesc = TextView(this).apply {
            text = L10n.t("현재: $presetName", "Current: $presetName")
            setPtSize(7.5f)
            setTextColor(mutedCol)
        }
        presetCol.addView(presetTitle)
        presetCol.addView(presetDesc)
        val presetBtn = pillButton(L10n.t("변경", "Change"), inkCol, btnBg, btnBorder) {
            data class ModeOption(val id: String, val forcedKind: String, val preset: String, val title: String)
            val modes = listOf(
                ModeOption("AUTO", "", "AUTO", L10n.t("자동 감지 (기기 환경에 맞춤 - 권장)", "Auto Detect (Recommended for Device)")),
                ModeOption("MONO_EINK", DisplayKind.MONO_EINK.name, "MONO_EINK", L10n.t("흑백 E-Ink 모드 강제 적용", "Force Mono E-Ink Mode")),
                ModeOption("COLOR_EINK", DisplayKind.COLOR_EINK.name, "COLOR_EINK", L10n.t("컬러 E-Ink 모드 강제 적용 (Kaleido 3)", "Force Color E-Ink (Kaleido 3)")),
                ModeOption("LCD_OLED", DisplayKind.LCD_OLED.name, "GENERIC_TABLET", L10n.t("일반 LCD / OLED 모드 강제 적용 (컬러)", "Force Standard LCD / OLED (Color)"))
            )
            val labels = modes.map { m ->
                val isSelected = if (m.id == "AUTO") isAuto else (currentKind == m.forcedKind)
                if (isSelected) "✓  ${m.title}" else "     ${m.title}"
            }
            AlertDialog.Builder(this)
                .setTitle(L10n.t("디스플레이 및 화면 출력 모드", "Display & Screen Output Mode"))
                .setItems(labels.toTypedArray()) { _, idx ->
                    val chosen = modes[idx]
                    p.forcedKind = chosen.forcedKind
                    p.displayDevicePreset = chosen.preset
                    dlg.dismiss()
                    recreate()
                }
                .create()
                .showWithOptimalWidth(this, onBack = { settingsMenu() })
        }
        presetRow.addView(presetCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        presetRow.addView(presetBtn)
        dispCard.addView(presetRow)
        dispCard.addView(makeDivider())

        val modeTitle = TextView(this).apply {
            text = L10n.t("화면 출력 모드", "Screen Output Mode")
            setPtSize(9f)
            setTextColor(inkCol)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        dispCard.addView(modeTitle)

        val modeRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val modeRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        fun modeButton(label: String, kindVal: String): Button {
            val active = (kindVal.isEmpty() && isAuto) || (kindVal.isNotEmpty() && p.forcedKind == kindVal)
            val displayLabel = if (active) "✓ $label" else label
            return pillButton(
                displayLabel,
                if (active) Color.WHITE else inkCol,
                if (active) ReaderTheme.ACCENT_CHARCOAL else btnBg,
                if (active) ReaderTheme.ACCENT_CHARCOAL else btnBorder
            ) {
                p.forcedKind = kindVal
                p.displayDevicePreset = if (kindVal.isEmpty()) "AUTO" else kindVal
                dlg.dismiss()
                recreate()
            }
        }
        modeRow1.addView(modeButton(L10n.t("자동 감지", "Auto Detect"), ""), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        modeRow1.addView(modeButton(L10n.t("흑백 E-Ink", "Mono E-Ink"), DisplayKind.MONO_EINK.name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modeRow2.addView(modeButton(L10n.t("컬러 E-Ink", "Color E-Ink"), DisplayKind.COLOR_EINK.name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        modeRow2.addView(modeButton(L10n.t("LCD / 태블릿", "LCD / Tablet"), DisplayKind.LCD_OLED.name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dispCard.addView(modeRow1)
        dispCard.addView(modeRow2)
        dispCard.addView(makeDivider())

        val isLcdCapable = isLcd
        if (isLcdCapable) {
            val themeTitle = TextView(this).apply {
                text = L10n.t("🎨 독서 테마 및 색상 모드 (LCD/OLED)", "🎨 Reading Theme & Color Mode (LCD/OLED)")
                setPtSize(9f)
                setTextColor(inkCol)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(6))
            }
            dispCard.addView(themeTitle)

            val themeRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val themeRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(4))
            }

            fun themeBtn(label: String, themeKey: String): Button {
                val active = (p.readingTheme == themeKey)
                val displayLabel = if (active) "✓ $label" else "   $label"
                return pillButton(
                    displayLabel,
                    if (active) Color.WHITE else inkCol,
                    if (active) ReaderTheme.ACCENT_CHARCOAL else bgCol,
                    borderCol
                ) {
                    p.readingTheme = themeKey
                    p.dark = (themeKey == "DARK" || themeKey == "OLED_BLACK")
                    dlg.dismiss()
                    recreate()
                }
            }

            themeRow1.addView(themeBtn(L10n.t("📄 기본 화이트", "📄 Default White"), "WHITE"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            themeRow1.addView(themeBtn(L10n.t("📜 눈 보호 웜톤", "📜 Warm Sepia"), "SEPIA"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            themeRow2.addView(themeBtn(L10n.t("🌙 야간 다크", "🌙 Night Dark"), "DARK"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            themeRow2.addView(themeBtn(L10n.t("⚫ OLED 블랙", "⚫ OLED Black"), "OLED_BLACK"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            dispCard.addView(themeRow1)
            dispCard.addView(themeRow2)
            dispCard.addView(makeDivider())
        }

        // Row 3: 탐색기 플로팅 뒤로가기 버튼 사용자 선택 토글
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val navCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val navTv = TextView(this).apply {
            text = L10n.t("탐색기 플로팅 뒤로가기 버튼", "Picker Floating Back Button")
            setPtSize(9f)
            setTextColor(inkCol)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val navSubTv = TextView(this).apply {
            text = L10n.t("시스템 탐색기 위에 [◀ 뒤로] [✕ 닫기] 버튼 표시 (제스처가 불편할 때 유용)", "Show [◀ Back] [✕ Close] floating buttons on system picker")
            setPtSize(7.5f)
            setTextColor(mutedCol)
        }
        navCol.addView(navTv)
        navCol.addView(navSubTv)
        lateinit var navSw: ToggleSwitchView
        navSw = toggleSwitch(p.floatingPickerNavEnabled) {
            p.floatingPickerNavEnabled = !p.floatingPickerNavEnabled
            navSw.isChecked = p.floatingPickerNavEnabled
            if (p.floatingPickerNavEnabled && !NavAssistService.isEnabled(this@LibraryActivity)) {
                AlertDialog.Builder(this@LibraryActivity)
                    .setTitle(L10n.t("접근성 서비스 권한 안내", "Accessibility Permission"))
                    .setMessage(
                        L10n.t(
                            "시스템 파일 탐색기 화면 위에 버튼을 띄우려면 접근성 설정에서 'Aether SAGA 뒤로가기 버튼'을 켜주셔야 합니다.\n\n접근성 설정을 지금 여시겠습니까?",
                            "To display floating buttons over the system file picker, please enable 'Aether SAGA Back Button' in Accessibility Settings.\n\nOpen Accessibility Settings now?"
                        )
                    )
                    .setPositiveButton(L10n.t("설정 열기", "Open Settings")) { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: Throwable) {}
                    }
                    .setNegativeButton(L10n.t("나중에", "Later"), null)
                    .show()
            }
        }
        navRow.addView(navCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        navRow.addView(navSw)
        dispCard.addView(navRow)
        box.addView(dispCard)

        // 2. 뷰어 기본 조판 및 사용자 폰트 카드
        box.addView(secHeader(L10n.t("뷰어 조판 및 폰트 관리", "Viewer Typography & Fonts")))
        val fontCard = makeCard()
        val curFontName = when (p.fontPath) {
            "" -> L10n.t("기본 글꼴", "Default Font")
            "SYSTEM_SERIF" -> L10n.t("시스템 명조", "System Serif")
            "SYSTEM_SANS" -> L10n.t("시스템 고딕", "System Sans")
            else -> File(p.fontPath).nameWithoutExtension
        }
        val styleSummary = TextView(this).apply {
            text = if (L10n.isKorean) "현재 설정: ${p.fontSizePt}pt · 줄간격 ${p.lineHeight}배 · 문단 ${p.paraSpacingDp}dp · 여백 ${p.marginDp}dp · 굵기 ${p.boldness}단계 ($curFontName)"
                   else "Current: ${p.fontSizePt}pt · Line ${p.lineHeight}x · Para ${p.paraSpacingDp}dp · Margin ${p.marginDp}dp · Boldness ${p.boldness} ($curFontName)"
            setPtSize(8f)
            setTextColor(mutedCol)
            setPadding(0, 0, 0, dp(8))
        }
        fontCard.addView(styleSummary)

        val fontActionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val addFontBtn = pillButton(L10n.t("+ 폰트 추가 (.ttf/.otf)", "+ Add Font (.ttf/.otf)"), Color.WHITE, ReaderTheme.ACCENT_CHARCOAL) {
            dlg.dismiss()
            showFontPicker()
        }
        val resetStyleBtn = pillButton(L10n.t("↺ 기본값 초기화", "↺ Reset Defaults"), inkCol, btnBg, btnBorder) {
            p.applyHardwareDefaults(hw)
            Toast.makeText(this, L10n.t("기본 뷰어 설정으로 복원되었습니다.", "Restored to default viewer settings."), Toast.LENGTH_SHORT).show()
            dlg.dismiss()
            recreate()
        }
        fontActionRow.addView(addFontBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply { rightMargin = dp(6) })
        fontActionRow.addView(resetStyleBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fontCard.addView(fontActionRow)
        fontCard.addView(makeDivider())

        val docRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val docTv = TextView(this).apply {
            text = L10n.t("도서 파일 원본 스타일 우선 따르기", "Respect Original Book Styles")
            setPtSize(9f)
            setTextColor(inkCol)
        }
        lateinit var docSw: ToggleSwitchView
        docSw = toggleSwitch(p.useDocumentDefaults) {
            p.useDocumentDefaults = !p.useDocumentDefaults
            docSw.isChecked = p.useDocumentDefaults
        }
        docRow.addView(docTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        docRow.addView(docSw)
        fontCard.addView(docRow)
        box.addView(fontCard)

        // 2-2. Gemini AI 독서 어시스턴트 설정 카드 (Only on LCD/OLED devices)
        if (isLcd) {
            box.addView(secHeader(L10n.t("✨ Gemini AI 독서 어시스턴트 (Beta)", "✨ Gemini AI Reading Assistant (Beta)")))
            val geminiCard = makeCard()

            val geminiToggleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val geminiCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val geminiTitleTv = TextView(this).apply {
                text = L10n.t("Gemini AI 독서 도우미 사용", "Enable Gemini AI Assistant")
                setPtSize(9f)
                setTextColor(inkCol)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            val geminiDescTv = TextView(this).apply {
                text = L10n.t("문장 선택 및 하이라이트 탭 시 AI 심층 해석 & 1:1 토론 활성화", "Enable AI in-depth analysis & 1:1 discussion on text selection")
                setPtSize(7.5f)
                setTextColor(mutedCol)
            }
            geminiCol.addView(geminiTitleTv)
            geminiCol.addView(geminiDescTv)

            lateinit var geminiSw: ToggleSwitchView
            geminiSw = toggleSwitch(p.geminiAiEnabled) {
                p.geminiAiEnabled = !p.geminiAiEnabled
                geminiSw.isChecked = p.geminiAiEnabled
                Toast.makeText(this, if (p.geminiAiEnabled) L10n.t("Gemini AI (Beta)가 활성화되었습니다.", "Gemini AI (Beta) enabled.") else L10n.t("Gemini AI (Beta)가 비활성화되었습니다.", "Gemini AI (Beta) disabled."), Toast.LENGTH_SHORT).show()
            }
            geminiToggleRow.addView(geminiCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            geminiToggleRow.addView(geminiSw)
            geminiCard.addView(geminiToggleRow)
            geminiCard.addView(makeDivider())

            // Gemini API Key row
            val apiKeyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val apiCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val apiTitleTv = TextView(this).apply {
                text = L10n.t("🔑 Gemini API Key (Beta 인앱 1:1 실시간 대화)", "🔑 Gemini API Key (Beta In-App Chat)")
                setPtSize(8.5f)
                setTextColor(inkCol)
            }
            val keyStatus = if (p.geminiApiKey.isNotBlank()) {
                L10n.t("✅ 무료 API Key 등록됨 (인앱 1:1 실시간 대화 모드)", "✅ API Key registered (In-App Chat active)")
            } else {
                L10n.t("ℹ️ 미등록 (외부 Gemini 앱 자동 연결 모드 동작)", "ℹ️ Not set (External App Mode active)")
            }
            val apiDescTv = TextView(this).apply {
                text = keyStatus
                setPtSize(7.5f)
                setTextColor(if (p.geminiApiKey.isNotBlank()) 0xFF16A34A.toInt() else mutedCol)
            }
            apiCol.addView(apiTitleTv)
            apiCol.addView(apiDescTv)

            val setKeyBtn = pillButton(L10n.t("키 설정", "Set Key"), inkCol, btnBg, btnBorder) {
                dlg.dismiss()
                inputDialog(
                    title = L10n.t("Google Gemini API Key 입력", "Enter Gemini API Key"),
                    initial = p.geminiApiKey
                ) { newKey ->
                    p.geminiApiKey = newKey.trim()
                    Toast.makeText(
                        this,
                        if (p.geminiApiKey.isNotBlank()) L10n.t("Gemini API Key가 저장되었습니다. (인앱 대화 활성화)", "Gemini API Key saved. (In-App Chat Active)")
                        else L10n.t("API Key가 비워졌습니다. (외부 앱 모드)", "API Key cleared. (External App Mode)"),
                        Toast.LENGTH_SHORT
                    ).show()
                    settingsMenu()
                }
            }
            apiKeyRow.addView(apiCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            apiKeyRow.addView(setKeyBtn)
            geminiCard.addView(apiKeyRow)

            box.addView(geminiCard)
        }

        // 3. 백업 및 서재 관리 카드
        box.addView(secHeader(L10n.t("데이터 백업 및 서재 관리", "Data Backup & Library Management")))
        val dataCard = makeCard()
        val backupRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val backupBtn = pillButton(L10n.t("📤 설정/기록 백업 (.json)", "📤 Backup Settings (.json)"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            scope.launch {
                val f = backupManager.createBackup()
                Toast.makeText(this@LibraryActivity, L10n.t("백업 완료:\n${f.path}", "Backup complete:\n${f.path}"), Toast.LENGTH_LONG).show()
            }
        }
        val restoreBtn = pillButton(L10n.t("📥 백업 복원", "📥 Restore Backup"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            val docs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AetherSAGA")
            val legacyDocs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AetherReader")
            val list = (docs.listFiles { f -> f.extension.lowercase() == "json" }?.toList().orEmpty() +
                        legacyDocs.listFiles { f -> f.extension.lowercase() == "json" }?.toList().orEmpty()).distinctBy { it.name }
            if (list.isEmpty()) {
                Toast.makeText(this@LibraryActivity, L10n.t("Documents/AetherSAGA에 백업 파일이 없습니다.", "No backup files found in Documents/AetherSAGA."), Toast.LENGTH_SHORT).show()
            } else {
                listDialog(L10n.t("복원할 백업 파일 선택", "Select Backup File to Restore"), list.map { it.name }) { idx ->
                    scope.launch {
                        val ok = backupManager.restoreBackup(list[idx])
                        Toast.makeText(this@LibraryActivity, if (ok) L10n.t("복원 성공! 책장을 갱신합니다.", "Restore successful! Updating library.") else L10n.t("복원 실패", "Restore failed"), Toast.LENGTH_SHORT).show()
                        if (ok) recreate()
                    }
                }
            }
        }
        backupRow.addView(backupBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        backupRow.addView(restoreBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dataCard.addView(backupRow)
        dataCard.addView(makeDivider())

        val viewAllHlBtn = pillButton(L10n.t("🖍 하이라이트 및 북마크 열람/관리", "🖍 Browse Highlights & Bookmarks"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            showMainHighlightsDialog()
        }
        val mainShareNotesBtn = pillButton(L10n.t("📢 도서별 독서 노트 공유 (이메일 · SNS)", "📢 Share Book Reading Notes"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            showMainNotesShareDialog()
        }
        val mainSnsCardBtn = pillButton(L10n.t("📷 도서별 독서 카드 저장 · SNS 공유", "📷 Save Book Reading Card & Share"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            showMainSnsShareDialog()
        }
        val exportAllHlBtn = pillButton(L10n.t("📦 보관된 전체 하이라이트 압축파일(ZIP) 이메일 전송", "📦 Email All Saved Highlights (ZIP)"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            scope.launch {
                val allHl = withContext(Dispatchers.IO) { app.db.highlights().all() }
                if (allHl.isEmpty()) {
                    toast(L10n.t("보관된 하이라이트나 북마크가 없습니다.", "No saved highlights or bookmarks found."))
                    return@launch
                }
                showEmailHighlightsZipDialog(app, L10n.t("AetherSAGA 전체 도서", "AetherSAGA All Books"), L10n.t("통합 독서 보관함", "Unified Highlights Archive"), allHl, onBack = { settingsMenu() })
            }
        }
        dataCard.addView(viewAllHlBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        dataCard.addView(makeDivider())
        dataCard.addView(mainShareNotesBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        dataCard.addView(makeDivider())
        dataCard.addView(mainSnsCardBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        dataCard.addView(makeDivider())
        dataCard.addView(exportAllHlBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        dataCard.addView(makeDivider())

        val clearBtn = pillButton(L10n.t("🗑 책장 목록 전체 비우기 (원본 파일 유지)", "🗑 Clear Entire Library (Keep Files)"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            AlertDialog.Builder(this)
                .setTitle(L10n.t("책장 비우기", "Clear Library"))
                .setMessage(L10n.t("책장에 추가된 모든 도서 목록을 비우시겠습니까? (실제 원본 파일은 삭제되지 않습니다)", "Are you sure you want to clear all books from the library? (Original files will remain safe)"))
                .setPositiveButton(L10n.t("비우기", "Clear")) { _, _ ->
                    scope.launch(Dispatchers.IO) {
                        for (pathItem in app.db.books().allPaths()) app.db.books().delete(pathItem)
                    }
                }
                .setNegativeButton(L10n.t("취소", "Cancel"), null)
                .create()
                .showWithOptimalWidth(this, onBack = { settingsMenu() })
        }
        dataCard.addView(clearBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(dataCard)

        // 4. 독서 통계 및 활동 분석 카드
        box.addView(secHeader(L10n.t("독서 통계 및 활동 분석", "Reading Stats & Activity Analysis")))
        val statsCard = makeCard()
        val openStatsBtn = pillButton(L10n.t("📊 주간/월간/연간 독서 통계 대시보드 열기", "📊 Open Reading Stats Dashboard"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            val isEink = strategy.kind != DisplayKind.LCD_OLED
            StatsDashboardDialog.show(this@LibraryActivity, app, isEink, onBack = { settingsMenu() })
        }
        statsCard.addView(openStatsBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(statsCard)

        // 5. 앱 정보 및 릴리즈 노트 (설정 창 가장 아래 위치)
        box.addView(secHeader(L10n.t("버전 및 릴리즈 노트", "Version & Release Notes")))
        val releaseCard = makeCard()
        val guideBtn = pillButton(L10n.t("📖 공식 사용 설명서 열기 (User Guide)", "📖 Official User Guide"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            scope.launch {
                val guide = seedDefaultGuideBookIfNeeded() ?: allBooks.firstOrNull { it.path.contains("AetherSAGA_User_Guide") }
                if (guide != null) {
                    openBook(guide)
                }
            }
        }
        releaseCard.addView(guideBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        releaseCard.addView(makeDivider())
        val releaseBtn = pillButton(L10n.t("📋 릴리즈 노트 (Release Notes) • Ver. 2.6", "📋 Release Notes • Ver. 2.6"), inkCol, btnBg, btnBorder) {
            dlg.dismiss()
            showReleaseNotesDialog(onBack = { settingsMenu() })
        }
        releaseCard.addView(releaseBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(releaseCard)

        dlg = AlertDialog.Builder(this)
            .setTitle(L10n.t("⚙ 환경 설정", "⚙ Settings"))
            .setView(scroll)
            .create()
        dlg.showWithOptimalWidth(this, enforceBalancedMargins = true)
    }

    private fun showReleaseNotesDialog(onBack: (() -> Unit)? = null) {
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            enableVisibleScrollbar(isDark)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        scroll.addView(box)

        val verBadge = TextView(this).apply {
            text = "Aether SAGA  Ver. 2.6"
            setPtSize(10.5f)
            setTextColor(inkCol)
            typeface = Typeface.create("serif", Typeface.BOLD)
        }
        val releaseDate = TextView(this).apply {
            text = L10n.t("정식 릴리즈 • 2026. 11. 13", "Official Release • Nov 13, 2026")
            setPtSize(7.5f)
            setTextColor(mutedCol)
            setPadding(0, dp(1), 0, dp(8))
        }
        val divider = View(this).apply {
            setBackgroundColor(borderCol)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                bottomMargin = dp(10)
            }
        }
        box.addView(verBadge)
        box.addView(releaseDate)
        box.addView(divider)

        fun addSection(title: String, items: List<String>) {
            val h = TextView(this).apply {
                text = title
                setPtSize(8.5f)
                setTextColor(inkCol)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(3))
            }
            box.addView(h)
            for (item in items) {
                val t = TextView(this).apply {
                    text = "• $item"
                    setPtSize(7.5f)
                    setTextColor(inkCol)
                    setLineSpacing(dp(2).toFloat(), 1.15f)
                    setPadding(dp(4), dp(1), 0, dp(3))
                }
                box.addView(t)
            }
        }

        if (L10n.isKorean) {
            addSection("🚀 Ver. 2.6 e-ink AI 완전 비활성화, LCD/OLED 인앱 Gemini 원스톱 연동 & SNS 카드 발행", listOf(
                "e-ink 단말기 AI 기능 완전 비활성화: e-ink 기기에서는 리소스 낭비와 불필요한 점멸을 방지하기 위해 Gemini AI 메뉴를 완전히 은닉하고 100% 클래식 고대비 타이포그래피 모드로 즉각 동작",
                "LCD/OLED 인앱 Gemini 1.5 Flash REST API 직통 연동: 외부 브라우저나 외부 앱 이동 없이 앱 내부에서 직접 Gemini API와 통신하여 1:1 토론 및 심층 사유 제공",
                "모래시계(⏳) 작업 상태 인디케이터: AI 분석 및 사유 생성 중 '⏳ Gemini AI가 사유를 나누고 있습니다... ✨' 모래시계 다이얼로그로 명확한 동작 인지 지원",
                "1클릭 고해상도(1080p) SNS 토론 카드 합성: AI와 나눈 토론 결과를 미학적 정사각형 카드로 즉시 변환하여 인스타그램, 스레드, 카카오톡 등으로 1클릭 전송",
                "과거 읽은 책과의 대화(Serendipity Card) 고도화: 두 도서 간의 지적 연결 고리를 AI 통찰 배지와 함께 1:1 미학 카드로 합성",
                "공식 사용 설명서(EPUB) 전면 개정: 실제 앱 동작을 생생하게 보여주는 고해상도 UI 일러스트 다이어그램 전면 탑재"
            ))
            addSection("🚀 Ver. 2.5 폴더블·플립 반응형 조판, E-Ink vs LCD 이원화 비교표 & 21종 일러스트 가이드", listOf(
                "공식 사용 설명서 전 항목 21종 일러스트 다이어그램 탑재: 모든 장·항목마다 직관적인 인포그래픽 도표(총 21장)를 수록하고, E-Ink 단말기와 LCD/OLED 기기의 10대 기능 구분 상세 비교표를 추가",
                "갤럭시 Z 폴드 / 폴드 울트라 / Z 플립 완벽 대응: 메인 펼침 화면(폴드 2176×1812, 울트라 2184×1968, 플립 2640×1080) 및 플립 커버 플렉스 윈도우(748×720 / 512×260) 해상도 전용 프로파일 탑재",
                "폴딩↔언폴딩 실시간 무중단 반응형 조판: 기기를 접거나 펼 때 읽던 글자 오프셋을 100% 유지하며 즉시 본문·이미지·만화(CBZ/ZIP) 뷰포트를 재조판",
                "카카오톡(KakaoTalk) SNS 다이렉트 공유 복구 & 삼성 디스플레이 감지 정밀화: 갤러리 저장, 카카오톡, 인스타(1:1 피드 / 9:16 스토리), 스레드, X, 페이스북, 문자(SMS) 전 채널 완비",
                "Gemini AI 독서 어시스턴트 (Beta) 고도화: LCD/OLED 전용 3대 장르(문학·기술·역사) 자동 감지, 앱 이탈 없는 인앱 1:1 멀티턴 채팅 팝업, 메인 환경설정 및 독서 중 전체 리더 설정 첫 화면 On/Off 토글 지원"
            ))
            addSection("📖 Ver. 2.2 공식 사용 설명서(EPUB) 기본 탑재 & 독서 관리 고도화", listOf(
                "공식 사용 설명서 EPUB 기본 탑재: 앱 신규 설치 및 빈 서재 상태에서 원클릭으로 바로 열람할 수 있는 고품질 《Aether SAGA 사용 설명서》 기본 번들링 제공", 
                "서재 및 환경설정 연동: 서재 빈 화면 및 [환경설정 > 공식 사용 설명서 열기] 메뉴를 통해 언제든 설명서 열람 가능", 
                "도서 하이라이트 ZIP 백업 도서 메타데이터 통합: 선택한 도서 정보(《도서명》(저자))가 헤더 및 마크다운 파일에 자동 포함되어 여러 권 동시 내보내기 시 가독성 극대화", 
                "뷰어 내 하이라이트 문장 터치 시 [📢 SNS 공유] 바로가기 원클릭 팝업 액션 추가 (인스타 피드 1:1 및 스토리 9:16 오토핏)"
            ))
            addSection("🌟 Ver. 2.1 독서 노트 공유 통합, 모듈러 타이포 & 온디멘드 자가 복구", listOf(
                "도서 정보 및 독서 노트 공유 단일 통합 창: 상단에 '[✓] 도서 기본 정보 및 진행률 (진행률 XX% 달성)' 기본 제공으로 하이라이트 유무에 관계없이 모든 공유 시나리오 100% 지원",
                "온디멘드(On-Demand) 도서 위치 자동 재연결: 지정 폴더 내 하위 폴더로 책 이동 시, 서재 및 독서노트에서 클릭하는 즉시 신속 탐색하여 DB 경로 및 북마크 자동 자가 복구",
                "SNS 공유 카드 모듈러 타이포그래피 스케일 정렬: 슬롯 수에 비례한 일관된 태그 배지(26~30px) 및 따옴표(48~80px), 헤드라인과 인포그래픽 프로그레스 바 적용",
                "인스타그램 스토리 슬롯 글자수별 공간 비례 할당: 문장 길이에 맞추어 카드 높이를 유동 배분하고, 600자 이내 복수 단문 또는 장문 1개 자유 조합 지원",
                "동적 폰트 오토핏(Auto-Fit) 엔진: 긴 인용구도 말줄임표(...) 잘림 없이 폰트 크기 및 행간을 자동 최적화하여 원문 내용 100% 전체 수용",
                "도서 표지 원본 가로세로 비율(Aspect Ratio) 100% 보존: 스토리 및 피드 카드 우측 상단 표지 축소 시 왜곡 없이 원본 비율 유지",
                "독서노트 문장 선택 '전체 선택' 체크박스 양방향 동기화 및 중첩 스크롤 제스처 간섭 원천 해결, 고대비 상시 스크롤바 탑재"
            ))
            addSection("1. 극도의 초경량화 & Zero-Library 리더 엔진", listOf(
                "Jetpack Compose, Hilt, Glide, Retrofit 일체 배제한 순수 Framework View 구현",
                "약 640KB 초경량 설치 패키지(APK) 및 즉각적인 콜드 스타트 기동"
            ))
            addSection("2. E-Ink 및 LCD/OLED 하드웨어 최적화 이원화", listOf(
                "E-Ink: Carta 1300/1200 최적화, 10단계 엣지 스트로크 부스터, 잔상 제거 전체 리프레시",
                "E-Ink: 환경설정 및 전체 리더 설정·도구 메뉴 묶음과 버튼 외곽선 고대비(2px) 보강",
                "E-Ink: 글자 스타일, 조명, 보조도구 등 각 메뉴 항목 간 구분선 옅은 점선(Dotted) 적용",
                "E-Ink: 독서 보조도구 카드 박스 그룹화 (플래시카드 복습 및 통계 대시보드)",
                "E-Ink: 목차 도서 정보 아래 실선 및 챕터 간 옅은 점선(18dp 여백) 차별화",
                "E-Ink: 상시 노출 고대비 커스텀 수직 스크롤바(EinkScrollBarView)",
                "LCD/OLED: 4대 시력보호 독서 테마 (화이트, 눈 보호 세피아, 야간 차콜, OLED 블랙)",
                "LCD/OLED: 은은한 배경 딤(0.35f) 및 카드 그림자(Elevation)"
            ))
            addSection("3. 다양한 도서 및 만화 포맷 지원", listOf(
                "EPUB, TXT, PDF, MOBI, AZW3, 마크다운(MD) 텍스트 문서",
                "CBZ, CBR, ZIP 만화/코믹스 스트리밍 엔진 (자동 양면 분할, 콘트라스트 부스터)"
            ))
            addSection("4. 독서 집중 및 편의 보조도구", listOf(
                "독서 룰러 (Reading Ruler) 및 25분 집중 포모도로 타이머",
                "지수 평활 기반 완독 예상 시간 계산기 (Time-to-Finish Estimator)",
                "독서 화면 하단 스크러버 레이블 2줄 표시 (페이지 정보 & 완독 시간 분리)",
                "하단 상태바 푸터 폰트 크기 1PT 확대 적용",
                "10~120초 간격 핸즈프리 자동 책넘김 (Auto Page Turn)",
                "인라인 각주 팝업 (Footnote Resolver) 및 북마크 벡터 리본"
            ))
            addSection("5. 독서 기록 영구 보존 & 이메일 ZIP 내보내기", listOf(
                "인라인 하이라이트 독서 메모 및 3x3 터치(<850ms) vs 드래그(>=850ms) 분리",
                "도서 삭제 시 하이라이트 영구 보관 (도서 메타데이터 디커플링)",
                "보관된 전체 하이라이트 표준 TXT/MD 통합 ZIP 압축파일 생성 및 이메일 전송"
            ))
            addSection("6. 감성 독서 카드 저장 & SNS 공유 (Zero-SDK)", listOf(
                "독서 카드 갤러리 저장: 전 기기(E-Ink 및 LCD/OLED) 지원 (권한 없는 Scoped Storage Pictures/AetherSAGA 저장)",
                "카카오톡(KakaoTalk): 브랜드 아이콘 및 카드 이미지 다이렉트 전송 (LCD/OLED 기기 지원, E-Ink 기기는 은닉)",
                "문자 메시지(SMS): 셀룰러 통신 기기 감지 연동 (E-Ink 기기는 원천 은닉)",
                "글로벌 SNS: Instagram, Threads, X, Facebook 연동 (E-Ink 및 LCD/OLED 전 기기 지원)"
            ))
            addSection("7. 독서 통계 트래커 & 순수 Canvas 대시보드", listOf(
                "일일 독서 시간 및 페이지 측정 (서재 헤더 표출)",
                "Zero-Library 순수 캔버스 조판 라인 차트 대시보드 (주간/월간/연간)",
                "주간 읽은 도서 목록, 완독 뱃지, 연속 독서일(Streak) 기록",
                "도서 파일 삭제 후에도 월별/연간 완독 도서 기록 영구 보존"
            ))
            addSection("8. 글로벌 이중 언어(Bilingual) 지원", listOf(
                "한국어 및 영어 2대 언어 전용 지원",
                "시스템 로케일 자동 감지 (한국어 외 영어 기본 적용)",
                "설정에서 실시간 원클릭 언어 전환 및 영속화"
            ))
            addSection("9. 오프라인 무선 파일 전송 & 동기화", listOf(
                "로컬 Wi-Fi 무선 책 전송 경량 HTTP 웹 서버 내장",
                "WebDAV 원격 독서 위치 동기화 및 설정 JSON 백업/복원"
            ))
        } else {
            addSection("🚀 Ver. 2.6 E-Ink AI Deactivation, In-App Gemini REST API & Instant SNS Card Dispatch", listOf(
                "Complete AI Deactivation on E-Ink: Completely hides Gemini AI menus on e-ink devices to prevent battery/resource drain and flicker, instantly rendering pure classic high-contrast typography cards.",
                "In-App Gemini 1.5 Flash REST API Integration: Direct in-app communication with Gemini API without opening external browsers or apps.",
                "Hourglass (⏳) Activity Status Indicator: Displays a clear hourglass loading dialog during AI analysis and synthesis.",
                "1-Click 1080p Social Discussion Card Synthesizer: Instantly synthesizes dialogues into aesthetic 1080x1080 cards for Instagram, Threads, and KakaoTalk.",
                "Cross-Book Serendipity Cards: Deep intellectual synergy bridges between contrasting books in your personal library.",
                "Official User Guide (EPUB) Overhaul: Enriched with realistic app operation UI visual mockups."
            ))
            addSection("🚀 Ver. 2.5 Foldable/Flip Responsive Layout, E-Ink vs LCD Matrix & 21-Diagram Guide", listOf(
                "21 High-Res Illustrated Diagrams Across All User Guide Sections: Every chapter & subsection now includes visual infographic diagrams plus a 10-point E-Ink vs. LCD/OLED Feature Comparison Matrix",
                "Galaxy Z Fold / Fold Ultra / Z Flip Native Support: Dedicated display profiles for unfolded main screens (Fold 2176×1812, Ultra 2184×1968, Flip 2640×1080) and Flip Cover Flex Windows (748×720 / 512×260)",
                "Seamless Fold↔Unfold Live Repagination: Preserves exact character offset while dynamically reflowing text, inline EPUB images, and CBZ/ZIP comic viewports on the fly",
                "KakaoTalk Direct SNS Sharing Restored & Refined Display Detection: Full sharing support across Gallery Save, KakaoTalk, Instagram (1:1 Feed / 9:16 Story), Threads, X, Facebook, and SMS",
                "Gemini AI Reading Assistant (Beta) Enhanced: Auto-detects 3 genres (Literature, Tech Tutor, History) on LCD/OLED devices, features an in-app 1:1 multi-turn chat popup, and provides On/Off toggles in both Main Settings and Reader Settings"
            ))
            addSection("📖 Ver. 2.2 Official User Guide (EPUB) Bundled & Enhanced Reading Notes", listOf(
                "Official User Guide EPUB Bundled: Built-in 'Aether SAGA Official User Guide' seamlessly seeded on fresh install and available from empty library or settings menu", 
                "Library & Settings Integration: One-click access to open or restore the user guide anytime", 
                "Highlight ZIP Export with Book Metadata: Auto-embeds book title & author into exported TXT/Markdown files for structured archival across multiple books", 
                "Quick SNS Share Action: Direct '[📢 SNS Share]' shortcut added to the highlight tap popup for instantaneous 1:1 and 9:16 card creation"
            ))
            addSection("🌟 Ver. 2.1 Unified Share, Modular Typography & On-Demand Self-Healing", listOf(
                "Unified Reading Notes & Progress Dialog: Default '[✓] Basic Info & Reading Progress' item covers all sharing flows seamlessly even with 0 highlights",
                "On-Demand Self-Healing Book Relinking: When book files are moved into subfolders, clicking them in the library or notes instantly locates the file and self-heals database paths",
                "Modular Typography Scale Alignment: Unified tag badges (26~30px) and quotation marks (48~80px) across all slots, paired with hero headlines & infographic progress bar",
                "Proportional Slot Height Allocation: Dynamically allocates Instagram Story card space by note length, supporting flexible multi-quote bundles up to 600 characters",
                "Dynamic Auto-Fit Font Scaling: Long quotes fit seamlessly without any '...' truncation by automatically scaling typography and line spacing",
                "Original Book Cover Aspect Ratio: Guarantees 100% distortion-free book cover proportions on Story and Feed cards",
                "Bi-directional Select-All Checkbox & Smooth Drag: Solved nested scroll gesture interception and added high-contrast persistent scrollbars"
            ))
            addSection("1. Ultra-Lightweight & Zero-Library Engine", listOf(
                "Pure Android Framework Views without Compose, Hilt, Glide, or Retrofit",
                "Ultra-compact ~640KB installation package with instantaneous cold start"
            ))
            addSection("2. Hardware Bifurcation for E-Ink & LCD/OLED", listOf(
                "E-Ink: Carta 1300/1200 tuning, 10-step Edge Stroke Booster, anti-ghosting full refresh",
                "E-Ink: High-contrast (2px) outlines for settings menu groups, tools, and pill buttons",
                "E-Ink: Subtle dotted divider lines between items in typography, tools, and settings menus",
                "E-Ink: Grouped extra tools card container (Flashcards review & Reading Stats dashboard)",
                "E-Ink: Solid line below book meta and soft dashed lines with 18dp margin between TOC chapters",
                "E-Ink: High-contrast persistent custom vertical scrollbar (EinkScrollBarView)",
                "LCD/OLED: 4 eye-care themes (White, Warm Sepia, Dark Charcoal, True Black)",
                "LCD/OLED: Soft background dim (0.35f) and elegant card elevations"
            ))
            addSection("3. Comprehensive Format Support", listOf(
                "EPUB, TXT, PDF, MOBI, AZW3, Markdown (MD) text formats",
                "CBZ, CBR, ZIP comic streaming engine (smart dual-page split, contrast boost)"
            ))
            addSection("4. Reading Focus & Assistive Tools", listOf(
                "Reading Ruler guide and 25-minute Pomodoro timer",
                "Exponential smoothing Time-to-Finish Estimator",
                "2-line scrubber label (pages info & remaining time separated)",
                "Footer status font size enlarged by 1PT for enhanced clarity",
                "Hands-free auto page turn (10s to 120s intervals)",
                "Inline footnote popover & bookmark vector ribbon indicator"
            ))
            addSection("5. Note Preservation & Email ZIP Export", listOf(
                "Inline highlight memo editor & 3x3 touch (<850ms) vs drag (>=850ms) separation",
                "Permanent highlight preservation on book removal (metadata decoupling)",
                "All highlights packaged into UTF-8 TXT/MD ZIP archive for email export"
            ))
            addSection("6. Reading Card Gallery Save & Social Sharing", listOf(
                "Gallery Save: Supported on ALL devices (E-Ink & LCD/OLED) via Scoped Storage Pictures/AetherSAGA without extra permissions",
                "KakaoTalk: Direct card sharing with official brand icons (LCD/OLED only, hidden on E-Ink)",
                "SMS / MMS: Cellular telephony detection (completely hidden on E-Ink)",
                "Global Social Media: Instagram, Threads, X, Facebook (Supported across all devices)"
            ))
            addSection("7. Reading Stats & Pure Canvas Dashboard", listOf(
                "Daily reading time and page tracking in library header",
                "Zero-library pure Canvas line chart dashboard (Weekly / Monthly / Yearly)",
                "Weekly reading book list, finished badge, and reading streaks",
                "Permanent completed book history across months and years"
            ))
            addSection("8. Bilingual Localization (Korean & English)", listOf(
                "Dedicated Korean and English dual language support",
                "System locale auto-detection (defaults to English for non-Korean devices)",
                "Real-time language switching in Settings with persistence"
            ))
            addSection("9. Wireless Transfer & Synchronization", listOf(
                "Embedded local Wi-Fi HTTP web server for wireless book upload",
                "WebDAV remote reading position sync and settings JSON backup/restore"
            ))
        }

        AlertDialog.Builder(this)
            .setTitle(L10n.t("📋 릴리즈 노트", "📋 Release Notes"))
            .setView(scroll)
            .setPositiveButton(L10n.t("닫기", "Close"), null)
            .create()
            .showWithOptimalWidth(this, onBack = onBack)
    }

    // Modern Modern Book Card Adapter
    private inner class BookGridAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val card = (cv as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(8))

                val coverFrame = RelativeLayout(context).apply {
                    id = View.generateViewId()
                }
                val iv = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    id = View.generateViewId()
                }
                coverFrame.addView(iv, RelativeLayout.LayoutParams(-1, dp(175)))

                // Format & Progress badge (top-right corner pill)
                val badgeTv = TextView(context).apply {
                    id = View.generateViewId()
                    textSize = 9.5f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background = GradientDrawable().apply {
                        setColor(0xCC111111.toInt())
                        cornerRadius = dp(4).toFloat()
                    }
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                }
                val badgeParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                coverFrame.addView(badgeTv, badgeParams)

                // Progress ribbon (Progress bottom bar overlay)
                val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    id = View.generateViewId()
                    max = 100
                    progressDrawable = GradientDrawable().apply {
                        setColor(ReaderTheme.ACCENT_PRIMARY_BLUE)
                        cornerRadius = dp(2).toFloat()
                    }
                    visibility = View.GONE
                }
                val pbParams = RelativeLayout.LayoutParams(-1, dp(4)).apply {
                    addRule(RelativeLayout.ALIGN_BOTTOM, iv.id)
                }
                coverFrame.addView(progressBar, pbParams)

                // Selection check badge (top-left corner circle)
                val checkBadge = TextView(context).apply {
                    id = View.generateViewId()
                    textSize = 12f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                }
                val checkParams = RelativeLayout.LayoutParams(dp(26), dp(26)).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                coverFrame.addView(checkBadge, checkParams)

                addView(coverFrame, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

                val titleView = TextView(context).apply {
                    id = View.generateViewId()
                    setTextColor(inkCol)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 13.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(0, dp(6), 0, dp(2))
                    setLineSpacing(0f, 1.15f)
                }
                addView(titleView)

                val authorView = TextView(context).apply {
                    id = View.generateViewId()
                    setTextColor(mutedCol)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 11.5f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                addView(authorView)
            }

            val coverFrame = card.getChildAt(0) as RelativeLayout
            val iv = coverFrame.getChildAt(0) as ImageView
            val badgeTv = coverFrame.getChildAt(1) as TextView
            val pb = coverFrame.getChildAt(2) as ProgressBar
            val checkBadge = coverFrame.getChildAt(3) as TextView
            val titleTv = card.getChildAt(1) as TextView
            val authorTv = card.getChildAt(2) as TextView

            val item = items[p]
            titleTv.text = item.title
            val pct = (item.progress * 100).toInt().coerceIn(0, 100)
            val itemShelf = cachedShelfMap[item.path].orEmpty()
            val baseAuthor = if (item.author.isNotBlank()) item.author else item.format
            authorTv.text = if (itemShelf.isNotBlank() && app.prefs.selectedShelf.isBlank()) {
                "$baseAuthor  •  📁 $itemShelf"
            } else baseAuthor
            badgeTv.visibility = View.VISIBLE
            badgeTv.text = if (pct > 0) "${item.format} • $pct%" else item.format

            if (pct > 0) {
                pb.progress = pct
                pb.visibility = View.VISIBLE
            } else {
                pb.visibility = View.GONE
            }

            val isSelected = selectedPaths.contains(item.path)
            if (isSelectMode) {
                checkBadge.visibility = View.VISIBLE
                if (isSelected) {
                    checkBadge.text = "✓"
                    checkBadge.setTextColor(Color.WHITE)
                    checkBadge.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ReaderTheme.ACCENT_CHARCOAL)
                        setStroke(dp(2), Color.WHITE)
                    }
                } else {
                    checkBadge.text = ""
                    checkBadge.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x88333333.toInt())
                        setStroke(dp(2), Color.WHITE)
                    }
                }
                if (isSelected) {
                    card.background = cardDrawable(if (isDark) 0x22FFFFFF.toInt() else 0x18000000.toInt(), 8, ReaderTheme.ACCENT_CHARCOAL)
                } else {
                    card.background = null
                }
            } else {
                checkBadge.visibility = View.GONE
                card.background = null
            }

            val coverPath = item.coverPath
            val cacheKey = coverPath ?: "gen_${item.path}"
            val cachedBmp = thumbCache.get(cacheKey)
            iv.tag = cacheKey

            if (cachedBmp != null) {
                iv.setImageBitmap(cachedBmp)
            } else {
                val defaultCover = thumbCache.get("gen_${item.path}") ?: renderDefaultBookCover(
                    dp(120), dp(175), item.title, item.author, isDark
                ).also { thumbCache.put("gen_${item.path}", it) }
                iv.setImageBitmap(defaultCover)

                if (coverPath != null) {
                    val reqW = dp(120)
                    val reqH = dp(175)
                    scope.launch(Dispatchers.IO) {
                        val decoded = decodeSampledBitmap(coverPath, reqW, reqH)
                        if (decoded != null) {
                            thumbCache.put(coverPath, decoded)
                            withContext(Dispatchers.Main) {
                                if (iv.tag == coverPath) {
                                    iv.setImageBitmap(decoded)
                                }
                            }
                        }
                    }
                }
            }

            iv.background = cardDrawable(Color.TRANSPARENT, 4, if (isSelectMode && isSelected) ReaderTheme.ACCENT_CHARCOAL else borderCol)

            return card
        }
    }

    override fun onBackPressed() {
        if (isSelectMode) {
            exitSelectMode()
        } else if (searchQuery.isNotBlank()) {
            searchQuery = ""
            updateList()
        } else {
            super.onBackPressed()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * On-Demand Self-Healing Book Relinking:
     * If a book file was relocated into a subfolder within the designated library directory or its parent,
     * performs an instant on-demand search, atomically updates database paths for both the book
     * and all associated highlights/shelf assignments, and returns the newly resolved path.
     */
    private fun resolveOnDemandBookPath(currentPath: String, expectedSizeBytes: Long): String {
        val clean = Containers.normalizePath(currentPath)
        if (clean.startsWith("content://")) return clean
        val oldFile = File(clean)
        if (oldFile.exists()) return clean

        // File is missing! On-demand search strictly within designated/parent directories
        val candidateRoots = LinkedHashSet<File>()
        var pDir: File? = oldFile.parentFile
        while (pDir != null && pDir.path != "/" && pDir.path != "/storage" && pDir.path != "/storage/emulated") {
            if (pDir.exists() && pDir.isDirectory) {
                candidateRoots.add(pDir)
                if (pDir.name.equals("Books", ignoreCase = true) ||
                    pDir.name.equals("Download", ignoreCase = true) ||
                    pDir.name.equals("Documents", ignoreCase = true)) {
                    break
                }
            }
            pDir = pDir.parentFile
        }
        if (candidateRoots.isEmpty()) {
            val extDir = Environment.getExternalStorageDirectory()
            if (extDir != null && extDir.exists()) {
                val booksDir = File(extDir, "Books")
                if (booksDir.exists() && booksDir.isDirectory) candidateRoots.add(booksDir)
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dlDir != null && dlDir.exists() && dlDir.isDirectory) candidateRoots.add(dlDir)
                val docDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                if (docDir != null && docDir.exists() && docDir.isDirectory) candidateRoots.add(docDir)
            }
        }

        val targetName = oldFile.name
        for (root in candidateRoots) {
            val matched = findMatchingBookFile(root, targetName, expectedSizeBytes)
            if (matched != null) {
                val newPath = matched.absolutePath
                if (newPath != clean) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            app.db.books().updatePath(clean, newPath)
                            app.db.highlights().updateBookPath(clean, newPath)
                            val shelf = app.prefs.getBookShelf(clean)
                            if (shelf.isNotBlank()) {
                                app.prefs.setBookShelf(newPath, shelf)
                                app.prefs.setBookShelf(clean, "")
                            }
                        } catch (_: Throwable) {}
                    }
                    allBooks = allBooks.map { if (it.path == clean) it.copy(path = newPath) else it }
                    toast(L10n.t("도서 이동이 감지되어 새 위치로 자동 연결되었습니다.", "Book relocation detected; reconnected automatically."))
                    return newPath
                }
            }
        }
        return clean
    }

    private fun openBookAtHighlight(h: HighlightEntity) {
        val b = allBooks.find { it.path == h.bookPath || "file://${it.path}" == h.bookPath }
            ?: BookEntity(
                path = h.bookPath,
                title = h.bookTitle.ifBlank { File(h.bookPath).nameWithoutExtension },
                author = h.bookAuthor,
                format = Containers.formatOf(h.bookPath)?.name ?: "TXT",
                sizeBytes = 0L,
                addedAt = System.currentTimeMillis()
            )
        val resolved = resolveOnDemandBookPath(b.path, b.sizeBytes)
        val cleanPath = Containers.normalizePath(resolved)
        try {
            val intent = Intent(this, ReaderActivity::class.java).apply {
                putExtra("path", cleanPath)
                putExtra("jumpChapter", h.chapter)
                putExtra("jumpOffset", h.startChar)
                if (cleanPath.startsWith("content://")) {
                    data = Uri.parse(cleanPath)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(intent)
        } catch (t: Throwable) {
            toast(L10n.t("도서를 열 수 없습니다: ${t.message}", "Cannot open book: ${t.message}"))
        }
    }

    private fun showMainSnsShareDialog() {
        if (allBooks.isEmpty()) {
            toast(L10n.t("책장에 등록된 도서가 없습니다.", "No books found in library."))
            return
        }
        val isLcd = (application as App).isLcdMode(strategy)
        if (allBooks.size == 1) {
            val b = allBooks[0]
            scope.launch {
                val hl = withContext(Dispatchers.IO) {
                    val list = app.db.highlights().forBook(b.path)
                    if (list.isNotEmpty()) list else app.db.highlights().forBook("file://${b.path}")
                }
                val pct = (b.progress * 100).toInt()
                val sampleQuote = hl.firstOrNull { it.kind == 0 }?.quote ?: L10n.t("'${b.title}' 독서 진행률 $pct% 달성!", "'${b.title}' reading progress: $pct%!")
                val sampleMemo = hl.firstOrNull { it.kind == 0 && it.note.isNotBlank() }?.note
                withContext(Dispatchers.Main) {
                    val coverBmp = b.coverPath?.let { p -> if (java.io.File(p).exists()) android.graphics.BitmapFactory.decodeFile(p) else null }; AndroidShareHelper.showSnsShareDialog(this@LibraryActivity, b.title, b.author, sampleQuote, sampleMemo, isEink = !isLcd, coverBitmap = coverBmp)
                }
            }
            return
        }

        val bookOptions = allBooks.map { b ->
            val pct = (b.progress * 100).toInt()
            "${b.title} (${b.author.ifBlank { L10n.t("저자 미상", "Unknown") }})  •  $pct%"
        }
        listDialog(L10n.t("📷 독서 카드 공유할 도서 선택", "Select Book for SNS Card"), bookOptions) { idx ->
            val b = allBooks[idx]
            scope.launch {
                val hl = withContext(Dispatchers.IO) {
                    val list = app.db.highlights().forBook(b.path)
                    if (list.isNotEmpty()) list else app.db.highlights().forBook("file://${b.path}")
                }
                val pct = (b.progress * 100).toInt()
                val sampleQuote = hl.firstOrNull { it.kind == 0 }?.quote ?: L10n.t("'${b.title}' 독서 진행률 $pct% 달성!", "'${b.title}' reading progress: $pct%!")
                val sampleMemo = hl.firstOrNull { it.kind == 0 && it.note.isNotBlank() }?.note
                withContext(Dispatchers.Main) {
                    val coverBmp = b.coverPath?.let { p -> if (java.io.File(p).exists()) android.graphics.BitmapFactory.decodeFile(p) else null }; AndroidShareHelper.showSnsShareDialog(this@LibraryActivity, b.title, b.author, sampleQuote, sampleMemo, isEink = !isLcd, coverBitmap = coverBmp)
                }
            }
        }
    }

    private fun showMainNotesShareDialog() {
        if (allBooks.isEmpty()) {
            toast(L10n.t("책장에 등록된 도서가 없습니다.", "No books found in library."))
            return
        }
        scope.launch {
            val allHl = withContext(Dispatchers.IO) { app.db.highlights().all() }
            val hlCountByBook = allHl.groupingBy { it.bookPath }.eachCount()
            val isLcd = (application as App).isLcdMode(strategy)
            val cardBg = if (isDark) ReaderTheme.DARK_CARD else (if (isLcd) cardBgCol else ReaderTheme.EINK_CARD_BG)
            val cardBorder = if (isDark) ReaderTheme.DARK_BORDER else (if (isLcd) borderCol else ReaderTheme.EINK_BORDER_CONTRAST)
            val strokePx = ReaderTheme.einkStrokePx(this@LibraryActivity, isLcd)

            fun getHlCount(b: BookEntity): Int =
                hlCountByBook[b.path] ?: hlCountByBook["file://${b.path}"] ?: 0

            // 1. Sort: Books with notes/bookmarks, in progress, or completed come first
            val isActive = { b: BookEntity ->
                getHlCount(b) > 0 || b.progress > 0f || b.lastRead > 0L
            }
            val activeBooks = allBooks.filter(isActive).sortedWith(
                compareByDescending<BookEntity> { getHlCount(it) > 0 }
                    .thenByDescending { it.progress }
                    .thenByDescending { it.lastRead }
                    .thenBy { it.title }
            )
            val otherBooks = allBooks.filterNot(isActive).sortedBy { it.title }

            withContext(Dispatchers.Main) {
                val dm = resources.displayMetrics
                val isLandscape = dm.widthPixels > dm.heightPixels
                // Constrain dialog height to ~380-440dp so the list scrolls with a persistent, high-contrast scrollbar
                val targetDialogH = if (isLandscape) {
                    (dm.heightPixels * 0.80f).toInt().coerceAtMost(dp(420))
                } else {
                    (dm.heightPixels * 0.65f).toInt().coerceAtMost(dp(440))
                }

                val scroll = ScrollView(this@LibraryActivity).apply {
                    isFillViewport = true
                    enableVisibleScrollbar(isDark = isDark, widthDp = 7)
                }
                val contentCol = LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }
                scroll.addView(contentCol)

                lateinit var dlg: AlertDialog

                fun addBookRow(b: BookEntity, isUnreadGroup: Boolean) {
                    val count = getHlCount(b)
                    val pct = (b.progress * 100).toInt()

                    val row = LinearLayout(this@LibraryActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = cardDrawable(cardBg, 6, cardBorder, strokePx)
                        setPadding(dp(12), dp(9), dp(12), dp(9))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            dlg.dismiss()
                            val bHl = allHl.filter { it.bookPath == b.path || it.bookPath == "file://${b.path}" }
                            showReadingNotesShareDialog(
                                app = app,
                                book = b,
                                highlights = bHl,
                                totalChapters = 0,
                                rawCoverBytes = null,
                                isLcd = isLcd,
                                onBack = { showMainNotesShareDialog() }
                            )
                        }
                    }

                    val infoCol = LinearLayout(this@LibraryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    val titleTv = TextView(this@LibraryActivity).apply {
                        text = b.title
                        setPtSize(8.5f)
                        setTextColor(if (isUnreadGroup) mutedCol else inkCol)
                        typeface = if (isUnreadGroup) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    val authorTv = TextView(this@LibraryActivity).apply {
                        text = b.author.ifBlank { L10n.t("저자 미상", "Unknown") }
                        setPtSize(7.5f)
                        setTextColor(mutedCol)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    infoCol.addView(titleTv)
                    infoCol.addView(authorTv)
                    row.addView(infoCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(8)
                    })

                    val badgeTv = TextView(this@LibraryActivity).apply {
                        setPtSize(7.6f)
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        when {
                            count > 0 -> {
                                text = "🔖 " + L10n.t("메모 ${count}건", "$count notes")
                                setTextColor(ReaderTheme.ACCENT_PRIMARY_BLUE)
                                typeface = Typeface.DEFAULT_BOLD
                            }
                            b.progress >= 0.99f -> {
                                text = "🏆 " + L10n.t("독서 완료", "Completed")
                                setTextColor(if (isLcd) 0xFF2E7D32.toInt() else inkCol)
                                typeface = Typeface.DEFAULT_BOLD
                            }
                            b.progress > 0f -> {
                                text = "📖 $pct%"
                                setTextColor(if (isLcd) ReaderTheme.ACCENT_PRIMARY_BLUE else inkCol)
                            }
                            else -> {
                                text = L10n.t("기록 없음 (0%)", "No notes (0%)")
                                setTextColor(mutedCol)
                            }
                        }
                    }
                    row.addView(badgeTv)

                    contentCol.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                        rightMargin = dp(8)
                    })
                }

                // Add active / in-progress / completed / noted books
                for (b in activeBooks) {
                    addBookRow(b, isUnreadGroup = false)
                }

                // Add dashed divider (점선) between active books and unread books
                if (activeBooks.isNotEmpty() && otherBooks.isNotEmpty()) {
                    val dividerBox = LinearLayout(this@LibraryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(6), dp(8), dp(6))
                    }
                    val dottedLine = DottedDividerView(
                        this@LibraryActivity,
                        dotColor = if (isDark) ReaderTheme.DARK_BORDER else borderCol,
                        strokePx = dp(1),
                        dashDp = 4f,
                        gapDp = 4f
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(-1, dp(4))
                    }
                    val unreadLabel = TextView(this@LibraryActivity).apply {
                        text = L10n.t("── 아직 독서 기록이 없는 도서 ──", "── Books with No Reading Records ──")
                        setPtSize(7.4f)
                        setTextColor(mutedCol)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(4), 0, dp(2))
                    }
                    dividerBox.addView(dottedLine)
                    dividerBox.addView(unreadLabel)
                    contentCol.addView(dividerBox)
                }

                // Add other unread books
                for (b in otherBooks) {
                    addBookRow(b, isUnreadGroup = true)
                }

                dlg = AlertDialog.Builder(this@LibraryActivity)
                    .setTitle(L10n.t("📢 독서 노트 공유할 도서 선택", "Select Book to Share Reading Notes"))
                    .setView(scroll)
                    .setNegativeButton(L10n.t("닫기", "Close"), null)
                    .create()
                dlg.showWithOptimalWidth(this@LibraryActivity, customHeight = targetDialogH)
            }
        }
    }

    private fun showMainHighlightsDialog() {
        scope.launch {
            val allHl = withContext(Dispatchers.IO) { app.db.highlights().all() }
            if (allHl.isEmpty()) {
                withContext(Dispatchers.Main) {
                    toast(L10n.t("보관된 하이라이트나 북마크가 없습니다.", "No saved highlights or bookmarks found."))
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                val isLcd = (application as App).isLcdMode(strategy)
                val cardBg = if (isDark) ReaderTheme.DARK_CARD else (if (isLcd) cardBgCol else ReaderTheme.EINK_CARD_BG)
                val cardBorder = if (isDark) ReaderTheme.DARK_BORDER else (if (isLcd) borderCol else ReaderTheme.EINK_BORDER_CONTRAST)
                val strokePx = ReaderTheme.einkStrokePx(this@LibraryActivity, isLcd)
                val btnBg = ReaderTheme.einkBtnBg(isLcd, isDark)
                val btnBorder = ReaderTheme.einkBtnBorder(isLcd, isDark)

                lateinit var dlg: AlertDialog
                var selectedFilterPath: String? = null

                fun filterHighlights(): List<HighlightEntity> {
                    return if (selectedFilterPath == null) allHl
                    else allHl.filter { it.bookPath == selectedFilterPath || it.bookPath == "file://$selectedFilterPath" }
                }

                val dm = resources.displayMetrics
                val isLandscape = dm.widthPixels > dm.heightPixels
                val customH = if (isLandscape) (dm.heightPixels * 0.90f).toInt().coerceAtMost(dp(560))
                              else (dm.heightPixels * 0.86f).toInt().coerceAtMost(dp(720))

                val outer = LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }

                // Top Toolbar: Filter by Book + Export ZIP
                val toolBar = LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(8))
                }

                lateinit var filterBtn: Button
                filterBtn = pillButton(L10n.t("📚 도서: 전체 ▾", "📚 Book: All ▾"), inkCol, cardBg, cardBorder, strokePx) {
                    val distinctPaths = allHl.map { it.bookPath }.distinct()
                    val filterOptions = listOf(L10n.t("전체 도서 보기", "All Books")) + distinctPaths.map { path ->
                        val b = allBooks.find { it.path == path || "file://${it.path}" == path }
                        val title = b?.title ?: File(path).nameWithoutExtension
                        val count = allHl.count { it.bookPath == path }
                        "$title ($count)"
                    }
                    listDialog(L10n.t("도서 필터 선택", "Select Book Filter"), filterOptions) { optIdx ->
                        selectedFilterPath = if (optIdx == 0) null else distinctPaths[optIdx - 1]
                        val curTitle = if (selectedFilterPath == null) L10n.t("전체", "All")
                        else (allBooks.find { it.path == selectedFilterPath || "file://${it.path}" == selectedFilterPath }?.title ?: File(selectedFilterPath!!).nameWithoutExtension).take(10)
                        filterBtn.text = L10n.t("📚 도서: $curTitle ▾", "📚 Book: $curTitle ▾")
                        dlg.dismiss()
                        showMainHighlightsDialog()
                    }
                }

                val zipBtn = pillButton(L10n.t("📦 ZIP 이메일", "📦 ZIP Email"), Color.WHITE, ReaderTheme.ACCENT_PRIMARY_BLUE, ReaderTheme.ACCENT_PRIMARY_BLUE, strokePx) {
                    val currentItems = filterHighlights()
                    val bTitle = if (selectedFilterPath != null) {
                        allBooks.find { it.path == selectedFilterPath || "file://${it.path}" == selectedFilterPath }?.title ?: File(selectedFilterPath!!).nameWithoutExtension
                    } else L10n.t("AetherSAGA 전체 도서", "AetherSAGA All Books")
                    val bAuthor = if (selectedFilterPath != null) {
                        allBooks.find { it.path == selectedFilterPath || "file://${it.path}" == selectedFilterPath }?.author ?: ""
                    } else L10n.t("통합 독서 보관함", "Unified Highlights Archive")
                    showEmailHighlightsZipDialog(app, bTitle, bAuthor, currentItems, onBack = { showMainHighlightsDialog() })
                }.apply {
                    setPtSize(7.8f)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }

                toolBar.addView(filterBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                    if (isLcd) rightMargin = dp(4)
                })
                toolBar.addView(zipBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (isLcd) rightMargin = dp(4)
                })

                if (isLcd) {
                    val mindMapBtn = pillButton(L10n.t("🧠 뇌 지도", "🧠 Mind Map"), Color.WHITE, 0xFF7C3AED.toInt(), 0xFF7C3AED.toInt(), strokePx) {
                        com.aether.reader.features.memo.AdvancedMemoShareDialogs.shareKnowledgeGraphCard(
                            activity = this@LibraryActivity,
                            app = app,
                            isLcd = isLcd,
                            onBack = { showMainHighlightsDialog() }
                        )
                    }.apply {
                        setPtSize(7.8f)
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                    }
                    toolBar.addView(mindMapBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f))
                }
                outer.addView(toolBar)

                val scroll = ScrollView(this@LibraryActivity).apply {
                    isFillViewport = true
                    enableVisibleScrollbar(isDark)
                }
                val itemsBox = LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val currentList = filterHighlights()
                for ((idx, h) in currentList.withIndex()) {
                    val b = allBooks.find { it.path == h.bookPath || "file://${it.path}" == h.bookPath }
                    val bTitle = b?.title ?: h.bookTitle.ifBlank { File(h.bookPath).nameWithoutExtension }
                    val bAuthor = b?.author ?: h.bookAuthor

                    val card = LinearLayout(this@LibraryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = cardDrawable(cardBg, 8, cardBorder, strokePx)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    }

                    // 1. Meta Header: Kind Badge + Book Title + Chapter + Position %
                    val metaRow = LinearLayout(this@LibraryActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val isBookmark = (h.kind == 1)
                    val tagTv = TextView(this@LibraryActivity).apply {
                        text = if (isBookmark) "🔖 " + L10n.t("북마크", "Bookmark") else "🖍 " + L10n.t("형광펜", "Highlight")
                        setPtSize(7.6f)
                        setTextColor(if (isBookmark) ReaderTheme.ACCENT_PRIMARY_BLUE else 0xFFD97706.toInt())
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val infoTv = TextView(this@LibraryActivity).apply {
                        val ch = h.chapterTitle.ifBlank { "" }
                        text = " • $bTitle" + (if (ch.isNotBlank()) " ($ch)" else "")
                        setPtSize(7.8f)
                        setTextColor(inkCol)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    val pctTv = TextView(this@LibraryActivity).apply {
                        text = "${(h.progress * 100).toInt()}%"
                        setPtSize(7.5f)
                        setTextColor(mutedCol)
                        gravity = Gravity.END
                    }
                    metaRow.addView(tagTv)
                    metaRow.addView(infoTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(4)
                        rightMargin = dp(6)
                    })
                    metaRow.addView(pctTv)
                    card.addView(metaRow)

                    // 2. Quote Text (with Fold/Unfold: 접기 / 펼치기)
                    val quoteTv = TextView(this@LibraryActivity).apply {
                        text = "“" + h.quote.trim() + "”"
                        setPtSize(8.8f)
                        setTextColor(inkCol)
                        typeface = Typeface.create("serif", Typeface.NORMAL)
                        setLineSpacing(dp(2).toFloat(), 1.15f)
                        setPadding(0, dp(6), 0, dp(4))
                    }
                    val isLong = h.quote.length > 70 || h.quote.contains("\n")
                    if (isLong) {
                        quoteTv.maxLines = 3
                        quoteTv.ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    card.addView(quoteTv)

                    if (isLong) {
                        var expanded = false
                        val toggleBtn = TextView(this@LibraryActivity).apply {
                            text = L10n.t("더보기 ▾", "Show More ▾")
                            setPtSize(7.8f)
                            setTextColor(if (isLcd) ReaderTheme.ACCENT_PRIMARY_BLUE else ReaderTheme.EINK_INK)
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(0, 0, 0, dp(4))
                            setOnClickListener {
                                expanded = !expanded
                                if (expanded) {
                                    quoteTv.maxLines = Int.MAX_VALUE
                                    quoteTv.ellipsize = null
                                    text = L10n.t("접기 ▴", "Show Less ▴")
                                } else {
                                    quoteTv.maxLines = 3
                                    quoteTv.ellipsize = android.text.TextUtils.TruncateAt.END
                                    text = L10n.t("더보기 ▾", "Show More ▾")
                                }
                            }
                        }
                        card.addView(toggleBtn)
                    }

                    // 3. Note/Memo if present
                    if (h.note.isNotBlank()) {
                        val noteBox = LinearLayout(this@LibraryActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            background = cardDrawable(if (isLcd) 0xFFF0F4F8.toInt() else Color.WHITE, 4, cardBorder, dp(1))
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(4)
                                bottomMargin = dp(4)
                            }
                        }
                        val noteTitleTv = TextView(this@LibraryActivity).apply {
                            text = L10n.t("📝 메모:", "📝 Note:")
                            setPtSize(7.6f)
                            setTextColor(if (isLcd) ReaderTheme.ACCENT_PRIMARY_BLUE else ReaderTheme.EINK_INK)
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        val noteBodyTv = TextView(this@LibraryActivity).apply {
                            text = h.note.trim()
                            setPtSize(8.2f)
                            setTextColor(inkCol)
                            setLineSpacing(dp(2).toFloat(), 1.15f)
                        }
                        val isNoteLong = h.note.length > 70 || h.note.contains("\n")
                        if (isNoteLong) {
                            noteBodyTv.maxLines = 3
                            noteBodyTv.ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                        noteBox.addView(noteTitleTv)
                        noteBox.addView(noteBodyTv)

                        if (isNoteLong) {
                            var noteExpanded = false
                            val noteToggleBtn = TextView(this@LibraryActivity).apply {
                                text = L10n.t("더보기 ▾", "Show More ▾")
                                setPtSize(7.6f)
                                setTextColor(if (isLcd) ReaderTheme.ACCENT_PRIMARY_BLUE else ReaderTheme.EINK_INK)
                                typeface = Typeface.DEFAULT_BOLD
                                setPadding(0, dp(2), 0, 0)
                                setOnClickListener {
                                    noteExpanded = !noteExpanded
                                    if (noteExpanded) {
                                        noteBodyTv.maxLines = Int.MAX_VALUE
                                        noteBodyTv.ellipsize = null
                                        text = L10n.t("접기 ▴", "Show Less ▴")
                                    } else {
                                        noteBodyTv.maxLines = 3
                                        noteBodyTv.ellipsize = android.text.TextUtils.TruncateAt.END
                                        text = L10n.t("더보기 ▾", "Show More ▾")
                                    }
                                }
                            }
                            noteBox.addView(noteToggleBtn)
                        }
                        card.addView(noteBox)
                    }

                    // 4. Action Buttons: [📖 도서 열기] [📷 SNS 공유] [🗑 삭제]
                    val btnRow = LinearLayout(this@LibraryActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, 0)
                    }
                    val openBtn = pillButton(L10n.t("📖 열기", "📖 Open"), inkCol, btnBg, btnBorder, strokePx) {
                        dlg.dismiss()
                        openBookAtHighlight(h)
                    }.apply {
                        setPtSize(7.8f)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        minHeight = dp(28)
                        minimumHeight = dp(28)
                    }
                    val cardBtn = pillButton(L10n.t("📷 SNS", "📷 SNS"), inkCol, btnBg, btnBorder, strokePx) {
                        val coverBmp = b?.coverPath?.let { p -> if (java.io.File(p).exists()) android.graphics.BitmapFactory.decodeFile(p) else null }
                        AndroidShareHelper.showSnsShareDialog(
                            this@LibraryActivity,
                            bTitle,
                            bAuthor,
                            h.quote,
                            h.note.ifBlank { null },
                            isEink = !isLcd,
                            onBack = { showMainHighlightsDialog() },
                            coverBitmap = coverBmp
                        )
                    }.apply {
                        setPtSize(7.8f)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        minHeight = dp(28)
                        minimumHeight = dp(28)
                    }
                    val deleteBtn = pillButton(L10n.t("🗑 삭제", "🗑 Delete"), 0xFFB71C1C.toInt(), btnBg, btnBorder, strokePx) {
                        AlertDialog.Builder(this@LibraryActivity)
                            .setTitle(L10n.t("기록 삭제", "Delete Note"))
                            .setMessage(L10n.t("선택한 하이라이트/북마크를 영구 삭제하시겠습니까?", "Delete this highlight/bookmark permanently?"))
                            .setPositiveButton(L10n.t("삭제", "Delete")) { _, _ ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { app.db.highlights().delete(h.id) }
                                    withContext(Dispatchers.Main) {
                                        toast(L10n.t("삭제되었습니다.", "Deleted."))
                                        dlg.dismiss()
                                        showMainHighlightsDialog()
                                    }
                                }
                            }
                            .setNegativeButton(L10n.t("취소", "Cancel"), null)
                            .create()
                            .showWithOptimalWidth(this@LibraryActivity)
                    }.apply {
                        setPtSize(7.8f)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        minHeight = dp(28)
                        minimumHeight = dp(28)
                    }

                    btnRow.addView(openBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
                    btnRow.addView(cardBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
                    btnRow.addView(deleteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    card.addView(btnRow)

                    itemsBox.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                        rightMargin = dp(10)
                    })
                    if (idx < currentList.size - 1) {
                        if (!isLcd) {
                            itemsBox.addView(DottedDividerView(this@LibraryActivity, dotColor = 0xFF888888.toInt(), strokePx = dp(1)).apply {
                                layoutParams = LinearLayout.LayoutParams(-1, dp(3)).apply {
                                    topMargin = dp(2)
                                    bottomMargin = dp(6)
                                    rightMargin = dp(10)
                                }
                            })
                        }
                    }
                }

                scroll.addView(itemsBox)
                outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

                dlg = AlertDialog.Builder(this@LibraryActivity)
                    .setTitle(L10n.t("🖍 하이라이트 및 북마크 (${currentList.size}건)", "Highlights & Bookmarks (${currentList.size})"))
                    .setView(outer)
                    .create()
                dlg.showWithOptimalWidth(this@LibraryActivity, customHeight = customH)
            }
        }
    }


    companion object {
        fun findMatchingBookFile(root: File, targetName: String, expectedSize: Long = 0L): File? {
            val queue = java.util.ArrayDeque<File>()
            queue.add(root)
            var limit = 0
            while (queue.isNotEmpty() && limit < 400) {
                val dir = queue.removeFirst()
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    if (f.isDirectory) {
                        if (!f.name.startsWith(".")) queue.add(f)
                    } else if (f.isFile) {
                        if (f.name.equals(targetName, ignoreCase = true)) {
                            if (expectedSize <= 0L || f.length() == expectedSize) {
                                return f
                            }
                        }
                    }
                }
                limit++
            }
            return null
        }
    }
}
