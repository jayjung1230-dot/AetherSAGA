package com.aether.reader.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

// Theme Palettes (Kindle Paperwhite Warm/Mono + Ridi Modern Clean Slate)
object ReaderTheme {
    const val EINK_PAPER = 0xFFFBFBF8.toInt()      // Carta 1300 warm paper off-white
    const val EINK_INK = 0xFF111111.toInt()        // Deep ink black
    const val EINK_MUTED = 0xFF767676.toInt()      // Clean secondary text
    const val EINK_BORDER = 0xFFE0E0DC.toInt()     // Subtle paper divider
    const val EINK_BORDER_CONTRAST = 0xFF666666.toInt() // High-contrast charcoal border (2px) for E-Ink Carta screens
    const val EINK_DIVIDER_CONTRAST = 0xFF666666.toInt() // High-contrast charcoal divider for E-Ink Carta screens
    const val EINK_CARD_BG = 0xFFF0EEE9.toInt()    // Warm soft gray surface for cards and menu groups
    const val EINK_CARD_BG_LIGHT = 0xFFF2F2EE.toInt() // Subtle light surface for book cards

    const val DARK_SURFACE = 0xFF141416.toInt()    // Modern OLED true-dark
    const val DARK_CARD = 0xFF202024.toInt()
    const val DARK_TEXT = 0xFFEEEEEE.toInt()
    const val DARK_MUTED = 0xFF9E9EA4.toInt()
    const val DARK_BORDER = 0xFF2C2C32.toInt()

    // Eye-care / Amber & OLED Reading Themes (for LCD/OLED screens)
    const val SEPIA_PAPER = 0xFFF5EEDB.toInt()      // Warm amber / eye-care blue light filter
    const val SEPIA_INK = 0xFF2C2216.toInt()        // Deep warm ink
    const val TRUE_BLACK_PAPER = 0xFF000000.toInt() // OLED true black
    const val TRUE_BLACK_INK = 0xFFD0D0D0.toInt()   // Soft white for true black

    const val ACCENT_KINDLE = 0xFF2A2A2A.toInt()
    const val ACCENT_RIDI_BLUE = 0xFF1F8CE6.toInt() // Subtle Ridi accent for interactive elements

    const val CORNER_CARD_DP = 8
    const val CORNER_PILL_DP = 20
    const val STROKE_EINK_PX = 2
    const val STROKE_LCD_PX = 1

    fun einkBtnBg(isLcd: Boolean, isDark: Boolean = false): Int =
        if (isDark) DARK_SURFACE else (if (isLcd) EINK_CARD_BG_LIGHT else Color.WHITE)

    fun einkBtnBorder(isLcd: Boolean, isDark: Boolean = false): Int =
        if (isDark) DARK_BORDER else (if (isLcd) EINK_BORDER else EINK_BORDER_CONTRAST)

    fun einkStrokePx(context: Context, isLcd: Boolean): Int =
        if (isLcd) context.dp(STROKE_LCD_PX) else context.dp(STROKE_EINK_PX)

    val uiFontRegular: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    val uiFontMedium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    val uiFontBold: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.BOLD) }
}

// dp defined in Ui.kt
fun Context.sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

/**
 * Sets UI TextView font size & crisp typography scaled to fit the UI container cleanly
 * without being too small:
 * - 9.0pt -> ~15.1dp (Primary menu item labels, list items)
 * - 8.5pt -> ~14.3dp (Standard pill action buttons, stepper values)
 * - 8.0pt -> ~13.4dp (Section headers, secondary info)
 * - 7.5pt -> ~12.6dp (Sub-descriptions, 5-column quick bar buttons)
 */
fun TextView.setPtSize(pt: Float) {
    val sw = resources.configuration.smallestScreenWidthDp.takeIf { it > 0 } ?: 360
    val widthScale = (sw / 360f).coerceIn(0.92f, 1.12f)
    val dpSize = (pt * 1.68f * widthScale).coerceAtLeast(12.2f)
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, dpSize)
    paintFlags = paintFlags or Paint.SUBPIXEL_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
    letterSpacing = -0.015f
    includeFontPadding = false
}

/**
 * Expands AlertDialog width and enforces a single, continuous, completely enclosed
 * outline border around the entire popup window (top title + middle content + bottom buttons).
 */
/**
 * Enforces a single, continuous, completely enclosed
 * outline border around the entire popup window (top title + middle content + bottom buttons).
 * When [enforceBalancedMargins] is true, applies unified balanced margins on all 4 sides (top, bottom, left, right).
 * [customHeight] allows specialized windows (e.g. flashcard, highlight browsing) to specify purpose-fitted heights.
 */
fun android.app.AlertDialog.showWithOptimalWidth(
    context: Context,
    enforceBalancedMargins: Boolean = false,
    customHeight: Int? = null,
    onBack: (() -> Unit)? = null
): android.app.AlertDialog {
    show()
    val w = window ?: return this
    val dm = context.resources.displayMetrics
    val isLandscape = dm.widthPixels > dm.heightPixels

    val targetW = if (isLandscape) {
        (dm.widthPixels * 0.85f).toInt().coerceAtMost(context.dp(720))
    } else {
        (dm.widthPixels * 0.94f).toInt().coerceAtMost(context.dp(640))
    }

    val targetH = when {
        customHeight != null -> customHeight
        enforceBalancedMargins -> {
            if (isLandscape) {
                (dm.heightPixels * 0.94f).toInt().coerceAtMost(context.dp(620))
            } else {
                (dm.heightPixels * 0.90f).toInt().coerceAtMost(context.dp(780))
            }
        }
        else -> ViewGroup.LayoutParams.WRAP_CONTENT
    }

    w.setLayout(targetW, targetH)
    w.setGravity(Gravity.CENTER)
    w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

    // Restore gentle window backdrop dim & drop shadow for all popup menus
    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    w.setDimAmount(0.35f)
    w.decorView.elevation = context.dp(8).toFloat()
    w.decorView.translationZ = 0f

    // LCD/OLED uniform brightness synchronization
    val app = context.applicationContext as? com.aether.reader.App
    val isLcd = app?.prefs?.let { prefs ->
        val kind = prefs.forcedKind.ifBlank { app.hardware.spec.displayKind.name }
        kind == "LCD_OLED" || app.hardware.spec.isTablet
    } ?: false

    if (isLcd && app != null && !app.prefs.followSystemBrightness) {
        val b = app.prefs.brightness.coerceIn(0.01f, 1f)
        val lp = w.attributes
        lp.screenBrightness = b
        w.attributes = lp
    }

    // If active inside ReaderActivity, attach dialog window to FrontlightController for live tint & brightness overlay
    if (context is ReaderActivity) {
        try {
            val flField = ReaderActivity::class.java.getDeclaredField("frontlight")
            flField.isAccessible = true
            val fl = flField.get(context) as? com.aether.reader.platform.FrontlightController
            fl?.attachDialogWindow(w)
            setOnDismissListener {
                fl?.detachDialogWindow()
            }
        } catch (_: Throwable) {}
    }

    if (onBack != null) {
        setOnCancelListener {
            onBack()
        }
    }

    val res = context.resources
    fun sysId(name: String): Int = res.getIdentifier(name, "id", "android")

    // Strip broken 3-part Holo 9-patch backgrounds from internal panels so the middle section never loses its border
    val panelNames = listOf("topPanel", "title_template", "contentPanel", "scrollView", "customPanel", "custom", "buttonPanel")
    for (name in panelNames) {
        val id = sysId(name)
        if (id != 0) {
            w.findViewById<View>(id)?.apply {
                background = null
                setPadding(0, 0, 0, 0)
            }
        }
    }

    sysId("topPanel").takeIf { it != 0 }?.let { w.findViewById<View>(it) }?.apply {
        setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(6))
    }
    sysId("buttonPanel").takeIf { it != 0 }?.let { w.findViewById<View>(it) }?.apply {
        setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(6))
    }

    val contentRoot = w.findViewById<android.view.ViewGroup>(android.R.id.content)
    val parentPanel = (sysId("parentPanel").takeIf { it != 0 }?.let { w.findViewById<View>(it) } as? android.view.ViewGroup)
        ?: (contentRoot?.getChildAt(0) as? android.view.ViewGroup)

    if (enforceBalancedMargins || customHeight != null) {
        parentPanel?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        val cp = sysId("customPanel").takeIf { it != 0 }?.let { w.findViewById<View>(it) }
            ?: sysId("custom").takeIf { it != 0 }?.let { w.findViewById<View>(it) }
        (cp?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = 1f
            lp.height = 0
            cp.layoutParams = lp
        }
        val contentP = sysId("contentPanel").takeIf { it != 0 }?.let { w.findViewById<View>(it) }
        (contentP?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = 1f
            lp.height = 0
            contentP.layoutParams = lp
        }
    }

    parentPanel?.apply {
        elevation = context.dp(6).toFloat()
        val corner = context.dp(12).toFloat()
        val strokeW = context.dp(2)
        background = GradientDrawable().apply {
            setColor(ReaderTheme.EINK_PAPER)
            cornerRadius = corner
            setStroke(strokeW, ReaderTheme.ACCENT_KINDLE)
        }
        // Draw continuous outline in foreground on top of all child panels (top, middle, bottom)
        foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = corner
            setStroke(strokeW, ReaderTheme.ACCENT_KINDLE)
        }
        val pad = context.dp(3)
        setPadding(pad, pad, pad, pad)
        clipToPadding = true
    }

    // Style dialog action buttons cleanly & eliminate redundant "닫기" buttons (replaced by top-right '✕')
    val b1 = w.findViewById<View>(android.R.id.button1) as? Button
    val b2 = w.findViewById<View>(android.R.id.button2) as? Button
    val b3 = w.findViewById<View>(android.R.id.button3) as? Button

    for (b in listOfNotNull(b1, b2, b3)) {
        val label = b.text?.toString()?.trim() ?: ""
        if (label == "닫기" || label == "✕ 닫기" || label == "취소하고 닫기") {
            b.visibility = View.GONE
        } else if (label.isNotBlank()) {
            b.visibility = View.VISIBLE
            b.setPtSize(8.5f)
            b.typeface = ReaderTheme.uiFontBold
            b.setTextColor(ReaderTheme.EINK_INK)
            b.isAllCaps = false
            b.minHeight = context.dp(36)
            b.minimumHeight = context.dp(36)
            if (b === b1) {
                b.background = GradientDrawable().apply {
                    setColor(ReaderTheme.EINK_CARD_BG)
                    cornerRadius = context.dp(6).toFloat()
                    setStroke(context.dp(1), ReaderTheme.EINK_INK)
                }
                b.setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
            }
        }
    }

    val hasVisibleBtn = (b1 != null && b1.visibility == View.VISIBLE && !b1.text.isNullOrBlank()) ||
                        (b2 != null && b2.visibility == View.VISIBLE && !b2.text.isNullOrBlank()) ||
                        (b3 != null && b3.visibility == View.VISIBLE && !b3.text.isNullOrBlank())

    sysId("buttonPanel").takeIf { it != 0 }?.let { w.findViewById<View>(it) as? ViewGroup }?.let { bp ->
        if (hasVisibleBtn) {
            bp.visibility = View.VISIBLE
            bp.setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(10))
        } else {
            bp.visibility = View.GONE
            bp.setPadding(0, 0, 0, 0)
        }
    }

    // Style dialog title & divider cleanly, injecting unified top-right '✕' close button and optional '◀ 뒤로' button
    val alertTitleId = sysId("alertTitle")
    val titleView = if (alertTitleId != 0) w.findViewById<View>(alertTitleId) as? TextView else null
    val topPanelId = sysId("topPanel")
    val topPanelView = if (topPanelId != 0) w.findViewById<View>(topPanelId) as? ViewGroup else null
    val titleTemplateId = sysId("title_template")
    val titleTemplateView = if (titleTemplateId != 0) w.findViewById<View>(titleTemplateId) as? ViewGroup else null

    val targetTitleContainer = titleTemplateView ?: topPanelView
    if (targetTitleContainer != null && titleView != null) {
        if (targetTitleContainer.findViewWithTag<View>("saga_dialog_close_btn") == null) {
            titleView.apply {
                setPtSize(9.5f)
                typeface = ReaderTheme.uiFontBold
                setTextColor(ReaderTheme.EINK_INK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            }

            val closeBtn = TextView(context).apply {
                tag = "saga_dialog_close_btn"
                text = "✕"
                setPtSize(11f)
                setTextColor(ReaderTheme.EINK_INK)
                typeface = ReaderTheme.uiFontBold
                gravity = Gravity.CENTER
                val hitBox = context.dp(36)
                layoutParams = LinearLayout.LayoutParams(hitBox, hitBox).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                }
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = context.dp(6).toFloat()
                }
                setOnClickListener {
                    this@showWithOptimalWidth.dismiss()
                }
            }

            if (targetTitleContainer is LinearLayout) {
                if (onBack != null && targetTitleContainer.findViewWithTag<View>("saga_dialog_back_btn") == null) {
                    targetTitleContainer.orientation = LinearLayout.VERTICAL
                    targetTitleContainer.removeAllViews()

                    val navRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }

                    val backBtn = TextView(context).apply {
                        tag = "saga_dialog_back_btn"
                        text = com.aether.reader.data.L10n.t("◀ 뒤로", "◀ Back")
                        setPtSize(8.5f)
                        setTextColor(ReaderTheme.EINK_INK)
                        typeface = ReaderTheme.uiFontBold
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(context.dp(10), context.dp(4), context.dp(12), context.dp(4))
                        background = GradientDrawable().apply {
                            setColor(if (isLcd) 0x12000000 else ReaderTheme.EINK_CARD_BG)
                            cornerRadius = context.dp(6).toFloat()
                            setStroke(context.dp(1), ReaderTheme.EINK_BORDER)
                        }
                        setOnClickListener {
                            this@showWithOptimalWidth.dismiss()
                            onBack()
                        }
                    }

                    val navSpacer = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    }

                    navRow.addView(backBtn)
                    navRow.addView(navSpacer)
                    navRow.addView(closeBtn)
                    targetTitleContainer.addView(navRow)

                    titleView.apply {
                        setPtSize(10f)
                        typeface = ReaderTheme.uiFontBold
                        setTextColor(ReaderTheme.EINK_INK)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = context.dp(6)
                            bottomMargin = context.dp(2)
                        }
                    }
                    targetTitleContainer.addView(titleView)
                } else {
                    targetTitleContainer.orientation = LinearLayout.HORIZONTAL
                    targetTitleContainer.gravity = Gravity.CENTER_VERTICAL
                    targetTitleContainer.addView(closeBtn)
                }
            } else {
                topPanelView?.addView(closeBtn)
            }
        }
    } else if (titleView != null) {
        titleView.setPtSize(9.5f)
        titleView.typeface = ReaderTheme.uiFontBold
        titleView.setTextColor(ReaderTheme.EINK_INK)
    }

    val dividerId = sysId("titleDivider")
    if (dividerId != 0) {
        val dividerCol = if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.EINK_DIVIDER_CONTRAST
        w.findViewById<View>(dividerId)?.setBackgroundColor(dividerCol)
    }

    // Automatically detect and enable persistent high-contrast visible scrollbar on all internal ScrollViews & ListViews
    fun findAndEnableScrollbars(v: View?) {
        if (v == null) return
        val className = v.javaClass.name
        if (v is ScrollView || v is AbsListView || className.contains("ScrollView") || className.contains("ListView")) {
            v.enableVisibleScrollbar()
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findAndEnableScrollbars(v.getChildAt(i))
            }
        }
    }
    findAndEnableScrollbars(w.decorView)
    w.decorView.post { findAndEnableScrollbars(w.decorView) }

    return this
}

/**
 * Enables persistent, high-contrast vertical scrollbar with optimal width
 * and rounded thumb and track. Essential for E-Ink readability and long settings dialogs.
 */
fun View.enableVisibleScrollbar(isDark: Boolean = false, widthDp: Int = 6) {
    isVerticalScrollBarEnabled = true
    isScrollbarFadingEnabled = false
    scrollBarSize = (widthDp * resources.displayMetrics.density).toInt()
    scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val radius = widthDp * resources.displayMetrics.density / 2f
        verticalScrollbarThumbDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (isDark) 0xFFB0B0B0.toInt() else 0xFF333333.toInt())
            cornerRadius = radius
        }
        verticalScrollbarTrackDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (isDark) 0x33FFFFFF.toInt() else 0x22000000.toInt())
            cornerRadius = radius
        }
    }
}

/** Rounded pill background drawable */
fun Context.pillDrawable(bg: Int, stroke: Int = Color.TRANSPARENT, strokePx: Int = dp(1)): Drawable = GradientDrawable().apply {
    setColor(bg)
    cornerRadius = dp(ReaderTheme.CORNER_PILL_DP).toFloat()
    if (stroke != Color.TRANSPARENT) setStroke(strokePx.coerceAtLeast(1), stroke)
}

/** Elegant Kindle/Ridi-style pill action button optimized for UI menus */
fun Context.pillButton(
    text: String,
    fg: Int,
    bg: Int,
    stroke: Int = Color.TRANSPARENT,
    onClick: () -> Unit
): Button = pillButton(text, fg, bg, stroke, dp(1), onClick)

fun Context.pillButton(
    text: String,
    fg: Int,
    bg: Int,
    stroke: Int,
    strokePx: Int,
    onClick: () -> Unit
): Button = Button(this).apply {
    this.text = text
    setPtSize(8.5f)
    isAllCaps = false
    maxLines = 1
    ellipsize = android.text.TextUtils.TruncateAt.END
    setTextColor(fg)
    typeface = ReaderTheme.uiFontMedium
    background = pillDrawable(bg, stroke, strokePx)
    stateListAnimator = null
    elevation = 0f
    minWidth = 0
    minimumWidth = 0
    minHeight = dp(36)
    minimumHeight = dp(36)
    setPadding(dp(12), dp(7), dp(12), dp(7))
    setOnClickListener { onClick() }
}

/**
 * Lightweight, crisp Dotted Divider View for menu item separation on E-Ink and LCD screens.
 * Uses a software rendering layer to guarantee DashPathEffect renders reliably across all Android versions.
 */
class DottedDividerView @JvmOverloads constructor(
    context: Context,
    dotColor: Int = 0xFF888888.toInt(),
    strokePx: Int = context.dp(1),
    dashDp: Float = 3f,
    gapDp: Float = 3f
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dotColor
        style = Paint.Style.STROKE
        this.strokeWidth = strokePx.toFloat().coerceAtLeast(1f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(dashDp.toInt()).toFloat(), context.dp(gapDp.toInt()).toFloat()), 0f)
    }
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = height / 2f
        path.reset()
        path.moveTo(0f, y)
        path.lineTo(width.toFloat(), y)
        canvas.drawPath(path, paint)
    }
}

/**
 * High-contrast, E-Ink & Tablet friendly visual Toggle Switch widget (52dp x 28dp).
 * Draws a stadium track with a sliding circular thumb (left = OFF, right = ON).
 */
class ToggleSwitchView(context: Context) : View(context) {
    var isChecked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var onToggle: (() -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        setOnClickListener {
            onToggle?.invoke()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(52), context.dp(28))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dens = resources.displayMetrics.density
        val pad = 1.5f * dens
        rect.set(pad, pad, w - pad, h - pad)
        val radius = (h - 2 * pad) / 2f

        if (isChecked) {
            trackPaint.color = ReaderTheme.ACCENT_KINDLE
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            val thumbR = radius - 3f * dens
            val cx = w - pad - radius
            val cy = h / 2f
            thumbPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, thumbR, thumbPaint)
        } else {
            trackPaint.color = 0xFFE4E4DF.toInt()
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            borderPaint.color = 0xFF70706C.toInt()
            borderPaint.strokeWidth = 1.5f * dens
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
            val thumbR = radius - 3.5f * dens
            val cx = pad + radius
            val cy = h / 2f
            thumbPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, thumbR, thumbPaint)
            canvas.drawCircle(cx, cy, thumbR, borderPaint)
        }
    }
}

fun Context.toggleSwitch(initial: Boolean, onToggleClick: () -> Unit): ToggleSwitchView =
    ToggleSwitchView(this).apply {
        isChecked = initial
        onToggle = onToggleClick
    }

/** Kindle-style modern rounded card container */
fun cardDrawable(bg: Int, cornerDp: Int = 8, border: Int = Color.TRANSPARENT, strokePx: Int = 2): Drawable {
    return GradientDrawable().apply {
        setColor(bg)
        cornerRadius = cornerDp.toFloat() * 2f
        if (border != Color.TRANSPARENT) setStroke(strokePx.coerceAtLeast(1), border)
    }
}

/** Ridi/Kindle-style book cover placeholder with typography when no cover image exists */
fun renderDefaultBookCover(width: Int, height: Int, title: String, author: String, dark: Boolean): Bitmap {
    val bmp = Bitmap.createBitmap(width.coerceAtLeast(10), height.coerceAtLeast(10), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    
    // Rich paper-like base gradient
    val bgColors = if (dark) intArrayOf(0xFF232528.toInt(), 0xFF18191B.toInt()) 
                   else intArrayOf(0xFFEDEDE8.toInt(), 0xFFD8D8D2.toInt())
    val grad = LinearGradient(0f, 0f, 0f, height.toFloat(), bgColors, null, Shader.TileMode.CLAMP)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

    // Book spine crease effect on the left (Classic Kindle/Ridi look)
    val spine = Paint().apply {
        shader = LinearGradient(0f, 0f, width * 0.08f, 0f, 
            intArrayOf(0x33000000, 0x11000000, 0x00000000), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, width * 0.08f, height.toFloat(), spine)

    // Decorative inner frame
    val borderP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = if (dark) 0x33FFFFFF else 0x22000000
    }
    val margin = width * 0.08f
    canvas.drawRoundRect(margin, margin, width - margin, height - margin, 6f, 6f, borderP)

    // Title & Author Typography
    val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0xFFEEEEEE.toInt() else 0xFF222222.toInt()
        textSize = (width * 0.11f).coerceIn(18f, 32f)
        typeface = Typeface.create("serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    val displayTitle = if (title.length > 28) title.take(26) + "…" else title
    val words = displayTitle.chunked(12)
    var y = height * 0.38f
    for (line in words.take(3)) {
        canvas.drawText(line, width / 2f, y, textP)
        y += textP.textSize * 1.25f
    }

    if (author.isNotBlank()) {
        val authP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFFAAAAAA.toInt() else 0xFF666666.toInt()
            textSize = (width * 0.075f).coerceIn(12f, 20f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(author.take(20), width / 2f, height * 0.82f, authP)
    }

    return bmp
}
