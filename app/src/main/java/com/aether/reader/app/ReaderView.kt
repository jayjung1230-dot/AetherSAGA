package com.aether.reader.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.aether.reader.domain.display.PageTransition
import kotlin.math.abs

/**
 * Draws the engine's bitmap with Paperwhite Carta 1300 aesthetic, Ridi progress line,
 * adaptive Reading Ruler, and clean Kindle footer.
 */
class ReaderView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onZone(row: Int, col: Int)
        fun onSwipePage(forward: Boolean)
        fun onLongPress(x: Int, y: Int)
        fun onSelectionStart(x: Int, y: Int) {}
        fun onSelectionDrag(x: Int, y: Int) {}
        fun onSelectionEnd() {}
        fun onSelectionCancel() {}
        fun onRulerMove(x: Float, y: Float) {}
        fun onEdgeStart(side: Int)            // 0 left (brightness), 1 right (warmth)
        fun onEdgeMove(side: Int, delta: Float)
        fun onTwoFingerVertical(delta: Float)
        fun onTwoFingerHorizontal(delta: Float)
        fun onSizeReady(w: Int, h: Int)
        fun onAnyTouch()
        fun onSingleTap(x: Float, y: Float): Boolean { return false }
    }

    var readingRulerEnabled: Boolean = false
    var isEinkDisplay: Boolean = true
    var rulerSentenceRects: List<RectF>? = null
        set(v) { field = v; invalidate() }
    var rulerY: Float = -1f
        set(v) { field = v; invalidate() }
    var selectionRects: List<RectF>? = null
        set(v) { field = v; invalidate() }
    var pomodoroText: String = ""
        set(v) { field = v; invalidate() }
    var progressRatio: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    var listener: Listener? = null
    var bg: Int = ReaderTheme.EINK_PAPER
    var fg: Int = ReaderTheme.EINK_INK
    var transition = PageTransition.INSTANT
    var durationMs = 0
    var statusChapter: String = ""
        set(v) { field = v; invalidate() }
    var statusProgress: String = ""
        set(v) { field = v; invalidate() }

    private var current: Bitmap? = null
    private var shownCopy: Bitmap? = null
    private var outgoing: Bitmap? = null
    private var activeAnim: ValueAnimator? = null
    private var animDir = 1
    private var t = 1f

    // Kindle/Ridi footer paints
    private val footerPaintLeft = Paint(Paint.ANTI_ALIAS_FLAG)
    private val footerPaintRight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressTrackPaint = Paint()
    private val progressFillPaint = Paint()
    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint()
    private val handler = Handler(Looper.getMainLooper())

    fun clearPage() {
        activeAnim?.cancel()
        activeAnim = null
        outgoing = null
        current = null
        shownCopy = null
        invalidate()
    }

    /** dir: +1 forward, -1 backward, 0 no animation. Instant page turn (all page curl/slide animations removed). */
    fun showPage(bmp: Bitmap, dir: Int) {
        activeAnim?.cancel()
        activeAnim = null
        val old = shownCopy
        current = bmp
        old?.let { if (!it.isRecycled && it != bmp) it.recycle() }
        outgoing = null
        shownCopy = null
        t = 1f
        selectionRects = null
        rulerSentenceRects = null
        invalidate()
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0 && (w != ow || h != oh)) listener?.onSizeReady(w, h)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        val cur = current ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val dens = resources.displayMetrics.density

        // Instant clean presentation (zero curl/slide latency)
        c.drawBitmap(cur, 0f, 0f, null)

        // Drag-to-select visual selection overlay (real-time feedback)
        val selRects = selectionRects
        if (!selRects.isNullOrEmpty()) {
            val isLight = (fg == Color.BLACK || fg == ReaderTheme.EINK_INK)
            selectionPaint.style = Paint.Style.FILL
            selectionPaint.color = if (isEinkDisplay) {
                if (isLight) 0x33000000 else 0x44FFFFFF
            } else {
                0x444A90E2 // Translucent modern blue for LCD/OLED
            }
            // Union all rects into one path so adjacent/overlapping lines are filled exactly once (no darker overlaps).
            val unionPath = Path()
            val tmpPath = Path()
            for (r in selRects) {
                tmpPath.reset()
                tmpPath.addRoundRect(r.left - 2f * dens, r.top, r.right + 2f * dens, r.bottom, 3f * dens, 3f * dens, Path.Direction.CW)
                if (unionPath.isEmpty) unionPath.set(tmpPath) else unionPath.op(tmpPath, Path.Op.UNION)
            }
            c.drawPath(unionPath, selectionPaint)
            // Selection underline guide
            selectionPaint.style = Paint.Style.STROKE
            selectionPaint.strokeWidth = 2f * dens
            selectionPaint.color = if (isEinkDisplay) fg else 0xFF1976D2.toInt()
            for (r in selRects) {
                val uy = r.bottom + 1.5f * dens
                c.drawLine(r.left, uy, r.right, uy, selectionPaint)
            }
        }

        // Sentence-Level Reading Ruler / Focus Guide
        if (readingRulerEnabled) {
            val sRects = rulerSentenceRects
            if (!sRects.isNullOrEmpty()) {
                if (isEinkDisplay) {
                    // High-contrast clean E-Ink Carta sentence focus guide:
                    // Soft background box + crisp underline per sentence segment
                    val isLight = (fg == Color.BLACK || fg == ReaderTheme.EINK_INK)
                    rulerPaint.style = Paint.Style.FILL
                    rulerPaint.color = if (isLight) 0x22000000 else 0x28FFFFFF
                    for (r in sRects) {
                        c.drawRoundRect(
                            r.left - 2f * dens,
                            r.top - 1f * dens,
                            r.right + 2f * dens,
                            r.bottom + 2f * dens,
                            3f * dens,
                            3f * dens,
                            rulerPaint
                        )
                    }

                    rulerPaint.style = Paint.Style.STROKE
                    rulerPaint.color = fg
                    rulerPaint.strokeWidth = 1.6f * dens
                    for (r in sRects) {
                        val underlineY = r.bottom + 2f * dens
                        c.drawLine(r.left, underlineY, r.right, underlineY, rulerPaint)
                    }
                    // Left bracket on the start of the sentence
                    val first = sRects.first()
                    rulerPaint.strokeWidth = 2.8f * dens
                    val bx = (first.left - 4f * dens).coerceAtLeast(4f * dens)
                    c.drawLine(bx, first.top, bx, first.bottom + 2f * dens, rulerPaint)
                } else {
                    // Exact sentence spotlight mask for LCD/OLED tablets (dims everything outside sentence rects)
                    val focusPath = Path()
                    for (r in sRects) {
                        focusPath.addRoundRect(
                            r.left - 3f * dens,
                            r.top - 2f * dens,
                            r.right + 3f * dens,
                            r.bottom + 2f * dens,
                            4f * dens,
                            4f * dens,
                            Path.Direction.CW
                        )
                    }
                    c.save()
                    c.clipOutPath(focusPath)
                    rulerPaint.style = Paint.Style.FILL
                    rulerPaint.color = 0x3D000000
                    c.drawRect(0f, 0f, w, h, rulerPaint)
                    c.restore()

                    // Subtle warm highlight & underline on the focused sentence
                    val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0x26FFD54F.toInt()
                        style = Paint.Style.FILL
                    }
                    for (r in sRects) {
                        c.drawRoundRect(r.left - 2f * dens, r.top, r.right + 2f * dens, r.bottom, 3f * dens, 3f * dens, hlPaint)
                    }
                }
            }
        }

        // Kindle / Ridi minimalist refined footer
        val footerY = h - (dens * 12f)
        val marginX = dens * 22f
        val isLight = (fg == Color.BLACK || fg == ReaderTheme.EINK_INK)

        val onePtPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PT, 1f, resources.displayMetrics)
        footerPaintLeft.apply {
            color = if (isLight) ReaderTheme.EINK_MUTED else ReaderTheme.DARK_MUTED
            textSize = resources.displayMetrics.scaledDensity * 11.5f + onePtPx
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }

        footerPaintRight.apply {
            color = footerPaintLeft.color
            textSize = footerPaintLeft.textSize
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        if (statusChapter.isNotEmpty()) {
            val maxChLen = (w * 0.52f)
            var dispChapter = statusChapter
            if (footerPaintLeft.measureText(dispChapter) > maxChLen) {
                while (dispChapter.length > 4 && footerPaintLeft.measureText("$dispChapter…") > maxChLen) {
                    dispChapter = dispChapter.dropLast(1)
                }
                dispChapter = "$dispChapter…"
            }
            c.drawText(dispChapter, marginX, footerY, footerPaintLeft)
        }

        if (statusProgress.isNotEmpty()) {
            val prog = if (pomodoroText.isNotEmpty()) ("$pomodoroText  •  $statusProgress") else statusProgress
            c.drawText(prog, w - marginX, footerY, footerPaintRight)
        }

        // Ridi-style ultra-thin bottom reading progress bar (2dp)
        val barH = dens * 2f
        progressTrackPaint.color = if (isLight) 0xFFE5E5E0.toInt() else 0xFF28282C.toInt()
        progressFillPaint.color = if (isEinkDisplay) fg else ReaderTheme.ACCENT_RIDI_BLUE
        c.drawRect(0f, h - barH, w, h, progressTrackPaint)
        c.drawRect(0f, h - barH, w * progressRatio, h, progressFillPaint)
    }

    // ---- touch ----
    private var downX = 0f; private var downY = 0f
    private var mode = 0 // 0 undecided, 1 edge, 2 swipe, 3 selection mode, 4 cancelled, 5 two-finger
    private var edgeSide = 0
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val LONG_PRESS_TIMEOUT_MS = 850L // Taps < 850ms trigger 3x3 touch zones; Holds >= 850ms trigger highlights
    private val longRunnable = Runnable {
        if (mode == 0) {
            mode = 3
            try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
            listener?.onSelectionStart(downX.toInt(), downY.toInt())
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        listener?.onAnyTouch()
        if (e.pointerCount == 2) {
            handler.removeCallbacks(longRunnable)
            if (mode == 3) {
                selectionRects = null
                listener?.onSelectionCancel()
            }
            val midY = (e.getY(0) + e.getY(1)) / 2f
            val midX = (e.getX(0) + e.getX(1)) / 2f
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    downY = midY; downX = midX; mode = 5
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 5) {
                        val dy = -(midY - downY) / height * 1.5f
                        val dx = (midX - downX) / width * 1.5f
                        if (abs(dy) > abs(dx) && abs(dy) > 0.03f) {
                            listener?.onTwoFingerVertical(dy)
                            downY = midY
                        } else if (abs(dx) > 0.04f) {
                            listener?.onTwoFingerHorizontal(dx)
                            downX = midX
                        }
                    }
                }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; mode = 0
                if (readingRulerEnabled) {
                    rulerY = e.y
                    listener?.onRulerMove(e.x, e.y)
                }
                handler.postDelayed(longRunnable, LONG_PRESS_TIMEOUT_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (mode == 3) {
                    listener?.onSelectionDrag(e.x.toInt(), e.y.toInt())
                    return true
                }
                if (readingRulerEnabled && (abs(dy) > slop || abs(dx) > slop) && downX in (width * 0.08f)..(width * 0.92f)) {
                    rulerY = e.y
                    listener?.onRulerMove(e.x, e.y)
                }
                if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    handler.removeCallbacks(longRunnable)
                    val edge = if (downX < width * 0.08f) 0 else if (downX > width * 0.92f) 1 else -1
                    if (edge >= 0 && abs(dy) > abs(dx)) {
                        mode = 1; edgeSide = edge; listener?.onEdgeStart(edge)
                    } else if (abs(dx) > abs(dy)) {
                        mode = 2
                    } else {
                        mode = 4
                    }
                }
                if (mode == 1) listener?.onEdgeMove(edgeSide, -(e.y - downY) / height * 1.2f)
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                val dx = e.x - downX
                when (mode) {
                    0 -> {
                        val handled = listener?.onSingleTap(e.x, e.y) == true
                        if (!handled) {
                            val col = (e.x / width * 3).toInt().coerceIn(0, 2)
                            val row = (e.y / height * 3).toInt().coerceIn(0, 2)
                            listener?.onZone(row, col)
                        }
                    }
                    2 -> if (abs(dx) > resources.displayMetrics.density * 65) listener?.onSwipePage(dx < 0)
                    3 -> {
                        listener?.onSelectionEnd()
                    }
                }
                mode = 4
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                if (mode == 3) {
                    selectionRects = null
                    listener?.onSelectionCancel()
                }
                mode = 4
            }
        }
        return true
    }
}
