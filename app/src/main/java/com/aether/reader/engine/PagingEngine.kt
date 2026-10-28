package com.aether.reader.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import java.io.Closeable

/**
 * Custom ReplacementSpan for rendering embedded EPUB images inside StaticLayout.
 * - Small images (< 64x64 px) are treated as inline glyphs aligned to the text line without altering line spacing.
 * - Normal illustrations (>= 64px) are scaled proportionally to fit the column width and max page height,
 *   centered horizontally on their own line.
 */
class BookImageSpan(val bitmap: Bitmap) : ReplacementSpan() {
    var colWidthPx: Int = 600
    var maxHeightPx: Int = 700
    var inlineTextHeightPx: Int = 28
    var drawW: Int = bitmap.width.coerceAtLeast(1)
        private set
    var drawH: Int = bitmap.height.coerceAtLeast(1)
        private set

    val isInline: Boolean
        get() = bitmap.width in 1..63 && bitmap.height in 1..63

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun configureViewport(colW: Int, maxH: Int, inlineH: Int) {
        colWidthPx = colW.coerceAtLeast(60)
        maxHeightPx = maxH.coerceAtLeast(80)
        inlineTextHeightPx = inlineH.coerceAtLeast(12)
        val bw = bitmap.width.coerceAtLeast(1)
        val bh = bitmap.height.coerceAtLeast(1)
        val aspect = bh.toFloat() / bw.toFloat()

        if (isInline) {
            drawH = inlineTextHeightPx
            drawW = (drawH / aspect).toInt().coerceIn(8, colWidthPx)
        } else {
            var targetW = if (bw >= 120) colWidthPx else (bw * 2).coerceAtMost(colWidthPx)
            var targetH = (targetW * aspect).toInt()
            if (targetH > maxHeightPx) {
                targetH = maxHeightPx
                targetW = (targetH / aspect).toInt().coerceIn(1, colWidthPx)
            }
            drawW = targetW.coerceAtLeast(1)
            drawH = targetH.coerceAtLeast(1)
        }
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (isInline) {
            return drawW + 4
        }
        if (fm != null) {
            val padV = 10
            fm.ascent = -(drawH + padV)
            fm.top = -(drawH + padV)
            fm.descent = padV
            fm.bottom = padV
        }
        return colWidthPx
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        if (bitmap.isRecycled) return
        if (isInline) {
            val left = x + 2f
            val lineCenterY = (top + bottom) / 2f
            val topY = lineCenterY - drawH / 2f
            canvas.drawBitmap(bitmap, null, RectF(left, topY, left + drawW, topY + drawH), bmpPaint)
        } else {
            val left = x + (colWidthPx - drawW) / 2f
            val availH = (bottom - top).coerceAtLeast(drawH)
            val topY = top + (availH - drawH) / 2f
            canvas.drawBitmap(bitmap, null, RectF(left, topY, left + drawW, topY + drawH), bmpPaint)
        }
    }
}

enum class BookFormat { EPUB, TXT, PDF, MOBI, AZW3, CBZ, CBR, MD }

/**
 * Style rules extracted directly from the book file (EPUB CSS/HTML or TXT layout).
 * Used when [TypographySpec.useDocumentDefaults] is enabled so the viewer follows
 * the file's native font family, paragraph spacing, line spacing, and margins by default.
 */
data class DocumentStyle(
    val fontFamily: String = "serif",        // "serif", "sans-serif", "monospace"
    val lineHeight: Float = 1.42f,           // Multiplier from document CSS
    val paragraphSpacingDp: Int = 6,         // Paragraph gap from document CSS (0 for raw TXT)
    val marginHorizontalDp: Int = 18,        // Left/Right margin from document CSS
    val marginVerticalDp: Int = 16           // Top/Bottom margin from document CSS
)

/** Everything that changes pagination; its hash keys the persisted PageIndex. */
data class TypographySpec(
    val useDocumentDefaults: Boolean = true, // true = follow book file's native style settings
    val fontPath: String?,                   // null = bundled/document default; else /sdcard/Fonts/*.ttf|otf
    val fontSizePt: Int,                     // 1pt steps
    val lineHeight: Float,
    val paragraphSpacingPx: Int,
    val letterSpacingEm: Float,
    val marginLeftPx: Int, val marginTopPx: Int,
    val marginRightPx: Int, val marginBottomPx: Int,
    val boldnessLevel: Int                   // 0..10
) {
    fun layoutHash(viewport: Viewport): Int =
        listOf(this, viewport).hashCode()
}

data class Viewport(val widthPx: Int, val heightPx: Int, val columns: Int, val dpi: Int)

/** Stable position independent of layout: survives font/size changes. */
data class BookLocation(
    val chapterIndex: Int,
    val charOffset: Long,       // offset in chapter's normalized text stream
    val progress: Float         // 0..1 overall, for display / sync
)

data class PageRef(val chapter: Int, val page: Int)

data class ChapterInfo(val index: Int, val title: String, val approxChars: Long)

/** Text selection returned by hit-testing; used by Module C. */
data class TextRange(val chapterIndex: Int, val startChar: Long, val endChar: Long, val text: String)

sealed interface PageResult {
    /** Slot index is owned by the pool; call [release] when done presenting. */
    class Ready(val bitmap: Bitmap, val ref: PageRef, val release: () -> Unit) : PageResult
    object EndOfBook : PageResult
    object StartOfBook : PageResult
    class Failure(val cause: Throwable) : PageResult
}

/**
 * Fixed pool: exactly 3 slots (PREVIOUS, CURRENT, NEXT). Bitmaps are allocated
 * once and re-used via native `AndroidBitmap_lockPixels`; no per-page allocation.
 */
interface BitmapPool : Closeable {
    enum class Slot { PREVIOUS, CURRENT, NEXT }
    val capacity: Int get() = 3
    fun acquire(slot: Slot): Bitmap
    /** Rotate after a forward/backward turn without copying pixels. */
    fun rotateForward()
    fun rotateBackward()
    fun reconfigure(width: Int, height: Int, config: Bitmap.Config)
}

interface PagingListener {
    fun onChapterLayoutStarted(chapter: Int) {}
    fun onChapterLayoutFinished(chapter: Int, pageCount: Int) {}
    fun onPagesInvalidated() {}
}

/**
 * Chunk-streaming pager. Only one chapter's layout is resident; neighbouring
 * chapters are opened lazily when the prefetch window crosses a boundary.
 * All methods are safe to call from the main thread; heavy work is dispatched
 * to the engine's own `layout`/`render` threads.
 */
interface PagingEngine : Closeable {
    suspend fun open(path: String, format: BookFormat, typography: TypographySpec, viewport: Viewport, context: android.content.Context? = null)
    val chapters: List<ChapterInfo>

    suspend fun goTo(location: BookLocation): PageResult
    suspend fun next(): PageResult
    suspend fun previous(): PageResult

    fun currentLocation(): BookLocation
    /** Re-paginate after typography/viewport change, keeping the reader's place. */
    suspend fun relayout(typography: TypographySpec, viewport: Viewport)

    fun hitTest(xPx: Int, yPx: Int): TextRange?
    fun extendSelection(range: TextRange, toXPx: Int, toYPx: Int): TextRange
    fun sentenceBoundsAt(xPx: Int, yPx: Int): List<RectF>? = null
    fun selectionBounds(range: TextRange): List<RectF>? = null

    fun setListener(listener: PagingListener?)
}
