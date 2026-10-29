package com.aether.reader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.aether.reader.App
import com.aether.reader.data.*
import com.aether.reader.domain.display.*
import com.aether.reader.engine.*
import com.aether.reader.platform.FeatureFlag
import com.aether.reader.platform.FrontlightController
import com.aether.reader.platform.KeyEventDispatcher
import com.aether.reader.platform.OrientationManager
import com.aether.reader.platform.TtsController
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class ReaderActivity : Activity(), ReaderView.Listener {
    override fun attachBaseContext(newBase: android.content.Context) {
        val p = Prefs(newBase)
        val lang = p.getEffectiveLanguage(newBase)
        val localized = L10n.applyLocale(newBase, lang)
        super.attachBaseContext(localized)
    }

    private lateinit var app: App
    private lateinit var rootFrame: FrameLayout
    private lateinit var view: ReaderView
    private lateinit var warmOverlay: View
    private lateinit var flash: View
    private lateinit var hud: TextView
    private lateinit var loadingCard: LinearLayout
    private lateinit var loadingText: TextView

    // Kindle / Ridi Floating Overlay Controls
    private lateinit var overlayContainer: FrameLayout
    private lateinit var topBarTitle: TextView
    private lateinit var topBarSubtitle: TextView
    private lateinit var scrubberLabel: TextView
    private lateinit var scrubberSeek: SeekBar

    private lateinit var frontlight: FrontlightController
    private lateinit var keys: KeyEventDispatcher
    private lateinit var tts: TtsController
    private lateinit var orientationMgr: OrientationManager
    private lateinit var strategy: DisplayStrategy
    private lateinit var path: String

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pool = BitmapPoolImpl()
    private val engine = PagingEngineImpl(pool)
    private val handler = Handler(Looper.getMainLooper())

    private var book: BookEntity? = null
    @Volatile private var highlights: List<HighlightEntity> = emptyList()
    private var opened = false
    private var relayoutJob: Job? = null
    private var pendingRelayoutLoc: BookLocation? = null
    private var pagesSincePurge = 0
    private var edgeBase = 0f
    private val navHistory = NavigationHistoryManager()
    private var pomodoroSeconds = 25 * 60
    private var pageStartTimeMs: Long = System.currentTimeMillis()
    private var avgSecondsPerPage: Double = 45.0
    private var jumpSnackbar: Button? = null
    private var activeHighlightDeleteAnchor: View? = null
    private lateinit var bookmarkRibbonView: View

    private val autoTurnRunnable = object : Runnable {
        override fun run() {
            if (!app.prefs.autoTurnEnabled) return
            turn(forward = true)
            val sec = app.prefs.autoTurnSeconds.coerceIn(10, 120)
            handler.postDelayed(this, sec * 1000L)
        }
    }

    private fun startAutoPageTurn() {
        handler.removeCallbacks(autoTurnRunnable)
        val sec = app.prefs.autoTurnSeconds.coerceIn(10, 120)
        handler.postDelayed(autoTurnRunnable, sec * 1000L)
        toast("핸즈프리 자동 책넘김 시작 (${sec}초 간격)")
    }

    private fun stopAutoPageTurn() {
        handler.removeCallbacks(autoTurnRunnable)
    }

    private fun resetAutoPageTurnTimer() {
        if (app.prefs.autoTurnEnabled) {
            handler.removeCallbacks(autoTurnRunnable)
            val sec = app.prefs.autoTurnSeconds.coerceIn(10, 120)
            handler.postDelayed(autoTurnRunnable, sec * 1000L)
        }
    }

    private class BookmarkRibbonView(context: Context, val isEink: Boolean) : View(context) {
        private val ribbonPath = Path()
        private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isEink) ReaderTheme.EINK_INK else 0xFFD32F2F.toInt()
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = if (isEink) 0xFF000000.toInt() else 0xFFB71C1C.toInt()
            strokeWidth = context.resources.displayMetrics.density * 1.2f
        }
        private val innerMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = context.resources.displayMetrics.density * 1.2f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val notch = h * 0.28f
            ribbonPath.reset()
            ribbonPath.moveTo(0f, 0f)
            ribbonPath.lineTo(w, 0f)
            ribbonPath.lineTo(w, h)
            ribbonPath.lineTo(w / 2f, h - notch)
            ribbonPath.lineTo(0f, h)
            ribbonPath.close()

            canvas.drawPath(ribbonPath, ribbonPaint)
            canvas.drawPath(ribbonPath, borderPaint)

            canvas.drawLine(w * 0.28f, h * 0.25f, w * 0.72f, h * 0.25f, innerMarkPaint)
            canvas.drawLine(w * 0.28f, h * 0.40f, w * 0.72f, h * 0.40f, innerMarkPaint)
        }
    }
    private val idleDim = Runnable { if (::frontlight.isInitialized && ::strategy.isInitialized && strategy.kind != DisplayKind.LCD_OLED) frontlight.dim() }

    private val refreshController = object : PanelRefreshController {
        override fun requestRefresh(mode: RefreshMode) {
            if (mode == RefreshMode.PARTIAL) return
            pagesSincePurge = 0
            doFlash(mode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        L10n.applyLocale(this, app.prefs.getEffectiveLanguage(this))
        val rawPath = intent.getStringExtra("path") ?: intent.data?.toString() ?: run { finish(); return }
        path = Containers.normalizePath(rawPath)
        overridePendingTransition(0, 0)

        view = ReaderView(this).also { it.listener = this }
        warmOverlay = View(this).apply { isClickable = false; isFocusable = false }
        frontlight = FrontlightController(this, warmOverlay, app.prefs)
        configureEngine()
        frontlight.apply()
        flash = View(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xDD111111.toInt())
                cornerRadius = dp(8).toFloat()
            }
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }

        buildLoadingIndicator()
        buildFloatingOverlay()

        rootFrame = FrameLayout(this)
        rootFrame.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val isLcdDevice = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        bookmarkRibbonView = BookmarkRibbonView(this, !isLcdDevice).apply {
            visibility = View.GONE
            setOnClickListener {
                val bm = findCurrentPageBookmark()
                if (bm != null) {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.db.highlights().delete(bm.id) }
                        highlights = withContext(Dispatchers.IO) { app.db.highlights().forBook(path) }
                        toast(L10n.t("북마크가 해제되었습니다", "Bookmark removed"))
                        updateBookmarkIndicator()
                    }
                }
            }
        }
        rootFrame.addView(
            bookmarkRibbonView,
            FrameLayout.LayoutParams(dp(28), dp(40), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(4)
                rightMargin = dp(14)
            }
        )
        rootFrame.addView(overlayContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootFrame.addView(loadingCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        rootFrame.addView(warmOverlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootFrame.addView(flash, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootFrame.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(rootFrame)
        view.hideSystemBars()

        orientationMgr = OrientationManager(this, app.prefs) {
            relayoutNow()
        }
        keys = KeyEventDispatcher(app.prefs) { perform(it) }
        tts = TtsController(
            ctx = this,
            allowSpeaker = {
                if (::strategy.isInitialized) {
                    strategy.kind == DisplayKind.LCD_OLED
                } else {
                    app.hardware.spec.displayKind == DisplayKind.LCD_OLED || app.hardware.spec.isTablet
                }
            }
        ) { handler.post { if (tts.speaking) turn(true, speakAfter = true) } }

        engine.setListener(object : PagingListener {
            override fun onChapterLayoutFinished(chapter: Int, pageCount: Int) {
                runOnUiThread {
                    if (opened) {
                        updateProgressDisplay()
                        if (overlayContainer.visibility == View.VISIBLE) syncOverlayState()
                    }
                }
            }
        })

        scope.launch {
            book = withContext(Dispatchers.IO) {
                app.db.books().get(path) ?: app.db.books().get("file://$path")
            }
            highlights = withContext(Dispatchers.IO) {
                val h = app.db.highlights().forBook(path)
                if (h.isNotEmpty()) h else app.db.highlights().forBook("file://$path")
            }
        }
    }

    private fun buildLoadingIndicator() {
        loadingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
                cornerRadius = dp(8).toFloat()
            }
            visibility = View.GONE
        }
        val icon = TextView(this).apply {
            text = "📖"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        loadingText = TextView(this).apply {
            text = "도서 레이아웃 구성 중..."
            textSize = 13.5f
            setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(8), 0, 0)
        }
        loadingCard.addView(icon)
        loadingCard.addView(loadingText)
    }

    private fun buildFloatingOverlay() {
        overlayContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0x18000000)
            setOnClickListener { hideOverlay() }
        }

        // 1. Top Floating Bar (Kindle / Ridi style)
        val isEink = if (::strategy.isInitialized) strategy.kind != DisplayKind.LCD_OLED else true
        fun makeOverlayBarBg(): Drawable = if (isEink) {
            GradientDrawable().apply {
                setColor(ReaderTheme.EINK_CARD_BG)
                setStroke(dp(1), ReaderTheme.EINK_DIVIDER_CONTRAST, dp(4).toFloat(), dp(3).toFloat())
            }
        } else {
            GradientDrawable().apply {
                setColor(ReaderTheme.EINK_PAPER)
                setStroke(dp(1), ReaderTheme.EINK_BORDER)
            }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = makeOverlayBarBg()
            isClickable = true
        }

        val backBtn = pillButton(L10n.t("← 서재", "← Library"), ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
            finish()
        }.apply {
            setPtSize(8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        topBarTitle = TextView(this).apply {
            setPtSize(9f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = ReaderTheme.uiFontBold
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        topBarSubtitle = TextView(this).apply {
            setPtSize(7.5f)
            setTextColor(ReaderTheme.EINK_MUTED)
            typeface = ReaderTheme.uiFontRegular
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(topBarTitle)
        titleCol.addView(topBarSubtitle)

        val searchBtn = pillButton(L10n.t("🔍 검색", "🔍 Search"), ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
            hideOverlay()
            showSearchDialog()
        }.apply {
            setPtSize(8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val bookmarkBtn = pillButton(L10n.t("🔖 북마크", "🔖 Bookmark"), ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
            hideOverlay()
            addBookmark()
        }.apply {
            setPtSize(8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val moreBtn = pillButton(L10n.t("⚙ 설정", "⚙ Settings"), ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
            hideOverlay()
            showFullMenu()
        }.apply {
            setPtSize(8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        topBar.addView(backBtn)
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(searchBtn)
        topBar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        topBar.addView(bookmarkBtn)
        topBar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        topBar.addView(moreBtn)

        overlayContainer.addView(topBar, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // 2. Bottom Floating Control Panel (Scrubber + Quick Actions)
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = makeOverlayBarBg()
            isClickable = true
        }

        // Scrubber Card (Prev Chapter / Global Page Progress / Next Chapter + SeekBar)
        val scrubCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            background = cardDrawable(ReaderTheme.EINK_CARD_BG, 8, if (isEink) ReaderTheme.EINK_DIVIDER_CONTRAST else ReaderTheme.EINK_BORDER)
        }
        val scrubHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prevChBtn = pillButton(L10n.t("◀ 이전 챕터", "◀ Prev Ch"), ReaderTheme.EINK_INK, ReaderTheme.EINK_PAPER, ReaderTheme.EINK_BORDER) {
            val curLoc = engine.currentLocation()
            if (curLoc.chapterIndex > 0) {
                pushHistoryAndJump(BookLocation(curLoc.chapterIndex - 1, 0L, 0f))
                syncOverlayState()
            } else toast(L10n.t("첫 번째 챕터입니다", "This is the first chapter"))
        }.apply {
            setPtSize(8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        scrubberLabel = TextView(this).apply {
            setPtSize(8.2f)
            setTextColor(ReaderTheme.EINK_INK)
            gravity = Gravity.CENTER
            typeface = ReaderTheme.uiFontBold
            setLineSpacing(dp(2).toFloat(), 1f)
            maxLines = 2
        }
        val nextChBtn = pillButton(L10n.t("다음 챕터 ▶", "Next Ch ▶"), ReaderTheme.EINK_INK, ReaderTheme.EINK_PAPER, ReaderTheme.EINK_BORDER) {
            val curLoc = engine.currentLocation()
            if (curLoc.chapterIndex + 1 < engine.chapters.size) {
                pushHistoryAndJump(BookLocation(curLoc.chapterIndex + 1, 0L, 0f))
                syncOverlayState()
            } else toast(L10n.t("마지막 챕터입니다", "This is the last chapter"))
        }.apply {
            setPtSize(8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        scrubHeader.addView(prevChBtn)
        scrubHeader.addView(scrubberLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scrubHeader.addView(nextChBtn)
        scrubCard.addView(scrubHeader)

        // Page Scrubber SeekBar (Whole-Book Global Page Navigation)
        scrubberSeek = SeekBar(this).apply {
            setPadding(dp(4), dp(8), dp(4), dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val (_, total) = engine.pageInfo()
                        val pct = if (total <= 1) 100 else ((p.toFloat() / (total - 1).coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
                        val remP = (total - (p + 1)).coerceAtLeast(0)
                        val est = formatEstimatedTime(remP, avgSecondsPerPage)
                        val line1 = L10n.t("전체 ${p + 1} / $total 페이지  •  $pct%", "Page ${p + 1} / $total  •  $pct%")
                        val line2 = L10n.t("완독까지 $est", "$est to finish")
                        scrubberLabel.text = "$line1\n$line2"
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    navHistory.push(engine.currentLocation())
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val targetGlobalPage = (s?.progress ?: 0) + 1
                    scope.launch {
                        showResult(engine.goToGlobalPage(targetGlobalPage), 0)
                        syncOverlayState()
                        navHistory.pop()?.let { showJumpBackBar(it) }
                    }
                }
            })
        }
        scrubCard.addView(scrubberSeek)
        bottomPanel.addView(scrubCard, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        // Symmetrical 5-Item Quick Action Bar
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addQuickBtn(label: String, onClick: () -> Unit) {
            val b = pillButton(label, ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
                hideOverlay()
                onClick()
            }.apply {
                setPtSize(7.8f)
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            quickRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
        }
        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        val isComic = (book?.format == "CBZ" || book?.format == "CBR" || path.endsWith(".cbz", true) || path.endsWith(".cbr", true))
        if (isComic) {
            addQuickBtn(L10n.t("🎨 만화설정", "🎨 Manga")) { showComicSettingsDialog() }
            addQuickBtn(L10n.t("☰ 목차", "☰ Contents")) { showTocAndNotesDialog() }
            addQuickBtn(L10n.t("☀ 화면/회전", "☀ Screen")) { frontlightAndDisplaySheet() }
            addQuickBtn(L10n.t("⚡ 보조도구", "⚡ Tools")) { showAdvancedFeaturesMenu() }
            if (!isLcd) {
                addQuickBtn(L10n.t("⟳ 잔상제거", "⟳ Refresh")) { perform(ReaderAction.FULL_REFRESH) }
            }
        } else {
            addQuickBtn(L10n.t("☰ 목차", "☰ Contents")) { showTocAndNotesDialog() }
            addQuickBtn(L10n.t("Aa 조판", "Aa Font")) { showTypographySheet() }
            addQuickBtn(L10n.t("☀ 화면/회전", "☀ Screen")) { frontlightAndDisplaySheet() }
            addQuickBtn(L10n.t("⚡ 보조도구", "⚡ Tools")) { showAdvancedFeaturesMenu() }
            if (!isLcd) {
                addQuickBtn(L10n.t("⟳ 잔상제거", "⟳ Refresh")) { perform(ReaderAction.FULL_REFRESH) }
            }
        }

        bottomPanel.addView(quickRow)
        overlayContainer.addView(bottomPanel, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun toggleOverlay() {
        if (overlayContainer.visibility == View.VISIBLE) {
            hideOverlay()
        } else {
            syncOverlayState()
            overlayContainer.visibility = View.VISIBLE
        }
    }

    private fun hideOverlay() {
        overlayContainer.visibility = View.GONE
        view.hideSystemBars()
    }

    private fun formatEstimatedTime(remainingPages: Int, secPerPage: Double): String {
        val totalSec = (remainingPages * secPerPage).toInt()
        val totalMin = (totalSec + 59) / 60
        return when {
            totalMin <= 0 -> L10n.t("곧 완독", "almost done")
            totalMin < 60 -> L10n.t("약 ${totalMin}분", "about ${totalMin}m")
            else -> {
                val h = totalMin / 60
                val m = totalMin % 60
                if (m == 0) L10n.t("약 ${h}시간", "about ${h}h") else L10n.t("약 ${h}시간 ${m}분", "about ${h}h ${m}m")
            }
        }
    }

    private fun syncOverlayState() {
        val b = book
        topBarTitle.text = (b?.title?.ifBlank { engine.bookTitle } ?: engine.bookTitle).ifBlank { "Aether SAGA" }
        val loc = engine.currentLocation()
        topBarSubtitle.text = engine.chapterTitle(loc.chapterIndex)
        val (pg, total) = engine.pageInfo()
        val pct = (loc.progress * 100).toInt().coerceIn(0, 100)
        val remPages = (total - pg).coerceAtLeast(0)
        val est = formatEstimatedTime(remPages, avgSecondsPerPage)
        val line1 = L10n.t("전체 $pg / $total 페이지  •  $pct%", "Page $pg / $total  •  $pct%")
        val line2 = L10n.t("완독까지 $est", "$est to finish")
        scrubberLabel.text = "$line1\n$line2"
        scrubberSeek.max = (total - 1).coerceAtLeast(0)
        scrubberSeek.progress = (pg - 1).coerceIn(0, (total - 1).coerceAtLeast(0))
    }

    private fun updateProgressDisplay() {
        val (pg, total) = engine.pageInfo()
        val loc = engine.currentLocation()
        val pct = (loc.progress * 100).toInt().coerceIn(0, 100)
        view.statusChapter = engine.chapterTitle(loc.chapterIndex)
        val remPages = (total - pg).coerceAtLeast(0)
        val est = formatEstimatedTime(remPages, avgSecondsPerPage)
        val finishPart = L10n.t("완독 $est", "finish $est")
        view.statusProgress = "$pg / $total  •  $pct%  •  $finishPart"
        view.progressRatio = loc.progress
        book = book?.copy(chapter = loc.chapterIndex, charOffset = loc.charOffset, progress = loc.progress)
    }

    // ---- configuration -----------------------------------------------------------------

    private fun configureEngine() {
        strategy = app.profiles.detect(app.prefs.displaySettings())
        val isLcd = (strategy.kind == DisplayKind.LCD_OLED)
        val bg: Int
        val fg: Int
        val dark: Boolean
        if (isLcd) {
            when (app.prefs.readingTheme) {
                "SEPIA" -> {
                    bg = ReaderTheme.SEPIA_PAPER
                    fg = ReaderTheme.SEPIA_INK
                    dark = false
                }
                "DARK" -> {
                    bg = ReaderTheme.DARK_SURFACE
                    fg = ReaderTheme.DARK_TEXT
                    dark = true
                }
                "OLED_BLACK" -> {
                    bg = ReaderTheme.TRUE_BLACK_PAPER
                    fg = ReaderTheme.TRUE_BLACK_INK
                    dark = true
                }
                else -> { // "WHITE"
                    bg = ReaderTheme.EINK_PAPER
                    fg = ReaderTheme.EINK_INK
                    dark = false
                }
            }
        } else {
            // E-Ink devices: pure Carta paper off-white & deep ink black.
            // Software warm/sepia tints are strictly hidden and disabled to prevent optical contrast loss.
            bg = ReaderTheme.EINK_PAPER
            fg = ReaderTheme.EINK_INK
            dark = false
        }
        engine.fgColor = fg; engine.bgColor = bg
        engine.dropCapsEnabled = app.prefs.dropCaps
        engine.forcedEncoding = app.prefs.txtEncoding
        view.readingRulerEnabled = app.prefs.readingRuler
        view.isEinkDisplay = !isLcd
        if (::frontlight.isInitialized) {
            frontlight.isEink = !isLcd
        }
        engine.bitmapConfig = strategy.renderSpec.config
        view.bg = bg; view.fg = fg
        view.transition = PageTransition.INSTANT
        view.durationMs = 0
        val colorful = strategy.kind != DisplayKind.MONO_EINK
        val palette = if (colorful) intArrayOf(0xFFD32F2F.toInt(), 0xFF388E3C.toInt(), 0xFF1976D2.toInt(), 0xFFF9A825.toInt())
        else intArrayOf(Color.BLACK, 0xFF444444.toInt(), 0xFF777777.toInt(), 0xFFAAAAAA.toInt())
        val fixed = if (dark) palette.map { if (it == Color.BLACK) Color.WHITE else it }.toIntArray() else palette
        engine.highlightProvider = { ch ->
            highlights.filter { it.chapter == ch && it.kind == 0 }
                .map { Mark(it.startChar.toInt(), it.endChar.toInt(), it.style, fixed[it.colorTag.coerceIn(0, 3)]) }
        }
    }

    private fun typography(w: Int): TypographySpec {
        val p = app.prefs; val ls = strategy.layoutSpec
        val dm = resources.displayMetrics
        val fontPx = p.fontSizePt * dm.densityDpi / 72f
        val cols = ls.columns
        val gap = PagingEngineImpl.COLUMN_GAP
        val mv = (p.marginDp * dm.density * ls.marginScale).toInt()
        var mh = mv
        val maxW = ls.maxLineWidthEm * fontPx
        val colW = (w - 2 * mh - gap * (cols - 1)) / cols.toFloat()
        if (colW > maxW) mh = ((w - cols * maxW - gap * (cols - 1)) / 2).toInt()
        val footer = (dm.scaledDensity * 11f * 2.5f).toInt()
        return TypographySpec(p.useDocumentDefaults, p.fontPath.ifBlank { null }, p.fontSizePt, p.lineHeight,
            (p.paraSpacingDp * dm.density).toInt(), p.letterSpacingEm, mh, mv, mh, mv + footer,
            strategy.renderSpec.boldnessLevel)
    }

    private fun viewport() = Viewport(view.width, view.height, strategy.layoutSpec.columns, resources.displayMetrics.densityDpi)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        app.profiles.onConfigurationChanged()
    }

    override fun onSizeReady(w: Int, h: Int) {
        scope.launch {
            configureEngine()
            if (!opened) {
                opened = true
                val showLoadJob = launch {
                    delay(250)
                    loadingText.text = "도서 분석 및 조판 중..."
                    loadingCard.visibility = View.VISIBLE
                }
                val b = withContext(Dispatchers.IO) {
                    app.db.books().get(path) ?: app.db.books().get("file://$path")
                }
                book = b
                try {
                    val resolvedFmt = b?.format?.let { runCatching { BookFormat.valueOf(it) }.getOrNull() }
                        ?: Containers.formatOf(path, this@ReaderActivity)
                        ?: BookFormat.TXT

                    var openPath = path
                    if (path.startsWith("content://")) {
                        try {
                            val localCandidate = Containers.resolveContentToFile(path, this@ReaderActivity, resolvedFmt, b?.title ?: "")
                            if (localCandidate.isNotBlank() && File(localCandidate).exists() && localCandidate != path) {
                                val oldPath = path
                                withContext(Dispatchers.IO) {
                                    app.db.books().updatePath(oldPath, localCandidate)
                                    app.db.highlights().updateBookPath(oldPath, localCandidate)
                                }
                                this@ReaderActivity.path = localCandidate
                                openPath = localCandidate
                                b?.let { book = it.copy(path = localCandidate) }
                            }
                        } catch (_: Throwable) {}
                    }

                    engine.open(openPath, resolvedFmt, typography(w), viewport(), this@ReaderActivity)

                    if (b != null) {
                        val liveTitle = engine.bookTitle.ifBlank { b.title }
                        val liveAuthor = engine.bookAuthor.ifBlank { b.author }
                        if (liveTitle != b.title || liveAuthor != b.author) {
                            book = b.copy(title = liveTitle, author = liveAuthor)
                        }
                    }
                    val loc = BookLocation(b?.chapter ?: 0, b?.charOffset ?: 0L, b?.progress ?: 0f)
                    val res = engine.goTo(loc)
                    showLoadJob.cancel()
                    loadingCard.visibility = View.GONE
                    showResult(res, 0)
                    pullRemotePosition(b)
                } catch (e: Throwable) {
                    showLoadJob.cancel()
                    loadingCard.visibility = View.GONE
                    Toast.makeText(this@ReaderActivity, "도서를 열 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            } else {
                relayoutNow()
            }
        }
    }

    /** Re-layout while keeping the reader's place without blanking the current page bitmap. */
    private fun relayoutNow(reopenContainer: Boolean = false) {
        if (!opened || view.width <= 0 || view.height <= 0) return
        val targetLoc = pendingRelayoutLoc ?: engine.currentLocation()
        pendingRelayoutLoc = targetLoc
        relayoutJob?.cancel()
        relayoutJob = scope.launch {
            configureEngine()
            val w = view.width.coerceAtLeast(100)
            val vp = viewport()
            if (reopenContainer) {
                val resolvedFmt = book?.format?.let { runCatching { BookFormat.valueOf(it) }.getOrNull() }
                    ?: Containers.formatOf(path, this@ReaderActivity)
                    ?: BookFormat.TXT
                engine.open(path, resolvedFmt, typography(w), vp, this@ReaderActivity)
            } else {
                engine.relayout(typography(w), vp)
            }
            val res = engine.goTo(targetLoc)
            pendingRelayoutLoc = null
            showResult(res, 0)
            view.invalidate()
        }
    }

    // ---- paging ------------------------------------------------------------------------

    private fun showResult(r: PageResult, dir: Int) {
        dismissHighlightDeleteButton()
        view.selectionRects = null
        currentSelectionRange = null
        when (r) {
            is PageResult.Ready -> {
                view.showPage(r.bitmap, dir)
                val now = System.currentTimeMillis()
                val elapsed = now - pageStartTimeMs
                pageStartTimeMs = now
                if (elapsed in 5_000L..180_000L) {
                    val sec = elapsed / 1000.0
                    avgSecondsPerPage = (avgSecondsPerPage * 0.7) + (sec * 0.3)
                    app.recordReadingSession(1, elapsed)
                }
                updateProgressDisplay()
                updateBookmarkIndicator()
                val loc = engine.currentLocation()
                if (highlights.any { it.chapter == loc.chapterIndex && it.kind == 0 } || book?.format == "PDF" || path.endsWith(".pdf", true)) {
                    if (::strategy.isInitialized && strategy.kind != DisplayKind.LCD_OLED) {
                        view.postDelayed({ doFlash(if (strategy.kind == DisplayKind.COLOR_EINK) RefreshMode.FULL_GC16 else RefreshMode.FULL_INVERT) }, 50)
                    }
                }
                if (view.readingRulerEnabled) {
                    val targetY = if (view.rulerY > 0f) view.rulerY.toInt() else (view.height / 3).coerceAtLeast(100)
                    view.rulerSentenceRects = engine.sentenceBoundsAt(view.width / 2, targetY)
                }
            }
            PageResult.EndOfBook -> {
                val now = System.currentTimeMillis()
                val bTitle = book?.title?.ifBlank { null } ?: java.io.File(path).nameWithoutExtension
                val bAuthor = book?.author ?: ""
                app.recordBookCompleted(path, bTitle, bAuthor, now)
                if (app.prefs.autoTurnEnabled) {
                    app.prefs.autoTurnEnabled = false
                    stopAutoPageTurn()
                    toast("마지막 페이지에 도달하여 자동 책넘김을 종료합니다")
                } else {
                    toast("마지막 페이지입니다")
                }
            }
            PageResult.StartOfBook -> toast("첫 페이지입니다")
            is PageResult.Failure -> toast("오류: ${r.cause.message}")
        }
    }

    private fun turn(forward: Boolean, speakAfter: Boolean = false) {
        dismissHighlightDeleteButton()
        view.selectionRects = null
        currentSelectionRange = null
        if (app.prefs.autoTurnEnabled) {
            resetAutoPageTurnTimer()
        }
        if (overlayContainer.visibility == View.VISIBLE) {
            hideOverlay()
            return
        }
        val isMangaRtl = (book?.format == "CBZ" || book?.format == "CBR" || path.endsWith(".cbz", true) || path.endsWith(".cbr", true)) &&
                app.prefs.comicDirection == "RTL"
        // Invert navigation for Japanese Manga (RTL)
        val actualForward = if (isMangaRtl) !forward else forward

        scope.launch {
            val r = if (actualForward) engine.next() else engine.previous()
            showResult(r, if (actualForward) 1 else -1)
            if (r is PageResult.Ready) {
                pagesSincePurge++
                strategy.onPagePresented(pagesSincePurge, refreshController)
                if (speakAfter) speakPage()
            } else tts.stop()
        }
    }

    private fun doFlash(mode: RefreshMode) {
        val seq = if (mode == RefreshMode.FULL_GC16) listOf(Color.BLACK, Color.WHITE, Color.BLACK, Color.WHITE) else listOf(Color.BLACK, Color.WHITE)
        flash.visibility = View.VISIBLE
        var i = 0
        val step = object : Runnable {
            override fun run() {
                if (i < seq.size) { flash.setBackgroundColor(seq[i++]); handler.postDelayed(this, 85) } else flash.visibility = View.GONE
            }
        }
        handler.post(step)
    }

    // ---- ReaderView.Listener -------------------------------------------------------------

    override fun onAnyTouch() { resetIdle() }

    override fun onSingleTap(x: Float, y: Float): Boolean {
        if (activeHighlightDeleteAnchor != null) {
            dismissHighlightDeleteButton()
            return true
        }
        if (overlayContainer.visibility == View.VISIBLE) {
            hideOverlay()
            return true
        }
        val loc = engine.currentLocation()
        val pageHighlights = highlights.filter { it.chapter == loc.chapterIndex && it.kind == 0 }
        for (h in pageHighlights) {
            val rects = engine.selectionBounds(TextRange(h.chapter, h.startChar, h.endChar, h.quote))
            if (!rects.isNullOrEmpty()) {
                val hit = rects.any { r ->
                    x >= (r.left - dp(8)) && x <= (r.right + dp(8)) &&
                    y >= (r.top - dp(8)) && y <= (r.bottom + dp(8))
                }
                if (hit) {
                    showHighlightDeleteAnchor(h, rects.last())
                    return true
                }
            }
        }
        return false
    }

    private fun dismissHighlightDeleteButton() {
        activeHighlightDeleteAnchor?.let {
            rootFrame.removeView(it)
            activeHighlightDeleteAnchor = null
        }
    }

    private fun showHighlightDeleteAnchor(h: HighlightEntity, lastRect: RectF) {
        dismissHighlightDeleteButton()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(ReaderTheme.EINK_CARD_BG)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), ReaderTheme.EINK_INK)
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
            elevation = dp(4).toFloat()
        }

        val memoLabel = if (h.note.isNotBlank()) L10n.t("✎ 메모 보기", "✎ View Note") else L10n.t("✎ 메모", "✎ Note")
        val memoBtn = TextView(this).apply {
            text = memoLabel
            setPtSize(8f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(ReaderTheme.EINK_INK)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                dismissHighlightDeleteButton()
                inputDialog(L10n.t("독서 메모", "Reading Note"), initial = h.note, multiline = true) { newNote ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            app.db.highlights().updateNote(h.id, newNote)
                            this@ReaderActivity.highlights = app.db.highlights().forBook(path)
                        }
                        showResult(engine.refreshCurrent(), 0)
                        toast(if (newNote.isNotBlank()) L10n.t("메모가 저장되었습니다", "Note saved") else L10n.t("메모가 삭제되었습니다", "Note deleted"))
                    }
                }
            }
        }

        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false

        val sep = View(this).apply {
            setBackgroundColor(if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_DIVIDER_CONTRAST)
        }

        val delBtn = TextView(this).apply {
            text = L10n.t("✕ 삭제", "✕ Delete")
            setPtSize(8f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(ReaderTheme.EINK_INK)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                dismissHighlightDeleteButton()
                scope.launch {
                    withContext(Dispatchers.IO) { app.db.highlights().delete(h.id) }
                    this@ReaderActivity.highlights = withContext(Dispatchers.IO) { app.db.highlights().forBook(path) }
                    showResult(engine.refreshCurrent(), 0)
                    toast(L10n.t("하이라이트가 삭제되었습니다", "Highlight deleted"))
                }
            }
        }

        container.addView(memoBtn)
        container.addView(sep, LinearLayout.LayoutParams(dp(1), dp(16)))
        if (isLcd) {
            val snsBtn = TextView(this).apply {
                text = "📱 SNS"
                setPtSize(8f)
                typeface = ReaderTheme.uiFontBold
                setTextColor(ReaderTheme.ACCENT_RIDI_BLUE)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    dismissHighlightDeleteButton()
                    val bTitle = (book?.title?.ifBlank { null } ?: engine.bookTitle.ifBlank { null } ?: File(path).nameWithoutExtension)
                    val bAuthor = book?.author?.ifBlank { null } ?: engine.bookAuthor
                    AndroidShareHelper.showSnsShareDialog(this@ReaderActivity, bTitle, bAuthor, h.quote, h.note.ifBlank { null }, isEink = !isLcd)
                }
            }
            container.addView(snsBtn)
            val sep2 = View(this).apply { setBackgroundColor(ReaderTheme.EINK_BORDER) }
            container.addView(sep2, LinearLayout.LayoutParams(dp(1), dp(16)))
        }
        container.addView(delBtn)

        val totalW = if (isLcd) dp(210) else dp(140)
        val totalH = dp(32)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        var targetX = (lastRect.right - dp(24)).toInt()
        var targetY = (lastRect.top - totalH - dp(4)).toInt()

        targetX = targetX.coerceIn(dp(8), screenW - totalW - dp(8))
        targetY = targetY.coerceIn(dp(8), screenH - totalH - dp(8))

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = targetX
            topMargin = targetY
        }
        rootFrame.addView(container, lp)
        activeHighlightDeleteAnchor = container
    }

    override fun onRulerMove(x: Float, y: Float) {
        val rects = engine.sentenceBoundsAt(x.toInt(), y.toInt())
        view.rulerSentenceRects = rects
    }
    override fun onZone(row: Int, col: Int) {
        if (overlayContainer.visibility == View.VISIBLE) {
            hideOverlay()
            return
        }
        perform(app.prefs.zoneAction(row, col))
    }
    override fun onSwipePage(forward: Boolean) { turn(forward) }
    override fun onEdgeStart(side: Int) { edgeBase = if (side == 0) app.prefs.brightness else app.prefs.warmth }

    override fun onTwoFingerVertical(delta: Float) {
        val v = (app.prefs.brightness + delta).coerceIn(0.01f, 1f)
        frontlight.setBrightness(v)
        showHud("밝기 ${(v * 100).toInt()}%")
    }

    override fun onTwoFingerHorizontal(delta: Float) {
        val step = if (delta > 0) 1 else -1
        val n = (app.prefs.fontSizePt + step).coerceIn(5, 36)
        if (n != app.prefs.fontSizePt) {
            app.prefs.fontSizePt = n
            showHud("글꼴 크기 ${n}pt")
            relayoutNow()
        }
    }

    override fun onEdgeMove(side: Int, delta: Float) {
        val v = (edgeBase + delta).coerceIn(0f, 1f)
        if (side == 0) { frontlight.setBrightness(v); showHud("밝기 ${(v * 100).toInt()}%") }
        else { frontlight.setWarmth(v); showHud("색온도 ${(v * 100).toInt()}%") }
    }

    private var currentSelectionRange: TextRange? = null

    override fun onSelectionStart(x: Int, y: Int) {
        val r = engine.hitTest(x, y) ?: return
        currentSelectionRange = r
        view.selectionRects = engine.selectionBounds(r)
    }

    override fun onSelectionDrag(x: Int, y: Int) {
        val startRange = currentSelectionRange ?: return
        val extended = engine.extendSelection(startRange, x, y)
        currentSelectionRange = extended
        view.selectionRects = engine.selectionBounds(extended)
    }

    override fun onSelectionEnd() {
        val r = currentSelectionRange
        if (r == null) {
            view.selectionRects = null
            return
        }
        showSelectionMenu(r)
    }

    override fun onSelectionCancel() {
        currentSelectionRange = null
        view.selectionRects = null
    }

    override fun onLongPress(x: Int, y: Int) {
        val r = engine.hitTest(x, y) ?: return
        currentSelectionRange = r
        view.selectionRects = engine.selectionBounds(r)
        showSelectionMenu(r)
    }

    private fun showSelectionMenu(r: TextRange) {
        val chapterNotes = engine.footnotesForCurrentChapter()
        val hasFootnotes = chapterNotes.isNotEmpty() || r.text.contains(Regex("\\[\\d+]|\\[주\\d+]|\\*"))

        val optSolid = L10n.t("밑줄: 실선 (Solid)", "Underline: Solid")
        val optDotted = L10n.t("밑줄: 점선 (Dotted)", "Underline: Dotted")
        val optBg = L10n.t("하이라이트: 글자 배경색 (Background)", "Highlight: Background")
        val optNote = L10n.t("메모 추가 (Add Note)...", "Add Note...")
        val optCopy = L10n.t("텍스트 복사 (Copy)", "Copy Text")
        val optFootnote = L10n.t("인라인 각주/미주 보기 (Footnote)", "View Footnote")
        val optShare = L10n.t("문장 공유하기 (이메일 · SNS)", "Share Quote (Email · SNS)")
        val optCancel = L10n.t("취소", "Cancel")

        val menuItems = ArrayList<String>()
        menuItems += optSolid
        menuItems += optDotted
        menuItems += optBg
        menuItems += optNote
        menuItems += optCopy
        if (hasFootnotes) menuItems += optFootnote
        menuItems += optShare
        menuItems += optCancel

        listDialog("“" + r.text.take(60) + "”", menuItems) { idx ->
            view.selectionRects = null
            currentSelectionRange = null
            val label = menuItems[idx]
            when (label) {
                optSolid -> saveHighlight(r, 0, "")
                optDotted -> saveHighlight(r, 1, "")
                optBg -> saveHighlight(r, 2, "")
                optNote -> inputDialog(L10n.t("독서 메모 작성", "Write Reading Note"), multiline = true) { saveHighlight(r, 0, it) }
                optCopy -> {
                    try {
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Aether SAGA Selection", r.text)
                        clipboard.setPrimaryClip(clip)
                        toast(L10n.t("클립보드에 복사되었습니다", "Copied to clipboard"))
                    } catch (e: Throwable) {
                        toast(L10n.t("복사 실패: ${e.message}", "Copy failed: ${e.message}"))
                    }
                }
                optFootnote -> {
                    val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
                    val isDark = app.prefs.dark && (strategy.layoutSpec.allowDarkMode || strategy.kind == DisplayKind.LCD_OLED)
                    val body = if (chapterNotes.isNotEmpty()) {
                        chapterNotes.entries.joinToString("\n\n") { "• [${it.key}] ${it.value}" }
                    } else {
                        "선택 문장: ${r.text}"
                    }
                    InlineFootnoteResolver.show(this, L10n.t("각주 / 참고 노트", "Footnotes & Reference Notes"), body, isDark, isLcd = isLcd)
                }
                optShare -> showEmailShareDialog(toEntity(r, 0, ""))
            }
        }
    }

    private fun toEntity(r: TextRange, style: Int, note: String, kind: Int = 0): HighlightEntity {
        val bTitle = book?.title?.ifBlank { null } ?: engine.bookTitle.ifBlank { null } ?: File(path).nameWithoutExtension
        val bAuthor = book?.author ?: engine.bookAuthor
        return HighlightEntity(
            bookPath = path, kind = kind, chapter = r.chapterIndex, startChar = r.startChar, endChar = r.endChar,
            quote = r.text, note = note, style = style, colorTag = app.prefs.colorTag,
            progress = engine.currentLocation().progress, chapterTitle = engine.chapterTitle(r.chapterIndex),
            createdAt = System.currentTimeMillis(),
            bookTitle = bTitle,
            bookAuthor = bAuthor
        )
    }

    private fun saveHighlight(r: TextRange, style: Int, note: String) {
        scope.launch {
            withContext(Dispatchers.IO) { app.db.highlights().insert(toEntity(r, style, note)) }
            highlights = withContext(Dispatchers.IO) { app.db.highlights().forBook(path) }
            showResult(engine.refreshCurrent(), 0)
            toast("하이라이트가 저장되었습니다")
        }
    }

    // ---- actions -------------------------------------------------------------------------

    private fun perform(a: ReaderAction) {
        resetIdle()
        when (a) {
            ReaderAction.NEXT -> turn(true)
            ReaderAction.PREV -> turn(false)
            ReaderAction.FULL_REFRESH -> {
                if (strategy.kind != DisplayKind.LCD_OLED) {
                    pagesSincePurge = 0
                    doFlash(if (strategy.kind == DisplayKind.COLOR_EINK) RefreshMode.FULL_GC16 else RefreshMode.FULL_INVERT)
                }
            }
            ReaderAction.BOOKMARK -> addBookmark()
            ReaderAction.MENU -> toggleOverlay()
            ReaderAction.TOGGLE_DARK -> {
                if (strategy.layoutSpec.allowDarkMode || strategy.kind == DisplayKind.LCD_OLED) {
                    app.prefs.dark = !app.prefs.dark
                    configureEngine()
                    relayoutNow()
                } else {
                    toast("흑백 E-Ink 모드에서는 눈의 피로 및 잔상 방지를 위해 종이 테마가 고정됩니다")
                }
            }
            ReaderAction.NONE -> {}
        }
    }

    private fun findCurrentPageBookmark(): HighlightEntity? {
        val loc = engine.currentLocation()
        val range = (engine as? PagingEngineImpl)?.currentPageCharRange()
        return highlights.find { h ->
            h.kind == 1 && h.chapter == loc.chapterIndex &&
                (range == null || (h.startChar >= range.first && h.startChar < range.second))
        }
    }

    private fun updateBookmarkIndicator() {
        if (!::bookmarkRibbonView.isInitialized) return
        val bm = findCurrentPageBookmark()
        bookmarkRibbonView.visibility = if (bm != null) View.VISIBLE else View.GONE
    }

    private fun addBookmark() {
        val existing = findCurrentPageBookmark()
        if (existing != null) {
            scope.launch {
                withContext(Dispatchers.IO) { app.db.highlights().delete(existing.id) }
                highlights = withContext(Dispatchers.IO) { app.db.highlights().forBook(path) }
                toast(L10n.t("북마크가 해제되었습니다", "Bookmark removed"))
                updateBookmarkIndicator()
            }
            return
        }
        val loc = engine.currentLocation()
        val text = engine.currentPageText().trim().replace(Regex("\\s+"), " ").take(100).ifBlank { L10n.t("페이지 북마크", "Page bookmark") }
        val r = TextRange(loc.chapterIndex, loc.charOffset, loc.charOffset + 1, text)
        scope.launch {
            withContext(Dispatchers.IO) { app.db.highlights().insert(toEntity(r, 0, "", kind = 1)) }
            highlights = withContext(Dispatchers.IO) { app.db.highlights().forBook(path) }
            toast(L10n.t("🔖 북마크가 추가되었습니다", "🔖 Bookmark added"))
            updateBookmarkIndicator()
        }
    }

    // ---- Kindle / Ridi Interactive Sheets & Dialogs --------------------------------------

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setPtSize(8f)
        setTextColor(ReaderTheme.EINK_MUTED)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(2), dp(8), dp(2), dp(4))
    }

    private fun settingsCardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        val bg = if (isLcd) ReaderTheme.EINK_CARD_BG_LIGHT else ReaderTheme.EINK_CARD_BG
        val border = if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_BORDER_CONTRAST
        val strokePx = ReaderTheme.einkStrokePx(this@ReaderActivity, isLcd)
        background = cardDrawable(bg, ReaderTheme.CORNER_CARD_DP, border, strokePx)
    }

    private fun thinDivider(): View {
        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        return if (isLcd) {
            View(this).apply {
                setBackgroundColor(ReaderTheme.EINK_BORDER)
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
            }
        } else {
            DottedDividerView(this, dotColor = 0xFF888888.toInt(), strokePx = dp(1)).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(3)).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
            }
        }
    }

    private fun showTypographySheet(onBack: (() -> Unit)? = null) {
        val p = app.prefs
        val doc = engine.documentStyle
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            enableVisibleScrollbar()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        scroll.addView(box)

        val valueUpdaters = ArrayList<() -> Unit>()
        lateinit var dlg: AlertDialog

        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        val sheetBtnBg = ReaderTheme.einkBtnBg(isLcd)
        val sheetBtnBorder = ReaderTheme.einkBtnBorder(isLcd)
        val sheetStroke = ReaderTheme.einkStrokePx(this, isLcd)

        fun ensureCustomMode() {
            if (p.useDocumentDefaults) p.useDocumentDefaults = false
        }

        // Section 1: 글꼴 및 문서 기준 (Font & Document Style Card)
        box.addView(sectionHeader(L10n.t("글꼴 및 문서 기준", "Font & Document Base")))
        val fontCard = settingsCardContainer()

        // Row 1: 현재 글꼴 + [글꼴 선택] (오른쪽 정렬)
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val fontLabelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fontTitleTv = TextView(this).apply {
            text = L10n.t("현재 글꼴", "Current Font")
            setPtSize(9f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val fontSubTv = TextView(this).apply {
            setPtSize(7.5f)
            setTextColor(ReaderTheme.ACCENT_RIDI_BLUE)
        }
        val updateFontLabel = {
            fontSubTv.text = when (p.fontPath) {
                "" -> L10n.t("기본 (${doc.fontFamily})", "Default (${doc.fontFamily})")
                "SYSTEM_SERIF" -> L10n.t("시스템 명조 (Serif)", "System Serif")
                "SYSTEM_SANS" -> L10n.t("시스템 고딕 (Sans-Serif)", "System Sans-Serif")
                else -> {
                    val fn = File(p.fontPath).nameWithoutExtension.lowercase()
                    when {
                        fn.contains("carlito") -> "Carlito"
                        fn.contains("arimo") -> "Arimo"
                        fn.contains("literata") -> "Literata"
                        fn.contains("inter") -> "Inter"
                        fn.contains("ridibatang") || fn.contains("ridi") -> L10n.t("리디바탕 (RIDI Batang)", "RIDI Batang")
                        fn.contains("gounbam") -> L10n.t("카페24 고운밤", "Cafe24 Gounbam")
                        else -> File(p.fontPath).nameWithoutExtension
                    }
                }
            }
        }
        updateFontLabel()
        valueUpdaters += updateFontLabel
        fontLabelCol.addView(fontTitleTv)
        fontLabelCol.addView(fontSubTv)

        val changeFontBtn = pillButton(L10n.t("글꼴 선택", "Select Font"), ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
            dlg.dismiss()
            pickFont()
        }
        fontRow.addView(fontLabelCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        fontRow.addView(changeFontBtn)
        fontCard.addView(fontRow)
        fontCard.addView(thinDivider())

        // Row 2: 외부 폰트 추가 + [+ 폰트 추가] (오른쪽 정렬)
        val addFontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val addFontLabel = TextView(this).apply {
            text = L10n.t("사용자 폰트 추가 (.ttf / .otf)", "Add Custom Font (.ttf / .otf)")
            setPtSize(9f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val addFontBtn = pillButton(L10n.t("+ 폰트 추가", "+ Add Font"), Color.WHITE, ReaderTheme.ACCENT_KINDLE) {
            dlg.dismiss()
            openFontPicker()
        }
        addFontRow.addView(addFontLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        addFontRow.addView(addFontBtn)
        fontCard.addView(addFontRow)
        fontCard.addView(thinDivider())

        // Row 3: TXT 문서 인코딩 + [인코딩 선택] (오른쪽 정렬)
        val encRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val encLabel = TextView(this).apply {
            text = L10n.t("TXT 문서 인코딩", "TXT Document Encoding")
            setPtSize(9f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val encBtn = pillButton(p.txtEncoding, ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
            dlg.dismiss()
            pickEncoding()
        }
        encRow.addView(encLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        encRow.addView(encBtn)
        fontCard.addView(encRow)
        fontCard.addView(thinDivider())

        // Row 4: 문서 원본 스타일 따르기 + 토글 스위치 (오른쪽 정렬)
        val docModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val docModeLabel = TextView(this).apply {
            text = L10n.t("문서 원본 스타일 따르기", "Respect Original Book Styles")
            setPtSize(9f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        lateinit var docModeSwitch: ToggleSwitchView
        docModeSwitch = toggleSwitch(p.useDocumentDefaults) {
            p.useDocumentDefaults = !p.useDocumentDefaults
            valueUpdaters.forEach { it() }
            relayoutNow()
        }
        valueUpdaters += {
            docModeSwitch.isChecked = p.useDocumentDefaults
        }
        docModeRow.addView(docModeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        docModeRow.addView(docModeSwitch)
        fontCard.addView(docModeRow)
        box.addView(fontCard)

        // Section 2: 조판 수치 상세 조절 (Typography Steppers Card)
        box.addView(sectionHeader(L10n.t("조판 수치 상세 조절", "Typography & Layout Settings")))
        val stepCard = settingsCardContainer()

        fun addStepperRow(
            label: String,
            subLabel: String,
            isLast: Boolean = false,
            valueProvider: () -> String,
            onMinus: () -> Unit,
            onPlus: () -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val labelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val titleTv = TextView(this).apply {
                text = label
                setPtSize(9f)
                setTextColor(ReaderTheme.EINK_INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            val subTv = TextView(this).apply {
                text = subLabel
                setPtSize(7.5f)
                setTextColor(ReaderTheme.EINK_MUTED)
            }
            labelCol.addView(titleTv)
            labelCol.addView(subTv)

            val valTv = TextView(this).apply {
                text = valueProvider()
                setPtSize(8.5f)
                setTextColor(ReaderTheme.ACCENT_RIDI_BLUE)
                typeface = ReaderTheme.uiFontBold
                gravity = Gravity.CENTER
                minWidth = dp(72)
            }
            val updateVal = { valTv.text = valueProvider() }
            valueUpdaters += updateVal

            val minusBtn = pillButton(" − ", ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
                onMinus()
                valueUpdaters.forEach { it() }
            }.apply { setPadding(dp(10), dp(5), dp(10), dp(5)) }
            val plusBtn = pillButton(" + ", ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
                onPlus()
                valueUpdaters.forEach { it() }
            }.apply { setPadding(dp(10), dp(5), dp(10), dp(5)) }
            row.addView(labelCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            row.addView(minusBtn)
            row.addView(valTv)
            row.addView(plusBtn)
            stepCard.addView(row)
            if (!isLast) stepCard.addView(thinDivider())
        }

        addStepperRow(
            L10n.t("글자 크기", "Font Size"), L10n.t("기본 8pt (4 ~ 36pt)", "Default 8pt (4 ~ 36pt)"), false,
            { "${p.fontSizePt} pt" },
            { if (p.fontSizePt > 4) { p.fontSizePt -= 1; relayoutNow() } },
            { if (p.fontSizePt < 36) { p.fontSizePt += 1; relayoutNow() } }
        )
        addStepperRow(
            L10n.t("줄 간격", "Line Spacing"), L10n.t("기본 2.0배 (1.0 ~ 2.5배)", "Default 2.0x (1.0 ~ 2.5x)"), false,
            { if (p.useDocumentDefaults) L10n.t("원본(${"%.1f".format(doc.lineHeight)})", "Doc (${"%.1f".format(doc.lineHeight)})") else L10n.t("%.1f 배".format(p.lineHeight), "%.1fx".format(p.lineHeight)) },
            { ensureCustomMode(); if (p.lineHeight > 1.05f) { p.lineHeight = ((p.lineHeight * 10).toInt() - 1) / 10f; relayoutNow() } },
            { ensureCustomMode(); if (p.lineHeight < 2.5f) { p.lineHeight = ((p.lineHeight * 10).toInt() + 1) / 10f; relayoutNow() } }
        )
        addStepperRow(
            L10n.t("문단 간격", "Paragraph Spacing"), L10n.t("기본 2dp (0 ~ 28dp)", "Default 2dp (0 ~ 28dp)"), false,
            { if (p.useDocumentDefaults) L10n.t("원본(${doc.paragraphSpacingDp}dp)", "Doc (${doc.paragraphSpacingDp}dp)") else "${p.paraSpacingDp} dp" },
            { ensureCustomMode(); if (p.paraSpacingDp > 0) { p.paraSpacingDp -= 1; relayoutNow() } },
            { ensureCustomMode(); if (p.paraSpacingDp < 28) { p.paraSpacingDp += 1; relayoutNow() } }
        )
        addStepperRow(
            L10n.t("페이지 여백", "Side Margins"), L10n.t("기본 20dp (0 ~ 64dp)", "Default 20dp (0 ~ 64dp)"), false,
            { if (p.useDocumentDefaults) L10n.t("원본(${doc.marginHorizontalDp}dp)", "Doc (${doc.marginHorizontalDp}dp)") else "${p.marginDp} dp" },
            { ensureCustomMode(); if (p.marginDp >= 2) { p.marginDp -= 2; relayoutNow() } },
            { ensureCustomMode(); if (p.marginDp <= 64) { p.marginDp += 2; relayoutNow() } }
        )
        addStepperRow(
            L10n.t("글자 굵기", "Font Weight"), L10n.t("기본 1단계 (0 ~ 10단계)", "Default Level 1 (0 ~ 10)"), true,
            { L10n.t("${p.boldness} 단계", "Level ${p.boldness}") },
            { if (p.boldness > 0) { p.boldness -= 1; relayoutNow() } },
            { if (p.boldness < 10) { p.boldness += 1; relayoutNow() } }
        )
        box.addView(stepCard)

        // Reset Action Button at Bottom
        val resetBtn = pillButton(
            L10n.t("↺ 기본 설정 초기화 (8pt · 2.0배 · 문단 2dp · 여백 20dp)", "↺ Reset to Defaults (8pt · 2.0x · Para 2dp · Margin 20dp)"),
            ReaderTheme.EINK_INK,
            sheetBtnBg,
            sheetBtnBorder,
            sheetStroke
        ) {
            p.resetToDefaultStyle()
            valueUpdaters.forEach { it() }
            relayoutNow()
            toast(L10n.t("기본 뷰어 설정(8pt, 2.0배, 2dp, 20dp, 굵기 1단계)으로 복원되었습니다", "Restored to default viewer settings"))
        }
        box.addView(resetBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        dlg = AlertDialog.Builder(this)
            .setTitle(L10n.t("Aa 글자 스타일 설정", "Aa Typography Settings"))
            .setView(scroll)
            .create()
        dlg.showWithOptimalWidth(this, enforceBalancedMargins = true, onBack = onBack)
    }

    private fun pickEncoding() {
        val options = listOf(
            "AUTO" to "자동 감지 (UTF-8 / CP949 / UTF-16)",
            "UTF-8" to "UTF-8 (표준 유니코드)",
            "x-windows-949" to "한국어 완성형 확장 (CP949 / EUC-KR)",
            "EUC-KR" to "한국어 EUC-KR",
            "Shift_JIS" to "일본어 (Shift-JIS)"
        )
        listDialog("TXT 문서 인코딩 선택", options.map { it.second }) { idx ->
            app.prefs.txtEncoding = options[idx].first
            relayoutNow(reopenContainer = true)
            toast("인코딩 변경: ${options[idx].second}")
        }
    }

    private fun showSearchDialog() {
        if (engine.isImageBased) {
            toast(L10n.t("PDF/이미지 문서는 페이지 슬라이더로 이동할 수 있습니다", "PDF/image books can be navigated via page slider"))
            return
        }
        inputDialog(L10n.t("본문 키워드 검색", "Search Keyword in Book"), "") { q ->
            val query = q.trim()
            if (query.isBlank()) return@inputDialog
            scope.launch {
                loadingText.text = L10n.t("본문에서 \"$query\" 검색 중...", "Searching for \"$query\" in book...")
                loadingCard.visibility = View.VISIBLE
                val matches = engine.search(query, 50)
                loadingCard.visibility = View.GONE
                if (matches.isEmpty()) {
                    toast(L10n.t("검색 결과가 없습니다: \"$query\"", "No matches found: \"$query\""))
                } else {
                    showSearchResultsDialog(matches, query)
                }
            }
        }
    }

    private fun showSearchResultsDialog(matches: List<SearchMatch>, query: String) {
        lateinit var dlg: AlertDialog
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.EINK_PAPER)
        }

        // Header container (검색 결과 건수 + 우측 X 닫기 버튼)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(16), dp(14), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = L10n.t("검색 결과 (${matches.size}건) - \"$query\"", "Search Results (${matches.size}) - \"$query\"")
            setPtSize(10f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(ReaderTheme.EINK_INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })

        val closeBtn = TextView(this).apply {
            text = "✕"
            setPtSize(12f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(ReaderTheme.EINK_INK)
            gravity = Gravity.CENTER
            val sz = dp(34)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                setColor(0x00000000)
                cornerRadius = sz / 2f
            }
            setOnClickListener { dlg.dismiss() }
        }
        header.addView(closeBtn)
        root.addView(header)

        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false

        // 구분선
        val dividerLine = View(this).apply {
            setBackgroundColor(if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_DIVIDER_CONTRAST)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (isLcd) dp(1).coerceAtLeast(1) else dp(2))
        }
        root.addView(dividerLine)

        // 검색 결과 리스트뷰
        val listView = ListView(this).apply {
            divider = ColorDrawable(if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_DIVIDER_CONTRAST)
            dividerHeight = if (isLcd) dp(1).coerceAtLeast(1) else dp(2)
            enableVisibleScrollbar()
        }
        val adapter = object : ArrayAdapter<SearchMatch>(this, android.R.layout.simple_list_item_2, matches) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val m = getItem(position) ?: return v
                val text1 = v.findViewById<TextView>(android.R.id.text1)
                val text2 = v.findViewById<TextView>(android.R.id.text2)
                text1?.apply {
                    text = m.chapterTitle
                    setPtSize(8f)
                    typeface = ReaderTheme.uiFontBold
                    setTextColor(ReaderTheme.EINK_MUTED)
                }
                text2?.apply {
                    text = m.snippet
                    setPtSize(9f)
                    typeface = ReaderTheme.uiFontRegular
                    setTextColor(ReaderTheme.EINK_INK)
                }
                v.setPadding(dp(18), dp(10), dp(18), dp(10))
                return v
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val m = matches[position]
            dlg.dismiss()
            pushHistoryAndJump(BookLocation(m.chapterIndex, m.charOffset, 0f))
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dlg = AlertDialog.Builder(this)
            .setView(root)
            .create()
        dlg.showWithOptimalWidth(this)
    }

    private fun showTocAndNotesDialog() {
        val hlCount = highlights.count { it.kind == 0 }
        val bmCount = highlights.count { it.kind == 1 }
        val bTitle = (book?.title?.ifBlank { null } ?: engine.bookTitle.ifBlank { null } ?: File(path).nameWithoutExtension)
        val bAuthor = book?.author?.ifBlank { null } ?: engine.bookAuthor
        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        val options = mutableListOf(
            L10n.t("📑 목차 이동 (총 ${engine.chapters.size}개 섹션)", "📑 Table of Contents (${engine.chapters.size} sections)"),
            L10n.t("🖍 하이라이트 및 북마크 ($hlCount / $bmCount)", "🖍 Highlights & Bookmarks ($hlCount / $bmCount)"),
            L10n.t("📦 하이라이트 압축파일(ZIP) 이메일 전송", "📦 Export Highlights (ZIP) via Email"),
            L10n.t("📢 독서 노트 공유 (이메일 · SNS)", "📢 Share Reading Notes (Email · SNS)"),
            L10n.t("📷 독서 카드 저장 · SNS 공유", "📷 Save Card & Share to SNS"),
            L10n.t("🎴 하이라이트 플래시카드 복습", "🎴 Flashcard Review")
        )

        listDialog(L10n.t("목차 및 독서 노트", "Contents & Reading Notes"), options) { i ->
            val opt = options[i]
            when {
                opt.startsWith("📑") -> showToc(onBack = { showTocAndNotesDialog() })
                opt.startsWith("🖍") -> browseHighlights(onBack = { showTocAndNotesDialog() })
                opt.startsWith("📦") -> showEmailHighlightsZipDialog(app, bTitle, bAuthor, highlights, onBack = { showTocAndNotesDialog() })
                opt.startsWith("📢") -> showEmailShareDialog(null, onBack = { showTocAndNotesDialog() })
                opt.startsWith("📷") || opt.startsWith("📱") -> {
                    val sampleQuote = highlights.firstOrNull { it.kind == 0 }?.quote ?: L10n.t("즐거운 독서의 여정", "A pleasant journey of reading")
                    val sampleMemo = highlights.firstOrNull { it.kind == 0 && it.note.isNotBlank() }?.note
                    AndroidShareHelper.showSnsShareDialog(this, bTitle, bAuthor, sampleQuote, sampleMemo, isEink = !isLcd, onBack = { showTocAndNotesDialog() })
                }
                opt.startsWith("🎴") -> showFlashcardsDialog(onBack = { showTocAndNotesDialog() })
            }
        }
    }

    private fun frontlightAndDisplaySheet(onBack: (() -> Unit)? = null) {
        val p = app.prefs
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            enableVisibleScrollbar()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        scroll.addView(box)
        lateinit var dlg: AlertDialog

        val isLcdCapable = (strategy.kind == DisplayKind.LCD_OLED)
        val isLcd = isLcdCapable
        val sheetBtnBg = ReaderTheme.einkBtnBg(isLcd)
        val sheetBtnBorder = ReaderTheme.einkBtnBorder(isLcd)
        val sheetStroke = ReaderTheme.einkStrokePx(this, isLcd)

        if (isLcdCapable) {
            // 1. 조명 및 밝기 조절 (LCD / OLED 전용)
            box.addView(sectionHeader(L10n.t("화면 밝기 조절 (LCD / OLED)", "Brightness Control (LCD / OLED)")))
            val lightCard = settingsCardContainer()

            val seekBars = ArrayList<SeekBar>()
            val valTvs = ArrayList<TextView>()

            // System Default Toggle Row
            val sysRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val sysLabelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val sysTitle = TextView(this).apply {
                text = L10n.t("⚙️ 시스템 기본 조명/밝기 따르기", "⚙️ Follow System Brightness")
                setPtSize(9f)
                setTextColor(ReaderTheme.EINK_INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            val sysDesc = TextView(this).apply {
                text = L10n.t("기기의 시스템 자동 밝기 및 표준 설정을 따름", "Use device automatic brightness setting")
                setPtSize(7.5f)
                setTextColor(ReaderTheme.EINK_MUTED)
            }
            sysLabelCol.addView(sysTitle)
            sysLabelCol.addView(sysDesc)

            lateinit var sysSw: ToggleSwitchView
            fun updateSliderVisuals(manualEnabled: Boolean) {
                seekBars.forEach {
                    it.alpha = if (manualEnabled) 1.0f else 0.45f
                }
                valTvs.forEach {
                    it.alpha = if (manualEnabled) 1.0f else 0.45f
                }
            }

            val toggleSystemBrightness = {
                val newVal = !p.followSystemBrightness
                p.followSystemBrightness = newVal
                sysSw.isChecked = newVal
                if (newVal) {
                    frontlight.setSystemDefault()
                    showHud(L10n.t("시스템 기본 밝기 적용", "System brightness applied"))
                } else {
                    frontlight.setBrightness(p.brightness, userAction = false)
                    frontlight.setWarmth(p.warmth, userAction = false)
                    showHud(L10n.t("앱 사용자 지정 밝기 적용", "Custom brightness applied"))
                }
                updateSliderVisuals(!newVal)
            }

            sysSw = toggleSwitch(p.followSystemBrightness) {
                toggleSystemBrightness()
            }
            sysRow.setOnClickListener { toggleSystemBrightness() }
            sysRow.addView(sysLabelCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            sysRow.addView(sysSw)
            lightCard.addView(sysRow)
            lightCard.addView(thinDivider())

            fun addSliderRow(title: String, initialPct: Int, isLast: Boolean, onChanged: (Int) -> Unit) {
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val titleTv = TextView(this).apply {
                    text = title
                    setPtSize(9f)
                    setTextColor(ReaderTheme.EINK_INK)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                val valTv = TextView(this).apply {
                    text = "${initialPct}%"
                    setPtSize(9f)
                    setTextColor(ReaderTheme.ACCENT_RIDI_BLUE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                valTvs.add(valTv)
                header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                header.addView(valTv)
                lightCard.addView(header)

                val seek = SeekBar(this).apply {
                    max = 100
                    progress = initialPct.coerceIn(0, 100)
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                            valTv.text = "${v}%"
                            if (fromUser) {
                                if (p.followSystemBrightness) {
                                    p.followSystemBrightness = false
                                    sysSw.isChecked = false
                                    updateSliderVisuals(true)
                                }
                                onChanged(v)
                            }
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {
                            if (p.followSystemBrightness) {
                                p.followSystemBrightness = false
                                sysSw.isChecked = false
                                updateSliderVisuals(true)
                            }
                        }
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                seekBars.add(seek)
                lightCard.addView(seek)
                if (!isLast) lightCard.addView(thinDivider())
            }

            addSliderRow(L10n.t("💡 화면 밝기 (Brightness)", "💡 Brightness"), (p.brightness * 100).toInt(), false) {
                frontlight.setBrightness((it.coerceAtLeast(1)) / 100f)
            }
            addSliderRow(L10n.t("🕯 색온도 / 난색 조명 (Warmth)", "🕯 Warmth / Blue Light Filter"), (p.warmth * 100).toInt(), true) {
                frontlight.setWarmth(it / 100f)
            }
            updateSliderVisuals(!p.followSystemBrightness)
            box.addView(lightCard)
        } else {
            // 1. E-Ink 하드웨어 조명 안내 및 시스템 설정 연동 (기기 시스템 조명 100% 위임)
            box.addView(sectionHeader(L10n.t("E-Ink 프론트라이트 조명 안내", "E-Ink Frontlight Guide")))
            val einkLightCard = settingsCardContainer()
            val infoRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val titleTv = TextView(this).apply {
                text = L10n.t("💡 전자잉크 하드웨어 조명 최적화", "💡 E-Ink Frontlight Optimization")
                setPtSize(9f)
                setTextColor(ReaderTheme.EINK_INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            val descTv = TextView(this).apply {
                text = L10n.t("전자종이의 최대 흑백 대비와 선명도를 유지하기 위해 기기 시스템 조명을 100% 따릅니다. 조명 밝기 및 색온도는 기기 상단 퀵패널 또는 시스템 설정에서 조절하실 수 있습니다.", "To maintain maximum contrast and clarity on electronic paper, device system frontlight is followed 100%. Adjust brightness in system quick panel.")
                setPtSize(7.5f)
                setTextColor(ReaderTheme.EINK_MUTED)
                setPadding(0, dp(2), 0, dp(8))
            }
            val openSysBtn = pillButton(L10n.t("⚙ 기기 시스템 조명 설정 열기", "⚙ Open Display Settings"), ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS))
                } catch (_: Throwable) {
                    toast(L10n.t("기기 설정 화면을 열 수 없습니다.", "Cannot open display settings."))
                }
            }
            infoRow.addView(titleTv)
            infoRow.addView(descTv)
            infoRow.addView(openSysBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            einkLightCard.addView(infoRow)
            box.addView(einkLightCard)
        }

        // 2. 화면 방향 및 테마 제어 (우측 정렬 통일)
        box.addView(sectionHeader(if (isLcdCapable) L10n.t("화면 방향 및 테마 제어", "Orientation & Theme") else L10n.t("화면 방향 제어", "Orientation Control")))
        val quickCard = settingsCardContainer()

        // Row 1: 독서 화면 테마 제어 (오직 LCD/OLED 기기에서만 표시, E-Ink는 순수 종이/잉크로 고정 및 은닉)
        if (isLcdCapable) {
            val themeTitle = TextView(this).apply {
                text = L10n.t("🎨 독서 화면 테마 (LCD/OLED 블루라이트 차단 · 야간 모드)", "🎨 Reading Theme (LCD/OLED Night Mode & Eye Care)")
                setPtSize(9f)
                setTextColor(ReaderTheme.EINK_INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(6))
            }
            quickCard.addView(themeTitle)

            val themeRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val themeRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }

            val themeDefs = listOf(
                Triple("WHITE", L10n.t("기본 화이트", "Default White"), themeRow1),
                Triple("SEPIA", L10n.t("눈 보호 웜톤", "Warm Sepia"), themeRow1),
                Triple("DARK", L10n.t("야간 다크", "Night Dark"), themeRow2),
                Triple("OLED_BLACK", L10n.t("OLED 블랙", "OLED Black"), themeRow2)
            )

            val buttonList = mutableListOf<Pair<String, Button>>()
            fun syncButtons() {
                val current = p.readingTheme
                for ((key, btn) in buttonList) {
                    val active = (key == current)
                    val baseLabel = when (key) {
                        "WHITE" -> L10n.t("기본 화이트", "Default White")
                        "SEPIA" -> L10n.t("눈 보호 웜톤", "Warm Sepia")
                        "DARK" -> L10n.t("야간 다크", "Night Dark")
                        "OLED_BLACK" -> L10n.t("OLED 블랙", "OLED Black")
                        else -> ""
                    }
                    val icon = when (key) {
                        "WHITE" -> "📄"
                        "SEPIA" -> "📜"
                        "DARK" -> "🌙"
                        "OLED_BLACK" -> "⚫"
                        else -> ""
                    }
                    btn.text = if (active) "✓ $icon $baseLabel" else "    $icon $baseLabel"
                    btn.setTextColor(if (active) Color.WHITE else ReaderTheme.EINK_INK)
                    btn.background = GradientDrawable().apply {
                        setColor(if (active) ReaderTheme.ACCENT_KINDLE else ReaderTheme.EINK_PAPER)
                        setStroke(dp(1), if (active) ReaderTheme.ACCENT_KINDLE else ReaderTheme.EINK_BORDER)
                        cornerRadius = dp(6).toFloat()
                    }
                }
            }

            for ((tKey, _, parentRow) in themeDefs) {
                val btn = Button(this).apply {
                    setPtSize(8.5f)
                    isAllCaps = false
                    setOnClickListener {
                        p.readingTheme = tKey
                        p.dark = (tKey == "DARK" || tKey == "OLED_BLACK")
                        configureEngine()
                        relayoutNow()
                        syncButtons()
                    }
                }
                buttonList.add(tKey to btn)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (parentRow.childCount > 0) leftMargin = dp(6)
                }
                parentRow.addView(btn, lp)
            }
            syncButtons()

            quickCard.addView(themeRow1)
            quickCard.addView(themeRow2)
            quickCard.addView(thinDivider())
        }

        // Row 2: 화면 회전 전환 (오른쪽 정렬)
        val rotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val rotLabel = TextView(this).apply {
            text = L10n.t("↻ 화면 방향 회전", "↻ Screen Orientation")
            setPtSize(9f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val rotBtn = pillButton(L10n.t("회전 전환", "Rotate"), ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
            val l = orientationMgr.toggleOrientation()
            showHud(L10n.t("화면 회전: $l", "Screen Orientation: $l"))
        }
        lateinit var lockBtn: Button
        lockBtn = pillButton(if (orientationMgr.isLocked) L10n.t("잠금 켜짐", "Locked") else L10n.t("잠금 꺼짐", "Unlocked"), ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
            val locked = orientationMgr.toggleLock()
            lockBtn.text = if (locked) L10n.t("잠금 켜짐", "Locked") else L10n.t("잠금 꺼짐", "Unlocked")
            showHud(orientationMgr.lockLabel())
        }
        rotRow.addView(rotLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        rotRow.addView(rotBtn)
        rotRow.addView(lockBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        quickCard.addView(rotRow)
        quickCard.addView(thinDivider())

        // Row 3: 디스플레이/페이지 전환 효과 (오른쪽 정렬)
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val modeLabel = TextView(this).apply {
            text = L10n.t("🖥 디스플레이 및 기기 해상도", "🖥 Display & Device Resolution")
            setPtSize(9f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val modeBtn = pillButton(L10n.t("설정 변경", "Change"), ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
            dlg.dismiss()
            displayMenu()
        }
        modeRow.addView(modeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        modeRow.addView(modeBtn)
        quickCard.addView(modeRow)

        box.addView(quickCard)

        val isLcdDisplay = (strategy.kind == DisplayKind.LCD_OLED)

        // 3. E-Ink 새로고침 및 절전 타이머 (LCD/OLED에서는 잔상제거 UI를 완전히 숨기고 절전 설정만 표시)
        box.addView(sectionHeader(if (isLcdDisplay) L10n.t("절전 설정", "Power Saving Settings") else L10n.t("E-Ink 새로고침 및 절전 설정", "E-Ink Refresh & Power Saving")))
        val einkCard = settingsCardContainer()

        fun addSimpleStepRow(label: String, isLast: Boolean, valStr: () -> String, onMinus: () -> Unit, onPlus: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val tv = TextView(this).apply {
                text = label
                setPtSize(9f)
                setTextColor(ReaderTheme.EINK_INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val vTv = TextView(this).apply {
                text = valStr()
                setPtSize(8.5f)
                setTextColor(ReaderTheme.ACCENT_RIDI_BLUE)
                typeface = ReaderTheme.uiFontBold
                gravity = Gravity.CENTER
                minWidth = dp(74)
            }
            val mBtn = pillButton(" − ", ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
                onMinus(); vTv.text = valStr()
            }.apply { setPadding(dp(10), dp(5), dp(10), dp(5)) }
            val pBtn = pillButton(" + ", ReaderTheme.EINK_INK, sheetBtnBg, sheetBtnBorder, sheetStroke) {
                onPlus(); vTv.text = valStr()
            }.apply { setPadding(dp(10), dp(5), dp(10), dp(5)) }
            r.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            r.addView(mBtn)
            r.addView(vTv)
            r.addView(pBtn)
            einkCard.addView(r)
            if (!isLast) einkCard.addView(thinDivider())
        }

        if (!isLcdDisplay) {
            addSimpleStepRow(
                L10n.t("잔상 제거(Full Refresh) 주기", "Full Refresh Frequency"), false,
                { L10n.t("${p.refreshEvery} 페이지", "${p.refreshEvery} Pages") },
                { if (p.refreshEvery > 1) p.refreshEvery -= 1 },
                { if (p.refreshEvery < 30) p.refreshEvery += 1 }
            )
        }
        addSimpleStepRow(
            L10n.t("자동 절전 조명 대기 시간", "Auto Sleep Timeout"), true,
            { L10n.t("${p.idleDimMinutes} 분", "${p.idleDimMinutes} Min") },
            { if (p.idleDimMinutes > 1) p.idleDimMinutes -= 1 },
            { if (p.idleDimMinutes < 30) p.idleDimMinutes += 1 }
        )
        box.addView(einkCard)

        dlg = AlertDialog.Builder(this)
            .setTitle(L10n.t("☀ 화면 · 조명 · 회전 설정", "☀ Display · Light · Rotation"))
            .setView(scroll)
            .create()
        dlg.setOnDismissListener {
            frontlight.detachDialogWindow()
        }
        dlg.showWithOptimalWidth(this, enforceBalancedMargins = true, onBack = onBack)
        frontlight.attachDialogWindow(dlg.window)
    }

    private fun showFullMenu() {
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            enableVisibleScrollbar()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        scroll.addView(box)
        lateinit var dlg: AlertDialog

        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        val isDark = app.prefs.dark && isLcd
        val btnBg = ReaderTheme.einkBtnBg(isLcd, isDark)
        val btnBorder = ReaderTheme.einkBtnBorder(isLcd, isDark)
        val btnFg = if (isDark) ReaderTheme.DARK_TEXT else ReaderTheme.EINK_INK
        val btnStrokePx = ReaderTheme.einkStrokePx(this, isLcd)

        fun addCategoryGrid(header: String, pairs: List<Pair<String, () -> Unit>>) {
            box.addView(sectionHeader(header))
            val card = settingsCardContainer()
            var i = 0
            while (i < pairs.size) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                val (label1, act1) = pairs[i]
                val b1 = pillButton(label1, btnFg, btnBg, btnBorder, btnStrokePx) {
                    dlg.dismiss()
                    act1()
                }.apply { gravity = Gravity.CENTER }
                row.addView(b1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = if (i + 1 < pairs.size) dp(6) else 0
                })
                if (i + 1 < pairs.size) {
                    val (label2, act2) = pairs[i + 1]
                    val b2 = pillButton(label2, btnFg, btnBg, btnBorder, btnStrokePx) {
                        dlg.dismiss()
                        act2()
                    }.apply { gravity = Gravity.CENTER }
                    row.addView(b2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                card.addView(row)
                i += 2
            }
            box.addView(card)
        }

        addCategoryGrid(L10n.t("독서 탐색 및 노트", "Navigation & Notes"), listOf(
            L10n.t("📑 목차 이동", "📑 Table of Contents") to { showToc(onBack = { showFullMenu() }) },
            L10n.t("🔍 본문 키워드 검색", "🔍 Search Keyword") to { showSearchDialog() },
            L10n.t("🖍 하이라이트 / 북마크", "🖍 Highlights / Bookmarks") to { exportMenu(onBack = { showFullMenu() }) },
            L10n.t("🎴 플래시카드 복습", "🎴 Flashcard Review") to { showFlashcardsDialog(onBack = { showFullMenu() }) }
        ))

        addCategoryGrid(L10n.t("조판 및 화면 제어", "Typography & Display"), listOf(
            L10n.t("Aa 글자 / 여백 설정", "Aa Typography & Margins") to { showTypographySheet(onBack = { showFullMenu() }) },
            L10n.t("☀ 조명 / 디스플레이", "☀ Brightness & Display") to { frontlightAndDisplaySheet(onBack = { showFullMenu() }) },
            L10n.t("↻ 화면 회전 (가로/세로)", "↻ Rotate Screen") to { val l = orientationMgr.toggleOrientation(); showHud(L10n.t("화면 회전: $l", "Screen Orientation: $l")) },
            L10n.t("🔒 회전 잠금 켜기/끄기", "🔒 Toggle Rotation Lock") to { orientationMgr.toggleLock(); showHud(orientationMgr.lockLabel()) },
            L10n.t("⚡ 독서 보조 도구", "⚡ Reading Tools") to { showAdvancedFeaturesMenu(onBack = { showFullMenu() }) }
        ))

        addCategoryGrid(L10n.t("기기 입력 및 클라우드", "Input & Cloud"), listOf(
            L10n.t("⌨ 물리 버튼 매핑", "⌨ Key Mapping") to { remapKeys(onBack = { showFullMenu() }) },
            L10n.t("🖐 3x3 터치 영역 설정", "🖐 3x3 Touch Zones") to { remapZones(onBack = { showFullMenu() }) },
            L10n.t("🔊 TTS 음성 낭독", "🔊 TTS Read Aloud") to { speakToggle() },
            L10n.t("☁ WebDAV 동기화", "☁ WebDAV Sync") to { webdavSettings(onBack = { showFullMenu() }) },
            L10n.t("🎨 하이라이트 태그", "🎨 Highlight Tags") to { listDialog(L10n.t("하이라이트 태그 선택", "Select Highlight Tag"), listOf(L10n.t("태그 1 (기본)", "Tag 1 (Default)"), L10n.t("태그 2", "Tag 2"), L10n.t("태그 3", "Tag 3"), L10n.t("태그 4", "Tag 4")), onBack = { showFullMenu() }) { app.prefs.colorTag = it } },
            L10n.t("← 서재로 나가기", "← Exit to Library") to { finish() }
        ))

        dlg = AlertDialog.Builder(this)
            .setTitle(L10n.t("⚙ 전체 리더 설정 및 도구", "⚙ Reader Settings & Tools"))
            .setView(scroll)
            .create()
        dlg.showWithOptimalWidth(this, enforceBalancedMargins = true)
    }

    private fun showComicSettingsDialog() {
        val dirLabel = when (app.prefs.comicDirection) {
            "RTL" -> L10n.t("우→좌 (일본 만화/Manga)", "Right to Left (Manga)")
            "LTR" -> L10n.t("좌→우 (웹툰/서구 만화)", "Left to Right (Comics)")
            else -> L10n.t("세로 연속 스크롤 (Webtoon)", "Vertical Scroll (Webtoon)")
        }
        val splitLabel = when (app.prefs.comicSplitMode) {
            "AUTO_SPLIT" -> L10n.t("2페이지 양면 자동 분할", "Auto Split 2-Page")
            "FIT_WIDTH" -> L10n.t("너비 맞춤", "Fit Width")
            else -> L10n.t("화면 맞춤", "Fit Screen")
        }
        val contrastLabel = if (app.prefs.comicContrastBoost) L10n.t("켜짐 (선명화 + 대비 향상)", "On (Sharpen & Contrast)") else L10n.t("꺼짐 (원본 그대로)", "Off (Original)")

        listDialog(L10n.t("🎨 만화책 뷰어 특화 설정", "🎨 Manga / Comic Settings"), listOf(
            L10n.t("읽기 방향: $dirLabel", "Direction: $dirLabel"),
            L10n.t("양면 분할 모드: $splitLabel", "Split Mode: $splitLabel"),
            L10n.t("E-Ink 선명화 필터 (대비 강화): $contrastLabel", "E-Ink Contrast Boost: $contrastLabel")
        )) { i ->
            when (i) {
                0 -> {
                    listDialog(L10n.t("만화 읽기 방향 선택", "Select Reading Direction"), listOf(
                        L10n.t("우→좌 넘김 (일본 만화 / 우철 Manga)", "Right to Left (RTL / Manga)"),
                        L10n.t("좌→우 넘김 (한국 만화 / 서구 코믹스)", "Left to Right (LTR / Western)")
                    )) { d ->
                        app.prefs.comicDirection = if (d == 0) "RTL" else "LTR"
                        toast(L10n.t("읽기 방향이 변경되었습니다: ${if (d == 0) "우→좌" else "좌→우"}", "Reading direction changed: ${if (d == 0) "RTL" else "LTR"}"))
                    }
                }
                1 -> {
                    listDialog(L10n.t("양면 만화 분할 설정", "Select Split Mode"), listOf(
                        L10n.t("스마트 양면 분할 (와이드 페이지 2분할)", "Smart Split (2-Page Spread)"),
                        L10n.t("화면 너비에 맞춤", "Fit to Width"),
                        L10n.t("화면 전체에 맞춤", "Fit to Screen")
                    )) { s ->
                        app.prefs.comicSplitMode = when (s) {
                            0 -> "AUTO_SPLIT"
                            1 -> "FIT_WIDTH"
                            else -> "FIT_HEIGHT"
                        }
                        relayoutNow()
                    }
                }
                2 -> {
                    app.prefs.comicContrastBoost = !app.prefs.comicContrastBoost
                    toast(L10n.t("E-Ink 선명화 대비 필터: ${if (app.prefs.comicContrastBoost) "켜짐" else "꺼짐"}", "E-Ink Contrast Filter: ${if (app.prefs.comicContrastBoost) "On" else "Off"}"))
                    relayoutNow()
                }
            }
        }
    }

    private fun resolveBookMeta(): Map<String, String> {
        val b = book
        val resolvedTitle = (engine.bookTitle.ifBlank { b?.title ?: "" }).ifBlank {
            File(path).nameWithoutExtension
        }
        var author = engine.bookAuthor.ifBlank { b?.author ?: "" }
        var translator = engine.bookTranslator
        var publisher = engine.bookPublisher
        var year = engine.bookPublishYear

        if (author.isBlank() || translator.isBlank() || publisher.isBlank() || year.isBlank()) {
            val sample = try {
                val sb = StringBuilder()
                val count = engine.chapters.size.coerceAtMost(3)
                for (i in 0 until count) {
                    sb.append(engine.chapterText(i)).append("\n")
                    if (sb.length > 2500) break
                }
                sb.take(3000).toString()
            } catch (_: Throwable) { "" }

            if (author.isBlank()) {
                Regex("""(?:지은이|저자|저\s*자|지\s*은\s*이)\s*[:：]\s*([^\r\n,]+)""").find(sample)?.let {
                    author = it.groupValues[1].trim()
                }
            }
            if (translator.isBlank()) {
                Regex("""(?:옮긴이|역자|번역|옮\s*긴\s*이)\s*[:：]\s*([^\r\n,]+)""").find(sample)?.let {
                    translator = it.groupValues[1].trim()
                }
            }
            if (publisher.isBlank()) {
                Regex("""(?:출판사|펴낸곳|도서출판|발행처)\s*[:：]\s*([^\r\n,]+)""").find(sample)?.let {
                    publisher = it.groupValues[1].trim()
                }
            }
            if (year.isBlank()) {
                Regex("""(?:발행|출간|초판|인쇄|발행일)\s*[:：]?\s*(?:[^\r\n]*?)?\b(19\d{2}|20\d{2})\b""").find(sample)?.let {
                    year = it.groupValues[1].trim()
                }
            }
        }

        return mapOf(
            "title" to resolvedTitle,
            "author" to author,
            "translator" to translator,
            "publisher" to publisher,
            "year" to year
        )
    }

    private fun showToc(onBack: (() -> Unit)? = null) {
        val meta = resolveBookMeta()
        val bookTitle = meta["title"] ?: ""
        val author = meta["author"]?.ifBlank { null } ?: "정보 없음"
        val translator = meta["translator"]?.ifBlank { null } ?: "-"
        val publisher = meta["publisher"]?.ifBlank { null } ?: "-"
        val rawYear = meta["year"]?.ifBlank { null }
        val year = rawYear?.let { if (it.endsWith("년")) it else "${it}년" } ?: "-"

        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        lateinit var dlg: AlertDialog

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.EINK_PAPER)
        }

        // Header container (뒤로가기 버튼 + 우측 X 닫기 버튼, 아래에 책 제목 및 메타 정보)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(10))
        }

        if (onBack != null) {
            val navRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val backBtn = TextView(this).apply {
                text = L10n.t("◀ 뒤로", "◀ Back")
                setPtSize(8.5f)
                setTextColor(ReaderTheme.EINK_INK)
                typeface = ReaderTheme.uiFontBold
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(12), dp(4))
                background = GradientDrawable().apply {
                    setColor(if (isLcd) 0x12000000 else ReaderTheme.EINK_CARD_BG)
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), ReaderTheme.EINK_BORDER)
                }
                setOnClickListener {
                    dlg.dismiss()
                    onBack()
                }
            }

            val navSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }

            val closeBtn = TextView(this).apply {
                text = "✕"
                setPtSize(12f)
                typeface = ReaderTheme.uiFontBold
                setTextColor(ReaderTheme.EINK_INK)
                gravity = Gravity.CENTER
                val sz = dp(34)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                background = GradientDrawable().apply {
                    setColor(0x00000000)
                    cornerRadius = sz / 2f
                }
                setOnClickListener { dlg.dismiss() }
            }

            navRow.addView(backBtn)
            navRow.addView(navSpacer)
            navRow.addView(closeBtn)
            header.addView(navRow)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                if (onBack != null) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (onBack != null) 0f else 1f
            ).apply {
                if (onBack != null) {
                    topMargin = dp(6)
                } else {
                    rightMargin = dp(8)
                }
            }
        }

        // 1. 책 제목 (굵은 글씨)
        val titleView = TextView(this).apply {
            text = bookTitle
            setPtSize(10.5f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(ReaderTheme.EINK_INK)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textCol.addView(titleView)

        // 2. 작가와 옮긴이, 출판사, 출시 연도 정보
        val metaView = TextView(this).apply {
            val aLbl = L10n.t("작가", "Author")
            val tLbl = L10n.t("옮긴이", "Translator")
            val pLbl = L10n.t("출판사", "Publisher")
            val yLbl = L10n.t("출시 연도", "Year")
            text = "$aLbl: $author  •  $tLbl: $translator\n$pLbl: $publisher  •  $yLbl: $year"
            setPtSize(7.5f)
            typeface = ReaderTheme.uiFontRegular
            setTextColor(ReaderTheme.EINK_MUTED)
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setPadding(0, dp(4), 0, 0)
        }
        textCol.addView(metaView)

        if (onBack == null) {
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val closeBtn = TextView(this).apply {
                text = "✕"
                setPtSize(12f)
                typeface = ReaderTheme.uiFontBold
                setTextColor(ReaderTheme.EINK_INK)
                gravity = Gravity.CENTER
                val sz = dp(34)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    gravity = Gravity.TOP
                }
                background = GradientDrawable().apply {
                    setColor(0x00000000)
                    cornerRadius = sz / 2f
                }
                setOnClickListener { dlg.dismiss() }
            }
            topRow.addView(textCol)
            topRow.addView(closeBtn)
            header.addView(topRow)
        } else {
            header.addView(textCol)
        }
        root.addView(header)

        // 구분선 (e-INK 기기는 양쪽 여백이 있는 직선, LCD는 실선)
        val dividerLine = if (!isLcd) {
            View(this).apply {
                setBackgroundColor(ReaderTheme.EINK_DIVIDER_CONTRAST)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
                    setMargins(dp(18), dp(3), dp(18), dp(3))
                }
            }
        } else {
            View(this).apply {
                setBackgroundColor(ReaderTheme.EINK_BORDER)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
            }
        }
        root.addView(dividerLine)

        // 목차 데이터 준비: 더미 챕터("챕터 N") 구분 제거 및 실제 목차만 필터링
        data class TocEntry(val chapterIndex: Int, val pageNum: Int, val title: String)

        fun isDummyTitle(t: String): Boolean {
            val s = t.trim()
            return s.isBlank() || s.matches(Regex("""^(?:챕터|Chapter|섹션|Section|장|Ch\.?)\s*\d+$""", RegexOption.IGNORE_CASE))
        }

        fun cleanTitle(t: String): String {
            val cleaned = t.trim()
                .replace(Regex("""^(?:챕터|Chapter|섹션|Section|장|Ch\.?)\s*\d+[\s:.\-·~]+""", RegexOption.IGNORE_CASE), "")
                .trim()
            return cleaned.ifBlank { t.trim() }
        }

        val allChapters = engine.chapters
        val hasRealTitles = allChapters.any { !isDummyTitle(it.title) }

        val tocList = allChapters.mapIndexedNotNull { idx, ch ->
            if (hasRealTitles && isDummyTitle(ch.title)) {
                null // 실제 목차가 있는 도서인 경우, 불필요한 자동생성 챕터 구분 항목은 제거
            } else {
                val startPage = engine.chapterStartPage(idx)
                val clean = cleanTitle(ch.title)
                TocEntry(idx, startPage, clean)
            }
        }

        val curLoc = engine.currentLocation()

        val einkDottedDivider = if (!isLcd) {
            object : Drawable() {
                private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF888888.toInt()
                    strokeWidth = dp(1).toFloat().coerceAtLeast(1f)
                    style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(dp(3).toFloat(), dp(3).toFloat()), 0f)
                }
                private val path = Path()

                override fun draw(canvas: Canvas) {
                    val b = bounds
                    val y = b.exactCenterY()
                    val startX = b.left + dp(18).toFloat()
                    val endX = b.right - dp(18).toFloat()
                    if (endX > startX) {
                        path.reset()
                        path.moveTo(startX, y)
                        path.lineTo(endX, y)
                        canvas.drawPath(path, dashPaint)
                    }
                }

                override fun setAlpha(alpha: Int) { dashPaint.alpha = alpha }
                override fun setColorFilter(colorFilter: ColorFilter?) { dashPaint.colorFilter = colorFilter }
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
        } else null

        // 목차 ListView (오른쪽에 상시 선명한 스크롤바 표시)
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            enableVisibleScrollbar(isDark = false, widthDp = 7)
            isFastScrollEnabled = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                isFastScrollAlwaysVisible = true
            }
            if (!isLcd) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                divider = einkDottedDivider
                dividerHeight = dp(3)
            } else {
                divider = ColorDrawable(ReaderTheme.EINK_BORDER)
                dividerHeight = dp(1).coerceAtLeast(1)
            }
            setPadding(0, dp(2), 0, dp(4))
            clipToPadding = false
        }

        val adapter = object : ArrayAdapter<TocEntry>(this, 0, tocList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = getItem(position)!!
                val row: LinearLayout
                val pageTv: TextView
                val titleTv: TextView

                if (convertView is LinearLayout && convertView.childCount == 2) {
                    row = convertView
                    pageTv = row.getChildAt(0) as TextView
                    titleTv = row.getChildAt(1) as TextView
                } else {
                    row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(18), dp(12), dp(18), dp(12))
                    }
                    // 순서 앞 페이지 번호 (약간 작은 숫자, 아주 약간 흐린 색상)
                    pageTv = TextView(context).apply {
                        setPtSize(7.5f)
                        typeface = ReaderTheme.uiFontRegular
                        setTextColor(ReaderTheme.EINK_MUTED)
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = dp(14)
                        }
                    }
                    // 목차 제목
                    titleTv = TextView(context).apply {
                        setPtSize(9f)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(pageTv)
                    row.addView(titleTv)
                }

                pageTv.text = "${item.pageNum}"
                titleTv.text = item.title

                val isCurrent = (item.chapterIndex == curLoc.chapterIndex)
                if (isCurrent) {
                    titleTv.typeface = ReaderTheme.uiFontBold
                    titleTv.setTextColor(if (isLcd) ReaderTheme.ACCENT_RIDI_BLUE else ReaderTheme.EINK_INK)
                    pageTv.setTextColor(if (isLcd) ReaderTheme.ACCENT_RIDI_BLUE else ReaderTheme.EINK_INK)
                } else {
                    titleTv.typeface = ReaderTheme.uiFontMedium
                    titleTv.setTextColor(ReaderTheme.EINK_INK)
                    pageTv.setTextColor(ReaderTheme.EINK_MUTED)
                }

                return row
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val target = tocList.getOrNull(position) ?: return@setOnItemClickListener
            dlg.dismiss()
            pushHistoryAndJump(BookLocation(target.chapterIndex, 0, 0f))
        }

        val activeIndex = tocList.indexOfFirst { it.chapterIndex >= curLoc.chapterIndex }.takeIf { it >= 0 }
            ?: tocList.indexOfLast { it.chapterIndex <= curLoc.chapterIndex }.takeIf { it >= 0 }
        if (activeIndex != null && activeIndex in tocList.indices) {
            listView.setSelection((activeIndex - 1).coerceAtLeast(0))
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dlg = AlertDialog.Builder(this)
            .setView(root)
            .create()

        dlg.showWithOptimalWidth(this, enforceBalancedMargins = true, onBack = onBack)

        // 기본 다이얼로그 패널 여백 제거
        dlg.window?.findViewById<View>(resources.getIdentifier("topPanel", "id", "android"))?.visibility = View.GONE
        dlg.window?.findViewById<View>(resources.getIdentifier("buttonPanel", "id", "android"))?.visibility = View.GONE
    }

    private fun collectCustomFonts(): List<File> {
        val appFontDir = File(filesDir, "fonts").apply { mkdirs() }
        val sdFontDir = File("/sdcard/Fonts")
        val list = ArrayList<File>()
        appFontDir.listFiles { f -> f.extension.lowercase() in listOf("ttf", "otf") }?.let { list.addAll(it) }
        sdFontDir.listFiles { f -> f.extension.lowercase() in listOf("ttf", "otf") }?.let { list.addAll(it) }
        return list.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
    }

    private fun pickFont() {
        val allFonts = collectCustomFonts()
        val currentFont = app.prefs.fontPath

        val presets = listOf(
            Triple("도서 기본 글꼴 (${engine.documentStyle.fontFamily})", "", "DEFAULT"),
            Triple("시스템 명조 (System Serif)", "SYSTEM_SERIF", "SYSTEM_SERIF"),
            Triple("시스템 고딕 (System Sans-serif)", "SYSTEM_SANS", "SYSTEM_SANS")
        )

        val menuItems = ArrayList<Pair<String, () -> Unit>>()

        presets.forEach { (label, path, _) ->
            val isSelected = (currentFont == path || (path.isEmpty() && currentFont.isEmpty()))
            val prefix = if (isSelected) "✓  " else "     "
            menuItems.add("$prefix$label" to {
                app.prefs.fontPath = path
                relayoutNow()
                toast("$label 적용되었습니다.")
            })
        }

        if (allFonts.isNotEmpty()) {
            menuItems.add("── [사용자 추가 글꼴] ──" to {})
            allFonts.forEach { f ->
                val isSelected = (currentFont == f.absolutePath)
                val prefix = if (isSelected) "✓  " else "     "
                menuItems.add("${prefix}사용자 폰트: ${f.nameWithoutExtension}" to {
                    app.prefs.fontPath = f.absolutePath
                    relayoutNow()
                    toast("${f.nameWithoutExtension} 적용되었습니다.")
                })
            }
        }

        menuItems.add("➕ 외부 저장소에서 새 폰트 추가 (.ttf / .otf)" to {
            openFontPicker()
        })

        listDialog("글꼴 설정 (기본 글꼴 · 사용자 폰트)", menuItems.map { it.first }) { i ->
            if (menuItems[i].first.startsWith("──")) return@listDialog
            menuItems[i].second()
        }
    }

    private fun openFontPicker() {
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
                        relayoutNow()
                        toast("'${imported.nameWithoutExtension}' 폰트가 적용되었습니다.")
                    } else {
                        toast("유효한 .ttf 또는 .otf 폰트 파일을 선택해 주세요.")
                    }
                }
            },
            onSystemPickerFallback = {
                showSystemNavigationBar()
                val intent = android.content.Intent(this@ReaderActivity, DocumentPickerActivity::class.java).apply {
                    putExtra("EXTRA_MODE", "FONT")
                }
                startActivityForResult(intent, 901)
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 901 && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<android.net.Uri>()
            data.data?.let { uris.add(it) }
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            }
            if (uris.isNotEmpty()) {
                scope.launch {
                    var lastFontPath: String? = null
                    var addedCount = 0
                    withContext(Dispatchers.IO) {
                        val destDir = File(filesDir, "fonts").apply { mkdirs() }
                        for (uri in uris) {
                            try {
                                var dispName = "custom_font.ttf"
                                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                                    if (c.moveToFirst()) dispName = c.getString(0) ?: dispName
                                }
                                val ext = dispName.substringAfterLast('.', "").lowercase()
                                if (ext !in listOf("ttf", "otf")) continue
                                val safeFile = File(destDir, dispName.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_"))
                                contentResolver.openInputStream(uri)?.use { inp ->
                                    java.io.FileOutputStream(safeFile).use { out -> inp.copyTo(out) }
                                }
                                if (safeFile.exists() && safeFile.length() > 0L) {
                                    lastFontPath = safeFile.absolutePath
                                    addedCount++
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    if (lastFontPath != null) {
                        app.prefs.fontPath = lastFontPath!!
                        relayoutNow()
                        toast("${addedCount}개의 폰트가 추가 및 적용되었습니다: ${File(lastFontPath!!).name}")
                    } else {
                        toast(".ttf 또는 .otf 형식의 폰트 파일을 선택해주세요")
                    }
                }
            }
        }
    }

    private fun displayMenu() {
        val currentKind = app.prefs.forcedKind
        val isAuto = currentKind.isEmpty()

        data class DisplayModeItem(val id: String, val forcedKind: String, val preset: String, val title: String)
        val modeList = listOf(
            DisplayModeItem("AUTO", "", "AUTO", "자동 감지 (기기 환경에 맞춤 - 권장)"),
            DisplayModeItem("MONO_EINK", DisplayKind.MONO_EINK.name, "MONO_EINK", "흑백 E-Ink 모드 강제 적용 (Carta 1300/1200)"),
            DisplayModeItem("COLOR_EINK", DisplayKind.COLOR_EINK.name, "COLOR_EINK", "컬러 E-Ink 모드 강제 적용 (Kaleido 3)"),
            DisplayModeItem("LCD_OLED", DisplayKind.LCD_OLED.name, "GENERIC_TABLET", "일반 LCD / OLED 모드 강제 적용 (컬러)")
        )

        val labels = modeList.map { item ->
            val isSelected = if (item.id == "AUTO") isAuto else (currentKind == item.forcedKind)
            if (isSelected) "✓  ${item.title}" else "     ${item.title}"
        }

        listDialog("디스플레이 해상도 및 화면 모드", labels) { i ->
            val chosen = modeList[i]
            app.prefs.forcedKind = chosen.forcedKind
            app.prefs.displayDevicePreset = chosen.preset
            configureEngine()
            relayoutNow()
            toast("${chosen.title} 적용되었습니다.")
        }
    }

    private fun exportMenu(onBack: (() -> Unit)? = null) {
        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        val hlCount = highlights.count { it.kind == 0 }
        val bmCount = highlights.count { it.kind == 1 }
        val bTitle = (book?.title?.ifBlank { null } ?: engine.bookTitle.ifBlank { null } ?: File(path).nameWithoutExtension)
        val bAuthor = book?.author?.ifBlank { null } ?: engine.bookAuthor
        val optBrowse = L10n.t("목록 열람 및 위치 이동", "Browse List & Jump")
        val optZip = L10n.t("📦 하이라이트 압축파일(ZIP) 이메일 전송", "📦 Export Highlights (ZIP) via Email")
        val optShare = L10n.t("📢 독서 노트 공유 (이메일 · SNS)", "📢 Share Reading Notes (Email · SNS)")
        val optSns = L10n.t("📷 독서 카드 저장 · SNS 공유", "📷 Save Card & Share to SNS")
        val options = mutableListOf(optBrowse, optZip, optShare, optSns)

        listDialog(L10n.t("하이라이트 ($hlCount) / 북마크 ($bmCount)", "Highlights ($hlCount) / Bookmarks ($bmCount)"), options, onBack = onBack) { i ->
            val opt = options[i]
            when (opt) {
                optBrowse -> browseHighlights(onBack = { exportMenu(onBack) })
                optZip -> showEmailHighlightsZipDialog(app, bTitle, bAuthor, highlights, onBack = { exportMenu(onBack) })
                optShare -> showEmailShareDialog(null, onBack = { exportMenu(onBack) })
                optSns -> {
                    val sampleQuote = highlights.firstOrNull { it.kind == 0 }?.quote ?: L10n.t("즐거운 독서의 여정", "A pleasant journey of reading")
                    val sampleMemo = highlights.firstOrNull { it.kind == 0 && it.note.isNotBlank() }?.note
                    AndroidShareHelper.showSnsShareDialog(this, bTitle, bAuthor, sampleQuote, sampleMemo, isEink = !isLcd, onBack = { exportMenu(onBack) })
                }
            }
        }
    }

    private fun safeName(s: String) = s.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(60)

    /**
     * Unified Email Share Dialog:
     * 1. Displays Book Metadata (Title, Author, Format, Progress) and Book Cover Image preview with attachment toggle.
     * 2. Lets the user enter recipient email address.
     * 3. Lets the user select/deselect from the current quote + saved highlights & bookmarks.
     * 4. Sends book info + attached cover image + selected quotes via email.
     */
    private fun showEmailShareDialog(initialQuote: HighlightEntity? = null, onBack: (() -> Unit)? = null) {
        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        val loc = engine.currentLocation()
        val resolvedFormat = (book?.format?.ifBlank { null } ?: (Containers.formatOf(path) ?: BookFormat.TXT).name)
        val resolvedTitle = engine.bookTitle.ifBlank { book?.title ?: "" }.ifBlank { File(path).nameWithoutExtension.ifBlank { L10n.t("Aether SAGA 도서", "Aether SAGA Book") } }
        val resolvedAuthor = engine.bookAuthor.ifBlank { book?.author ?: "" }.ifBlank { L10n.t("저자 미상", "Unknown Author") }
        val b = (book ?: BookEntity(
            path = path,
            title = resolvedTitle,
            author = resolvedAuthor,
            format = resolvedFormat,
            sizeBytes = 0L,
            addedAt = System.currentTimeMillis(),
            chapter = loc.chapterIndex,
            charOffset = loc.charOffset,
            progress = loc.progress
        )).copy(title = resolvedTitle, author = resolvedAuthor, progress = loc.progress)

        val candidateItems = ArrayList<HighlightEntity>()
        if (initialQuote != null) {
            candidateItems.add(initialQuote)
        }
        for (h in highlights) {
            if (initialQuote == null || (h.chapter != initialQuote.chapter || h.startChar != initialQuote.startChar)) {
                candidateItems.add(h)
            }
        }

        if (candidateItems.isEmpty()) {
            toast(L10n.t("공유할 문장이나 저장된 하이라이트/북마크가 없습니다", "No saved quotes, highlights, or bookmarks to share"))
            return
        }

        val coverBmp = AndroidShareHelper.prepareCoverBitmap(
            bookTitle = resolvedTitle,
            bookAuthor = resolvedAuthor,
            bookFormat = resolvedFormat,
            rawCoverBytes = engine.coverBytes(),
            cachedCoverPath = b.coverPath
        )

        val outerScroll = android.widget.ScrollView(this).apply {
            enableVisibleScrollbar()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        outerScroll.addView(root)

        // 1. Book Metadata + Cover Image Preview Card (Sent first!)
        val bookCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable(ReaderTheme.EINK_CARD_BG, 8, ReaderTheme.EINK_BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val coverIv = ImageView(this).apply {
            setImageBitmap(coverBmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = cardDrawable(Color.WHITE, 4, ReaderTheme.EINK_BORDER)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        bookCard.addView(coverIv, LinearLayout.LayoutParams(dp(58), dp(82)).apply {
            rightMargin = dp(12)
        })

        val metaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val metaBadgeTv = TextView(this).apply {
            text = L10n.t("📚 함께 전송될 도서 정보", "📚 Book Information to Share")
            setPtSize(7.5f)
            setTextColor(ReaderTheme.ACCENT_RIDI_BLUE)
            typeface = ReaderTheme.uiFontBold
        }
        val metaTitleTv = TextView(this).apply {
            text = L10n.t("제목: $resolvedTitle", "Title: $resolvedTitle")
            setPtSize(8.8f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = ReaderTheme.uiFontBold
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(1))
        }
        val metaAuthorTv = TextView(this).apply {
            text = L10n.t("저자: $resolvedAuthor  •  $resolvedFormat (${(loc.progress * 100).toInt()}%)", "Author: $resolvedAuthor  •  $resolvedFormat (${(loc.progress * 100).toInt()}%)")
            setPtSize(7.8f)
            setTextColor(ReaderTheme.EINK_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val attachCoverCb = CheckBox(this).apply {
            text = L10n.t("🖼 책 표지 이미지 함께 첨부", "🖼 Attach Book Cover Image")
            isChecked = true
            setPtSize(8f)
            setTextColor(ReaderTheme.EINK_INK)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        metaCol.addView(metaBadgeTv)
        metaCol.addView(metaTitleTv)
        metaCol.addView(metaAuthorTv)
        metaCol.addView(attachCoverCb)
        bookCard.addView(metaCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bookCard, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        // 2. Email Address Input
        val emailLabel = TextView(this).apply {
            text = L10n.t("받는 사람 이메일 주소", "Recipient Email Address")
            setPtSize(8.5f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = ReaderTheme.uiFontBold
            setPadding(0, 0, 0, dp(4))
        }
        val emailInput = EditText(this).apply {
            setText(app.prefs.shareEmail)
            hint = "example@email.com"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPtSize(9f)
            setTextColor(ReaderTheme.EINK_INK)
            setHintTextColor(ReaderTheme.EINK_MUTED)
            background = cardDrawable(ReaderTheme.EINK_CARD_BG, 6, ReaderTheme.EINK_BORDER)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setSingleLine(true)
        }
        root.addView(emailLabel)
        root.addView(emailInput)

        // Friendly Guide Note for Email & SNS Share
        val emailGuideTv = TextView(this).apply {
            text = L10n.t(
                "※ [이메일 전송하기] 버튼을 누르면 이메일 앱(Gmail 등)의 작성 화면으로 도서 정보와 선택 문장 및 표지가 채워져 열립니다. [SNS 독서카드 공유]를 누르면 인포그래픽 독서 카드가 생성되어 SNS로 전달됩니다.",
                "※ Tapping [Send Email] opens your email composer with book info, quotes, and cover. Tapping [Share to SNS] generates an infographic reading card for social media."
            )
            setPtSize(7.6f)
            setTextColor(ReaderTheme.EINK_MUTED)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(emailGuideTv)

        // 4. Checklist Header + Select All / Deselect All
        val listHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }
        val listHeaderTv = TextView(this).apply {
            text = L10n.t("전송/공유할 문장 선택 (하이라이트 / 북마크)", "Select Quotes & Notes (Highlights / Bookmarks)")
            setPtSize(8.5f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = ReaderTheme.uiFontBold
        }
        val checkBoxes = ArrayList<CheckBox>()
        val toggleAllBtn = pillButton(L10n.t("전체 선택/해제", "Select All / None"), ReaderTheme.EINK_INK, ReaderTheme.EINK_PAPER, ReaderTheme.EINK_BORDER) {
            val allChecked = checkBoxes.all { it.isChecked }
            checkBoxes.forEach { it.isChecked = !allChecked }
        }.apply {
            setPtSize(7.5f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            minHeight = dp(28)
            minimumHeight = dp(28)
        }
        listHeaderRow.addView(listHeaderTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listHeaderRow.addView(toggleAllBtn)
        root.addView(listHeaderRow)

        // 4. Scrollable Checklist of Current Quote + Saved Highlights & Bookmarks
        val listScroll = android.widget.ScrollView(this).apply {
            background = cardDrawable(ReaderTheme.EINK_CARD_BG, 6, ReaderTheme.EINK_BORDER)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            enableVisibleScrollbar()
        }
        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((idx, item) in candidateItems.withIndex()) {
            val isInitial = (initialQuote != null && idx == 0)
            val prefix = when {
                isInitial -> L10n.t("✨ [현재 선택 문장] ", "✨ [Current Selection] ")
                item.kind == 1 -> L10n.t("🔖 [북마크] ", "🔖 [Bookmark] ")
                else -> L10n.t("🖍 [하이라이트] ", "🖍 [Highlight] ")
            }
            val cb = CheckBox(this).apply {
                text = "$prefix${item.chapterTitle}: ${item.quote.replace("\n", " ").take(65)}"
                isChecked = if (initialQuote != null) isInitial else true
                setPtSize(8.2f)
                setTextColor(ReaderTheme.EINK_INK)
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            checkBoxes.add(cb)
            listCol.addView(cb)
        }
        listScroll.addView(listCol)
        val maxScrollH = (resources.displayMetrics.heightPixels * 0.28f).toInt().coerceAtLeast(dp(120))
        root.addView(listScroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (candidateItems.size > 3) height = maxScrollH
        })

        lateinit var dlg: AlertDialog
        val executeSend = {
            val email = emailInput.text.toString().trim()
            if (email.isBlank() || !email.contains("@")) {
                toast(L10n.t("올바른 이메일 주소를 입력해주세요", "Please enter a valid email address"))
            } else {
                val selected = candidateItems.filterIndexed { i, _ -> checkBoxes[i].isChecked }
                if (selected.isEmpty()) {
                    toast(L10n.t("공유할 문장이나 하이라이트/북마크를 1개 이상 선택해주세요", "Please select at least one quote or note"))
                } else {
                    app.prefs.shareEmail = email

                    val attachCover = attachCoverCb.isChecked
                    val subject = "[Aether SAGA] $resolvedTitle ($resolvedAuthor) - " + L10n.t("독서 공유 (${selected.size}건)", "Reading Share (${selected.size} items)")
                    val plainBody = MarkdownFormatter.formatEmailBody(
                        book = b,
                        resolvedTitle = resolvedTitle,
                        resolvedAuthor = resolvedAuthor,
                        totalChapters = engine.chapters.size,
                        items = selected,
                        hasCoverAttachment = attachCover
                    )
                    val coverUri = if (attachCover) {
                        AndroidShareHelper.prepareCoverShareUri(this, resolvedTitle, coverBmp)
                    } else null

                    dlg.dismiss()

                    val opened = AndroidShareHelper.openEmailAppDirectly(
                        ctx = this,
                        toEmail = email,
                        subject = subject,
                        body = plainBody,
                        coverUri = coverUri
                    )

                    if (opened) {
                        toast(L10n.t("📨 이메일 앱이 열렸습니다. 내용 확인 후 '보내기'를 눌러주세요.", "📨 Email app opened. Please review and tap 'Send'."))
                    } else {
                        showEmailResultDialog(
                            isSuccess = false,
                            title = L10n.t("이메일 앱 실행 실패", "Failed to Open Email App"),
                            message = L10n.t(
                                "기기에서 이메일 앱(Gmail, 삼성 이메일 등)을 열 수 없습니다.\n이메일 앱이 설치되어 있고 기본 계정이 설정되어 있는지 확인해주세요.",
                                "Could not open email app (Gmail, Samsung Email, etc.). Please check that an email app is installed and configured."
                            )
                        )
                    }
                }
            }
        }

        val executeSns = {
            val selected = candidateItems.filterIndexed { i, _ -> checkBoxes[i].isChecked }
            if (selected.isEmpty()) {
                toast(L10n.t("공유할 문장이나 하이라이트/북마크를 1개 이상 선택해주세요", "Please select at least one quote or note"))
            } else {
                dlg.dismiss()
                val targetItem = selected.first()
                val targetQuote = targetItem.quote
                val targetMemo = targetItem.note.ifBlank { null }
                AndroidShareHelper.showSnsShareDialog(
                    this,
                    resolvedTitle,
                    resolvedAuthor,
                    targetQuote,
                    targetMemo,
                    isEink = !isLcd,
                    onBack = { showEmailShareDialog(initialQuote, onBack) }
                )
            }
        }

        val actionBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val sendBtn = pillButton(L10n.t("📨 이메일 전송하기", "📨 Send Email"), Color.WHITE, ReaderTheme.ACCENT_RIDI_BLUE, ReaderTheme.ACCENT_RIDI_BLUE) {
            executeSend()
        }.apply {
            setPtSize(9f)
            typeface = ReaderTheme.uiFontBold
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val snsBtn = pillButton(L10n.t("📱 SNS 독서카드 공유", "📱 Share to SNS"), Color.WHITE, ReaderTheme.ACCENT_KINDLE, ReaderTheme.ACCENT_KINDLE) {
            executeSns()
        }.apply {
            setPtSize(9f)
            typeface = ReaderTheme.uiFontBold
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        actionBtnRow.addView(sendBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply { rightMargin = dp(6) })
        actionBtnRow.addView(snsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        root.addView(actionBtnRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dm = resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        val emailDlgH = if (isLandscape) (dm.heightPixels * 0.90f).toInt().coerceAtMost(dp(560))
                        else (dm.heightPixels * 0.80f).toInt().coerceAtMost(dp(680))

        dlg = AlertDialog.Builder(this)
            .setTitle(L10n.t("도서 정보 및 독서 노트 공유", "Share Book Info & Reading Notes"))
            .setView(outerScroll)
            .create()
        dlg.showWithOptimalWidth(this, customHeight = emailDlgH, onBack = onBack)
    }

    private fun showEmailResultDialog(isSuccess: Boolean, title: String, message: String) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        val iconTv = TextView(this).apply {
            text = if (isSuccess) L10n.t("🎉 이메일이 정상적으로 발송되었습니다.", "🎉 Email sent successfully.") else L10n.t("⚠️ 이메일 발송 실패 및 설정 안내", "⚠️ Email sending failed")
            setPtSize(9.5f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(if (isSuccess) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        outer.addView(iconTv)

        val scroll = android.widget.ScrollView(this).apply {
            background = cardDrawable(ReaderTheme.EINK_CARD_BG, 6, ReaderTheme.EINK_BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            enableVisibleScrollbar()
        }
        val msgTv = TextView(this).apply {
            text = message
            setPtSize(8.3f)
            setTextColor(ReaderTheme.EINK_INK)
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        scroll.addView(msgTv)
        val maxH = (resources.displayMetrics.heightPixels * 0.45f).toInt().coerceAtLeast(dp(160))
        outer.addView(scroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            height = maxH
        })

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(outer)
            .setPositiveButton(L10n.t("확인", "OK"), null)
            .create()
            .showWithOptimalWidth(this)
    }

    private fun browseHighlights(onBack: (() -> Unit)? = null) {
        val items = highlights
        if (items.isEmpty()) { toast(L10n.t("저장된 하이라이트나 북마크가 없습니다", "No saved highlights or bookmarks")); return }
        val dm = resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        val browseH = if (items.size > 4) {
            if (isLandscape) (dm.heightPixels * 0.90f).toInt().coerceAtMost(dp(560))
            else (dm.heightPixels * 0.80f).toInt().coerceAtMost(dp(680))
        } else null
        val bTitle = (book?.title?.ifBlank { null } ?: engine.bookTitle.ifBlank { null } ?: File(path).nameWithoutExtension)
        val bAuthor = book?.author?.ifBlank { null } ?: engine.bookAuthor
        val zipLabel = L10n.t("📦 전체 하이라이트 압축파일(ZIP) 이메일 전송", "📦 Export Highlights (ZIP) via Email")
        val options = listOf(zipLabel) + items.map { (if (it.kind == 1) "🔖 " else "🖍 ") + it.chapterTitle + ": " + it.quote.take(70) }
        listDialog(L10n.t("하이라이트 및 북마크 목록", "Highlights & Bookmarks"), options, customHeight = browseH, onBack = onBack) { i ->
            if (i == 0) {
                showEmailHighlightsZipDialog(app, bTitle, bAuthor, items, onBack = { browseHighlights(onBack) })
                return@listDialog
            }
            val h = items[i - 1]
            val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
            val itemActions = listOf(
                L10n.t("해당 페이지로 이동", "Go to Page"),
                L10n.t("이메일로 공유하기", "Share via Email"),
                L10n.t("📷 독서 카드 저장 · SNS 공유", "📷 Save Card & Share to SNS"),
                L10n.t("삭제", "Delete")
            )
            listDialog(h.quote.take(40), itemActions, onBack = { browseHighlights(onBack) }) { a ->
                when (a) {
                    0 -> pushHistoryAndJump(BookLocation(h.chapter, h.startChar, 0f))
                    1 -> showEmailShareDialog(h, onBack = { browseHighlights(onBack) })
                    2 -> AndroidShareHelper.showSnsShareDialog(this, bTitle, bAuthor, h.quote, h.note.ifBlank { null }, isEink = !isLcd, onBack = { browseHighlights(onBack) })
                    else -> scope.launch {
                        withContext(Dispatchers.IO) { app.db.highlights().delete(h.id) }
                        highlights = withContext(Dispatchers.IO) { app.db.highlights().forBook(path) }
                        showResult(engine.refreshCurrent(), 0)
                    }
                }
            }
        }
    }

    private fun speakToggle() {
        if (tts.speaking) {
            tts.stop()
            toast("낭독 중지됨")
            return
        }
        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
        if (!speakPage()) {
            if (isLcd) {
                toast("TTS 음성 엔진을 시작할 수 없습니다")
            } else {
                toast("블루투스 오디오 또는 이어폰을 연결하면 TTS 낭독이 시작됩니다")
            }
        } else {
            if (tts.isBtAudioConnected() || tts.isHeadsetConnected()) {
                toast("🎧 이어폰/블루투스로 TTS 낭독을 시작합니다")
            } else if (isLcd) {
                toast("🔊 스피커로 TTS 낭독을 시작합니다")
            } else {
                toast("TTS 낭독을 시작합니다")
            }
        }
    }

    private fun speakPage(): Boolean = tts.speak(engine.currentPageText().ifBlank { " " })

    private fun remapKeys(onBack: (() -> Unit)? = null) {
        val tv = TextView(this).apply {
            text = "매핑할 물리 버튼(볼륨키, 페이지키, 블루투스 리모컨 버튼 등)을 눌러주세요..."
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setPtSize(9f)
        }
        val dlg = AlertDialog.Builder(this).setTitle("물리 버튼 매핑").setView(tv).setNegativeButton("취소", null).create()
        dlg.setOnKeyListener { d, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && code != KeyEvent.KEYCODE_BACK) {
                d.dismiss()
                val actions = if (strategy.kind == DisplayKind.LCD_OLED) {
                    ReaderAction.values().filter { it != ReaderAction.FULL_REFRESH }
                } else {
                    ReaderAction.values().toList()
                }
                val names = actions.map { it.name }
                listDialog("${KeyEvent.keyCodeToString(code)} - 짧게 누름 동작", names, onBack = onBack) { s ->
                    app.prefs.setKeyAction(code, false, actions[s])
                    listDialog("${KeyEvent.keyCodeToString(code)} - 길게 누름 동작", names, onBack = onBack) { l ->
                        app.prefs.setKeyAction(code, true, actions[l]); toast("버튼 설정이 저장되었습니다")
                    }
                }
                true
            } else false
        }
        dlg.showWithOptimalWidth(this, onBack = onBack)
    }

    private fun remapZones(onBack: (() -> Unit)? = null) {
        val grid = GridLayout(this).apply {
            rowCount = 3
            columnCount = 3
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val actions = if (strategy.kind == DisplayKind.LCD_OLED) {
            ReaderAction.values().filter { it != ReaderAction.FULL_REFRESH }
        } else {
            ReaderAction.values().toList()
        }

        fun actionDisplayLabel(a: ReaderAction): String = when (a) {
            ReaderAction.NONE -> L10n.t("동작 없음\n(NONE)", "None\n(NONE)")
            ReaderAction.NEXT -> L10n.t("다음 페이지\n(NEXT)", "Next Page\n(NEXT)")
            ReaderAction.PREV -> L10n.t("이전 페이지\n(PREV)", "Prev Page\n(PREV)")
            ReaderAction.FULL_REFRESH -> L10n.t("화면 새로고침\n(REFRESH)", "Refresh\n(REFRESH)")
            ReaderAction.BOOKMARK -> L10n.t("북마크\n(BOOKMARK)", "Bookmark\n(BOOKMARK)")
            ReaderAction.MENU -> L10n.t("메뉴 열기\n(MENU)", "Open Menu\n(MENU)")
            ReaderAction.TOGGLE_DARK -> L10n.t("다크 모드\n(DARK)", "Dark Mode\n(DARK)")
        }

        for (r in 0..2) for (c in 0..2) {
            lateinit var b: Button
            b = button(actionDisplayLabel(app.prefs.zoneAction(r, c))) {
                val curAction = app.prefs.zoneAction(r, c)
                val curIdx = actions.indexOf(curAction).let { if (it < 0) 0 else it }
                val n = actions[(curIdx + 1) % actions.size]
                app.prefs.setZoneAction(r, c, n)
                b.text = actionDisplayLabel(n)
            }
            b.setPtSize(7.5f)
            b.gravity = Gravity.CENTER
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = dp(106)
                height = dp(76)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }

        val container = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            addView(grid, lp)
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle(L10n.t("각 영역을 터치하여 동작을 변경하세요", "Tap each zone to change action"))
            .setView(container)
            .setPositiveButton(L10n.t("완료", "Done"), null)
            .create()
            .showWithOptimalWidth(this, onBack = onBack)
    }

    private fun webdavSettings(onBack: (() -> Unit)? = null) {
        val inkCol = ReaderTheme.EINK_INK
        val cardBgCol = ReaderTheme.EINK_CARD_BG
        val borderCol = ReaderTheme.EINK_BORDER
        val mutedCol = ReaderTheme.EINK_MUTED

        val scroll = android.widget.ScrollView(this).apply {
            enableVisibleScrollbar()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        scroll.addView(root)

        // Guide Card
        val guideCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(cardBgCol, 8, borderCol)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val guideTitleTv = TextView(this).apply {
            text = L10n.t("💡 WebDAV 원격 동기화 기능 안내", "💡 WebDAV Remote Sync Guide")
            setPtSize(9.5f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
        }
        val guideDescTv = TextView(this).apply {
            text = L10n.t(
                "• 목적: 여러 기기(스마트폰, 태블릿, E-ink 단말기) 간에 마지막 독서 위치 및 하이라이트/메모를 안전하게 자동 동기화합니다.\n\n" +
                "• 개인정보 보호: 외부 상용 클라우드나 타사 서버를 거치지 않고, 사용자가 보유한 개인 NAS(Synology, QNAP 등)나 사설 클라우드(Nextcloud, ownCloud)와 직접 통신하므로 독서 기록이 안전하게 보호됩니다.\n\n" +
                "• 사용 방법: 개인 클라우드의 WebDAV URL, 아이디, 비밀번호를 입력해 두시면 Wi-Fi 등 네트워크 연결 시 자동으로 동기화됩니다.",
                "• Purpose: Automatically sync last read location, highlights and notes across devices.\n\n" +
                "• Privacy: Connects directly to your private NAS or personal cloud (Nextcloud, ownCloud) without third-party servers.\n\n" +
                "• Usage: Enter your WebDAV URL, username, and password to enable seamless Wi-Fi synchronization."
            )
            setPtSize(8f)
            setTextColor(mutedCol)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(4), 0, 0)
        }
        guideCard.addView(guideTitleTv)
        guideCard.addView(guideDescTv)
        root.addView(guideCard)

        // URL Input
        val urlLabel = TextView(this).apply {
            text = L10n.t("WebDAV 폴더 URL", "WebDAV Folder URL")
            setPtSize(8.5f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
            setPadding(0, dp(12), 0, dp(4))
        }
        val urlInput = EditText(this).apply {
            setText(app.prefs.webdavUrl)
            hint = "https://example.com/remote.php/dav/files/user/"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPtSize(9f)
            setTextColor(inkCol)
            setHintTextColor(mutedCol)
            background = cardDrawable(cardBgCol, 6, borderCol)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isSingleLine = true
        }
        root.addView(urlLabel)
        root.addView(urlInput)

        // User Input
        val userLabel = TextView(this).apply {
            text = L10n.t("사용자 아이디 (User)", "Username (User)")
            setPtSize(8.5f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
            setPadding(0, dp(8), 0, dp(4))
        }
        val userInput = EditText(this).apply {
            setText(app.prefs.webdavUser)
            hint = L10n.t("아이디 입력", "Enter username")
            setPtSize(9f)
            setTextColor(inkCol)
            setHintTextColor(mutedCol)
            background = cardDrawable(cardBgCol, 6, borderCol)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isSingleLine = true
        }
        root.addView(userLabel)
        root.addView(userInput)

        // Password Input
        val passLabel = TextView(this).apply {
            text = L10n.t("비밀번호 (Password)", "Password")
            setPtSize(8.5f)
            typeface = ReaderTheme.uiFontBold
            setTextColor(inkCol)
            setPadding(0, dp(8), 0, dp(4))
        }
        val passInput = EditText(this).apply {
            setText(app.prefs.webdavPass)
            hint = L10n.t("비밀번호 또는 앱 비밀번호", "Password or App Password")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            setPtSize(9f)
            setTextColor(inkCol)
            setHintTextColor(mutedCol)
            background = cardDrawable(cardBgCol, 6, borderCol)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isSingleLine = true
        }
        root.addView(passLabel)
        root.addView(passInput)

        val builder = AlertDialog.Builder(this)
            .setTitle(L10n.t("☁ WebDAV 원격 동기화 설정", "☁ WebDAV Remote Sync Settings"))
            .setView(scroll)
            .setPositiveButton(L10n.t("저장 및 동기화", "Save & Sync")) { _, _ ->
                val u = urlInput.text.toString().trim()
                val us = userInput.text.toString().trim()
                val pw = passInput.text.toString().trim()
                app.prefs.webdavUrl = u
                app.prefs.webdavUser = us
                app.prefs.webdavPass = pw
                app.sync.flush()
                toast("WebDAV 동기화 설정이 저장되었습니다")
            }
            .setNegativeButton("취소", null)

        if (app.prefs.webdavUrl.isNotBlank()) {
            builder.setNeutralButton("연결 해제") { _, _ ->
                app.prefs.webdavUrl = ""
                app.prefs.webdavUser = ""
                app.prefs.webdavPass = ""
                toast("WebDAV 연결이 해제되었습니다")
            }
        }

        builder.create().showWithOptimalWidth(this, onBack = onBack)
    }

    // ---- cloud position sync -----------------------------------------------------------

    private fun remoteName(): String = "aethersaga_pos_" + md5(path) + ".json"

    private var syncSnackbar: View? = null

    private fun showWhispersyncSnackbar(device: String, pct: Int, ch: Int, off: Long) {
        syncSnackbar?.let { rootFrame.removeView(it) }
        val isDark = app.prefs.dark
        val cardBg = if (isDark) 0xEE2A2C30.toInt() else 0xF5FFFFFF.toInt()
        val textFg = if (isDark) 0xFFEEEEEE.toInt() else 0xFF1C1C1E.toInt()
        val borderCol = if (isDark) 0xFF44474E.toInt() else 0xFFD1D5DB.toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), borderCol)
            }
            setPadding(dp(16), dp(10), dp(12), dp(10))
            elevation = dp(8).toFloat()

            val msg = TextView(this@ReaderActivity).apply {
                text = "📖 $device ($pct%)"
                setTextColor(textFg)
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(msg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val jumpBtn = TextView(this@ReaderActivity).apply {
                text = "이동"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(ReaderTheme.ACCENT_RIDI_BLUE)
                    cornerRadius = dp(14).toFloat()
                }
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    pushHistoryAndJump(BookLocation(ch, off, 0f))
                    syncSnackbar?.let { rootFrame.removeView(it); syncSnackbar = null }
                }
            }
            val lpJump = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(10)
            }
            addView(jumpBtn, lpJump)

            val closeBtn = TextView(this@ReaderActivity).apply {
                text = "✕"
                setTextColor(if (isDark) 0xFF9E9E9E.toInt() else 0xFF757575.toInt())
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnClickListener {
                    syncSnackbar?.let { rootFrame.removeView(it); syncSnackbar = null }
                }
            }
            addView(closeBtn)
        }

        syncSnackbar = card
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(48)
            leftMargin = dp(24)
            rightMargin = dp(24)
        }
        card.alpha = 0f
        card.translationY = -dp(20).toFloat()
        rootFrame.addView(card, params)
        card.animate().alpha(1f).translationY(0f).setDuration(280L).start()

        handler.postDelayed({
            syncSnackbar?.let {
                it.animate().alpha(0f).translationY(-dp(20).toFloat()).setDuration(240L).withEndAction {
                    rootFrame.removeView(it)
                    if (syncSnackbar === it) syncSnackbar = null
                }.start()
            }
        }, 8000L)
    }

    private fun pullRemotePosition(b: BookEntity?) {
        val c = app.sync.client() ?: return
        scope.launch(Dispatchers.IO) {
            val s = c.get(remoteName()) ?: c.get("aetherreader_pos_" + md5(path) + ".json") ?: return@launch
            try {
                val j = JSONObject(s)
                val upd = j.getLong("updatedAt")
                val ch = j.getInt("chapter"); val off = j.getLong("char")
                val local = b?.lastRead ?: 0L
                if (upd > local && (ch != (b?.chapter ?: 0) || abs(off - (b?.charOffset ?: 0L)) > 2000)) {
                    withContext(Dispatchers.Main) {
                        val isLcd = if (::strategy.isInitialized) strategy.kind == DisplayKind.LCD_OLED else false
                        val device = j.optString("device", "외부 기기")
                        val pct = (j.getDouble("progress") * 100).toInt()
                        if (isLcd) {
                            showWhispersyncSnackbar(device, pct, ch, off)
                        } else {
                            AlertDialog.Builder(this@ReaderActivity)
                                .setTitle("📖 독서 위치 동기화 (Whispersync)")
                                .setMessage("다른 기기($device)에서 더 최근에 읽던 위치($pct%)가 있습니다.\n해당 위치로 이어서 읽으시겠습니까?")
                                .setPositiveButton("이동") { _, _ -> pushHistoryAndJump(BookLocation(ch, off, 0f)) }
                                .setNegativeButton("현재 위치 유지", null)
                                .create()
                                .showWithOptimalWidth(this@ReaderActivity)
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun saveProgress() {
        if (!opened) return
        val loc = engine.currentLocation()
        val now = System.currentTimeMillis()
        app.appScope.launch {
            app.db.books().updateProgress(path, now, loc.chapterIndex, loc.charOffset, loc.progress)
            if (loc.progress >= 0.98f) {
                val bTitle = book?.title?.ifBlank { null } ?: java.io.File(path).nameWithoutExtension
                val bAuthor = book?.author ?: ""
                app.recordBookCompleted(path, bTitle, bAuthor, now)
            }
        }
        if (app.sync.client() != null) {
            val j = JSONObject().put("chapter", loc.chapterIndex).put("char", loc.charOffset).put("progress", loc.progress.toDouble())
                .put("updatedAt", now).put("device", Build.MODEL)
            app.sync.enqueue(remoteName(), j.toString(), "application/json")
        }
    }

    // ---- misc / lifecycle ------------------------------------------------------------------

    private fun showHud(t: String) {
        hud.text = t; hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud); handler.postDelayed(hideHud, 900)
    }
    private val hideHud = Runnable { hud.visibility = View.GONE }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun resetIdle() {
        if (::frontlight.isInitialized) frontlight.restore()
        handler.removeCallbacks(idleDim)
        handler.postDelayed(idleDim, app.prefs.idleDimMinutes * 60_000L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        resetIdle()
        if (keyCode == KeyEvent.KEYCODE_BACK && overlayContainer.visibility == View.VISIBLE) {
            hideOverlay()
            return true
        }
        return keys.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean = keys.onKeyLongPress(keyCode) || super.onKeyLongPress(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = keys.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    override fun onResume() { super.onResume(); orientationMgr.start(); view.hideSystemBars(); resetIdle() }
    override fun onPause() { super.onPause(); orientationMgr.stop(); saveProgress(); tts.stop() }

    override fun finish() { super.finish(); overridePendingTransition(0, 0) }

    private fun showAdvancedFeaturesMenu(onBack: (() -> Unit)? = null) {
        val hw = app.hardware
        val p = app.prefs
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            enableVisibleScrollbar()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        scroll.addView(box)
        lateinit var dlg: AlertDialog
        val updaters = ArrayList<() -> Unit>()

        fun addToggleRow(
            card: LinearLayout,
            title: String,
            desc: String,
            isLast: Boolean,
            stateProvider: () -> Boolean,
            onToggle: () -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val titleTv = TextView(this).apply {
                text = title
                setPtSize(9f)
                setTextColor(ReaderTheme.EINK_INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            val descTv = TextView(this).apply {
                text = desc
                setPtSize(7.5f)
                setTextColor(ReaderTheme.EINK_MUTED)
            }
            col.addView(titleTv)
            col.addView(descTv)

            lateinit var sw: ToggleSwitchView
            val refreshSw = {
                sw.isChecked = stateProvider()
            }
            sw = toggleSwitch(stateProvider()) {
                onToggle()
                updaters.forEach { it() }
            }
            updaters += refreshSw

            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            row.addView(sw)
            card.addView(row)
            if (!isLast) card.addView(thinDivider())
        }

        // 0. 언어 설정 (Language Settings)
        box.addView(sectionHeader(L10n.t("언어 설정 (Language)", "Language Settings")))
        val langCard = settingsCardContainer()
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val langCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val langTitle = TextView(this).apply {
            text = L10n.t("앱 언어 (Language)", "App Language")
            setPtSize(9f)
            setTextColor(ReaderTheme.EINK_INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val curLangDisplay = if (L10n.isKorean) "한국어 (Korean)" else "English (영어)"
        val langDesc = TextView(this).apply {
            text = L10n.t("현재 언어: $curLangDisplay", "Current: $curLangDisplay")
            setPtSize(7.5f)
            setTextColor(ReaderTheme.EINK_MUTED)
        }
        langCol.addView(langTitle)
        langCol.addView(langDesc)

        val langBtn = pillButton(curLangDisplay, ReaderTheme.EINK_INK, ReaderTheme.EINK_CARD_BG, ReaderTheme.EINK_BORDER) {
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
                    L10n.setLanguage(this@ReaderActivity, selectedCode, p)
                    recreate()
                }
                .setNegativeButton(L10n.t("취소", "Cancel"), null)
                .create()
                .showWithOptimalWidth(this, onBack = { showAdvancedFeaturesMenu(onBack) })
        }
        langRow.addView(langCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        langRow.addView(langBtn)
        langCard.addView(langRow)
        box.addView(langCard)

        box.addView(sectionHeader(L10n.t("시선 집중 및 가독성 보조", "Focus & Readability Tools")))
        val focusCard = settingsCardContainer()
        addToggleRow(
            focusCard,
            L10n.t("독서 룰러 (Reading Ruler)", "Reading Ruler"),
            L10n.t("현재 읽는 문장에 시선 가이드 표시 (화면 터치/드래그 시 문장 이동)", "Visual sentence line guide for focus"),
            false,
            { p.readingRuler }
        ) {
            p.readingRuler = !p.readingRuler
            view.readingRulerEnabled = p.readingRuler
            if (p.readingRuler) {
                if (view.rulerY < 0f) view.rulerY = view.height / 2f
                val targetY = view.rulerY.toInt()
                view.rulerSentenceRects = engine.sentenceBoundsAt(view.width / 2, targetY)
            } else {
                view.rulerSentenceRects = null
            }
            view.invalidate()
        }
        addToggleRow(
            focusCard,
            L10n.t("포모도로 집중 타이머", "Pomodoro Focus Timer"),
            L10n.t("25분 독서 집중 세션 타이머를 하단 상태바에 표시", "Display 25-minute reading focus timer"),
            false,
            { p.pomodoroTimer }
        ) {
            p.pomodoroTimer = !p.pomodoroTimer
            if (p.pomodoroTimer) startPomodoroTimer() else stopPomodoroTimer()
        }
        addToggleRow(
            focusCard,
            L10n.t("핸즈프리 자동 책넘김", "Hands-Free Auto Page Turn"),
            L10n.t("설정된 시간마다 자동으로 다음 페이지로 이동 (${p.autoTurnSeconds}초)", "Automatically advance pages every ${p.autoTurnSeconds}s"),
            false,
            { p.autoTurnEnabled }
        ) {
            p.autoTurnEnabled = !p.autoTurnEnabled
            if (p.autoTurnEnabled) startAutoPageTurn() else stopAutoPageTurn()
        }

        val autoIntervalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val autoIntervalLabel = TextView(this).apply {
            text = L10n.t("↳ 자동 넘김 간격", "↳ Auto Turn Interval")
            setPtSize(8f)
            setTextColor(ReaderTheme.EINK_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val autoIntervalVal = TextView(this).apply {
            text = L10n.t("${p.autoTurnSeconds}초", "${p.autoTurnSeconds}s")
            setPtSize(8.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ReaderTheme.EINK_INK)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val isLcd = strategy.kind == DisplayKind.LCD_OLED
        val btnStrokePx = if (isLcd) dp(1) else dp(2)
        val autoMinusBtn = pillButton(
            L10n.t("-5초", "-5s"),
            ReaderTheme.EINK_INK,
            if (isLcd) ReaderTheme.EINK_PAPER else Color.WHITE,
            if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_DIVIDER_CONTRAST,
            btnStrokePx
        ) {
            p.autoTurnSeconds = (p.autoTurnSeconds - 5).coerceIn(10, 120)
            autoIntervalVal.text = L10n.t("${p.autoTurnSeconds}초", "${p.autoTurnSeconds}s")
            if (p.autoTurnEnabled) resetAutoPageTurnTimer()
        }.apply { setPadding(dp(6), dp(2), dp(6), dp(2)) }
        val autoPlusBtn = pillButton(
            L10n.t("+5초", "+5s"),
            ReaderTheme.EINK_INK,
            if (isLcd) ReaderTheme.EINK_PAPER else Color.WHITE,
            if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_DIVIDER_CONTRAST,
            btnStrokePx
        ) {
            p.autoTurnSeconds = (p.autoTurnSeconds + 5).coerceIn(10, 120)
            autoIntervalVal.text = L10n.t("${p.autoTurnSeconds}초", "${p.autoTurnSeconds}s")
            if (p.autoTurnEnabled) resetAutoPageTurnTimer()
        }.apply { setPadding(dp(6), dp(2), dp(6), dp(2)) }
        autoIntervalRow.addView(autoIntervalLabel)
        autoIntervalRow.addView(autoMinusBtn)
        autoIntervalRow.addView(autoIntervalVal)
        autoIntervalRow.addView(autoPlusBtn)
        focusCard.addView(autoIntervalRow)
        focusCard.addView(thinDivider())

        addToggleRow(
            focusCard,
            L10n.t("첫 단어 강조 (Initial Word Bold)", "Initial Word Drop Cap"),
            L10n.t("챕터 첫 시작 단어를 크고 굵게 강조", "Enlarge and bold chapter initial word"),
            true,
            { p.dropCaps }
        ) {
            p.dropCaps = !p.dropCaps
            relayoutNow()
        }
        box.addView(focusCard)

        box.addView(sectionHeader(L10n.t("학습 및 독서 통계 도구", "Learning & Reading Stats Tools")))
        val extraCard = settingsCardContainer()

        // 1. 하이라이트 플래시카드 복습 (배경 박스 내부 배치)
        val studyBtn = pillButton(
            L10n.t("🎴 저장된 하이라이트 플래시카드 복습 열기", "🎴 Review Highlights with Flashcards"),
            ReaderTheme.EINK_INK,
            if (isLcd) ReaderTheme.EINK_CARD_BG else Color.WHITE,
            if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_DIVIDER_CONTRAST,
            btnStrokePx
        ) {
            dlg.dismiss()
            showFlashcardsDialog(onBack = { showAdvancedFeaturesMenu(onBack) })
        }
        extraCard.addView(studyBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        extraCard.addView(thinDivider())

        // 3. 독서 통계 대시보드 (배경 박스 내부 배치)
        val statsBtn = pillButton(
            L10n.t("📊 독서 통계 대시보드 (주간/월간/연간 트렌드)", "📊 Reading Stats Dashboard (Trends)"),
            ReaderTheme.EINK_INK,
            if (isLcd) ReaderTheme.EINK_CARD_BG else Color.WHITE,
            if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_DIVIDER_CONTRAST,
            btnStrokePx
        ) {
            dlg.dismiss()
            StatsDashboardDialog.show(this, app, !isLcd, onBack = { showAdvancedFeaturesMenu(onBack) })
        }
        extraCard.addView(statsBtn, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })

        box.addView(extraCard)

        dlg = AlertDialog.Builder(this)
            .setTitle(L10n.t("⚡ 독서 보조 도구 설정", "⚡ Reading Tools Settings"))
            .setView(scroll)
            .create()
        dlg.showWithOptimalWidth(this, enforceBalancedMargins = true, onBack = onBack)
    }

    private fun startPomodoroTimer() {
        pomodoroSeconds = 25 * 60
        view.pomodoroText = "⏱ 25m"
        handler.removeCallbacks(pomodoroRunnable)
        handler.postDelayed(pomodoroRunnable, 60_000L)
        toast("⏱ 25분 포모도로 집중 타이머 시작")
    }

    private fun stopPomodoroTimer() {
        handler.removeCallbacks(pomodoroRunnable)
        view.pomodoroText = ""
        toast("포모도로 타이머 종료")
    }

    private val pomodoroRunnable: Runnable = object : Runnable {
        override fun run() {
            pomodoroSeconds -= 60
            if (pomodoroSeconds <= 0) {
                pomodoroSeconds = 25 * 60
                toast("🔔 포모도로 독서 1세션 완료! 5분 휴식을 권장합니다.")
            }
            view.pomodoroText = "⏱ " + (pomodoroSeconds / 60) + "m"
            handler.postDelayed(this, 60_000L)
        }
    }

    private fun showFlashcardsDialog(onBack: (() -> Unit)? = null) {
        val items = highlights.filter { it.kind == 0 }
        if (items.isEmpty()) {
            toast(L10n.t("복습할 하이라이트가 없습니다.", "No highlights to review."))
            return
        }
        var idx = 0
        fun renderCard(qTv: TextView, nTv: TextView, cTv: TextView) {
            val h = items[idx]
            qTv.text = "“" + h.quote + "”"
            nTv.text = if (h.note.isNotBlank()) L10n.t("메모: ", "Note: ") + h.note else L10n.t("등록된 메모 없음", "No note")
            cTv.text = "${idx + 1} / ${items.size}  •  ${h.chapterTitle}"
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val countTv = TextView(this).apply { gravity = Gravity.RIGHT; setPtSize(7.5f); setTextColor(Color.GRAY) }
        val quoteTv = TextView(this).apply {
            setPtSize(9f)
            typeface = Typeface.SERIF
            setPadding(0, dp(8), 0, dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val noteTv = TextView(this).apply { setPtSize(8.5f); setTextColor(ReaderTheme.ACCENT_RIDI_BLUE); setPadding(0, 0, 0, dp(10)) }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        box.addView(countTv)
        box.addView(quoteTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(noteTv)
        box.addView(btnRow)

        val dm = resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        val cardH = if (isLandscape) (dm.heightPixels * 0.78f).toInt().coerceAtMost(dp(380))
                    else (dm.heightPixels * 0.55f).toInt().coerceIn(dp(280), dp(420))

        val dlg = AlertDialog.Builder(this).setTitle(L10n.t("🎴 하이라이트 플래시카드", "🎴 Highlight Flashcards")).setView(box).create()
        val prevB = button(L10n.t("◀ 이전", "◀ Previous")) { if (idx > 0) { idx--; renderCard(quoteTv, noteTv, countTv) } }
        val nextB = button(L10n.t("다음 ▶", "Next ▶")) { if (idx < items.size - 1) { idx++; renderCard(quoteTv, noteTv, countTv) } }
        btnRow.addView(prevB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        btnRow.addView(nextB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        dlg.showWithOptimalWidth(this, customHeight = cardH, onBack = onBack)
        renderCard(quoteTv, noteTv, countTv)
    }

    private fun pushHistoryAndJump(target: BookLocation) {
        navHistory.push(engine.currentLocation())
        scope.launch {
            showResult(engine.goTo(target), 0)
            showJumpBackBar(navHistory.pop() ?: return@launch)
        }
    }

    private fun showJumpBackBar(returnLoc: BookLocation) {
        jumpSnackbar?.let { rootFrame.removeView(it) }
        val btn = pillButton("↩ 이전 위치로 복귀 (${(returnLoc.progress * 100).toInt()}%)", Color.WHITE, 0xEE111111.toInt(), Color.WHITE) {
            scope.launch {
                showResult(engine.goTo(returnLoc), 0)
                jumpSnackbar?.let { rootFrame.removeView(it) }
                jumpSnackbar = null
            }
        }
        jumpSnackbar = btn
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(42)
        }
        rootFrame.addView(btn, params)
        handler.postDelayed({
            jumpSnackbar?.let { rootFrame.removeView(it); jumpSnackbar = null }
        }, 6000L)
    }

    override fun onDestroy() {
        stopAutoPageTurn()
        handler.removeCallbacksAndMessages(null)
        tts.shutdown()
        scope.cancel()
        engine.close()
        super.onDestroy()
    }
}
