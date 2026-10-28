package com.aether.reader.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Underline mark drawn straight into the page bitmap. style: 0 solid, 1 dotted, 2 wavy. */
data class Mark(val start: Int, val end: Int, val style: Int, val color: Int)

/** Search result item for in-book full-text search. */
data class SearchMatch(
    val chapterIndex: Int,
    val chapterTitle: String,
    val charOffset: Long,
    val snippet: String
)

/**
 * Exactly three bitmaps (PREVIOUS, CURRENT, NEXT), allocated once per viewport size and rotated
 * in place on page turns (no copying, no per-page allocation).
 * ALPHA_8 requests are served as RGB_565 because software Canvas text on ALPHA_8 is unreliable
 * across vendor ROMs; at 3 pages RGB_565 still costs only ~9 MB at 1448x1072.
 */
class BitmapPoolImpl : BitmapPool {
    // 4 slots: 0 = PREVIOUS, 1 = CURRENT, 2 = NEXT, 3 = STAGING (off-screen render target)
    private var bmps: Array<Bitmap>? = null

    override fun acquire(slot: BitmapPool.Slot): Bitmap = bmps!![slot.ordinal]

    /** Acquires the off-screen staging bitmap that is never actively bound to ReaderView on the UI thread. */
    fun acquireStaging(): Bitmap = bmps!![3]

    /** Atomically swaps the freshly rendered staging bitmap into the CURRENT slot and returns it. */
    fun commitStagingToCurrent(): Bitmap {
        val b = bmps!!
        val oldCurrent = b[1]
        b[1] = b[3]
        b[3] = oldCurrent
        return b[1]
    }

    override fun rotateForward() { val b = bmps!!; bmps = arrayOf(b[1], b[2], b[0], b[3]) }
    override fun rotateBackward() { val b = bmps!!; bmps = arrayOf(b[2], b[0], b[1], b[3]) }

    override fun reconfigure(width: Int, height: Int, config: Bitmap.Config) {
        val cfg = if (config == Bitmap.Config.ALPHA_8) Bitmap.Config.RGB_565 else config
        val cur = bmps
        if (cur != null && cur[0].width == width && cur[0].height == height && cur[0].config == cfg) return
        bmps = Array(4) { Bitmap.createBitmap(width.coerceAtLeast(10), height.coerceAtLeast(10), cfg) }
    }

    override fun close() { bmps = null }
}

class PagingEngineImpl(private val pool: BitmapPoolImpl) : PagingEngine {

    @Volatile var fgColor: Int = 0xFF000000.toInt()
    @Volatile var bgColor: Int = 0xFFFFFFFF.toInt()
    @Volatile var bitmapConfig: Bitmap.Config = Bitmap.Config.RGB_565
    @Volatile var highlightProvider: (Int) -> List<Mark> = { emptyList() }
    @Volatile var dropCapsEnabled: Boolean = false
    @Volatile var forcedEncoding: String = "AUTO"

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "aether-engine") }
    private val dispatcher = exec.asCoroutineDispatcher()

    private var container: BookContainer? = null
    private var typography: TypographySpec? = null
    private var viewport: Viewport? = null
    private var listener: PagingListener? = null
    override var chapters: List<ChapterInfo> = emptyList()
        private set

    val isImageBased: Boolean get() = container?.isImageBased == true
    val documentStyle: DocumentStyle get() = container?.documentStyle ?: DocumentStyle()

    private class ChLayout(
        val chapter: Int,
        val text: CharSequence,
        val sl: StaticLayout,
        val colStarts: IntArray, // line index where each column starts + sentinel(lineCount)
        val colsPerPage: Int,
        val marginLeftPx: Int,
        val marginTopPx: Int,
        val marginBottomPx: Int
    ) {
        val colCount get() = colStarts.size - 1
        val pageCount get() = max(1, (colCount + colsPerPage - 1) / colsPerPage)
    }

    @Volatile private var cache: ChLayout? = null
    private val layoutCache = LinkedHashMap<Int, ChLayout>(4, 0.75f, true)

    private fun clearLayoutCache() {
        synchronized(layoutCache) { layoutCache.clear() }
        cache = null
    }
    @Volatile private var cur = PageRef(0, 0)
    @Volatile private var curCharOffset: Long = 0L
    @Volatile private var chapterPageCounts = IntArray(0)
    @Volatile private var chapterExactMeasured = BooleanArray(0)
    @Volatile private var layoutEpoch = 0
    private val refs = arrayOfNulls<PageRef>(3) // content currently in PREVIOUS/CURRENT/NEXT
    private val typefaces = HashMap<String, Typeface>()

    private var colW = 0
    private var textH = 0
    private var activeMarginLeft = 0
    private var activeMarginTop = 0
    private var activeMarginBottom = 0

    // ---- layout ---------------------------------------------------------------------------

    private fun typefaceFor(path: String?, docFamily: String, useDocDefaults: Boolean): Typeface {
        if (!useDocDefaults && !path.isNullOrBlank()) {
            return when (path) {
                "SYSTEM_SERIF" -> Typeface.SERIF
                "SYSTEM_SANS" -> Typeface.SANS_SERIF
                else -> typefaces.getOrPut(path) { try { Typeface.createFromFile(path) } catch (_: Exception) { Typeface.SERIF } }
            }
        }
        if (!path.isNullOrBlank()) {
            return when (path) {
                "SYSTEM_SERIF" -> Typeface.SERIF
                "SYSTEM_SANS" -> Typeface.SANS_SERIF
                else -> typefaces.getOrPut(path) { try { Typeface.createFromFile(path) } catch (_: Exception) { Typeface.SERIF } }
            }
        }
        return when (docFamily.lowercase()) {
            "sans-serif" -> Typeface.SANS_SERIF
            "monospace" -> Typeface.MONOSPACE
            else -> Typeface.SERIF
        }
    }

    private fun buildPaint(t: TypographySpec, vp: Viewport, doc: DocumentStyle): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = t.fontSizePt * vp.dpi / 72f
        typeface = typefaceFor(t.fontPath, doc.fontFamily, t.useDocumentDefaults)
        textLocale = java.util.Locale.KOREAN
        isSubpixelText = true
        letterSpacing = if (t.useDocumentDefaults) 0f else t.letterSpacingEm
        color = fgColor
        if (t.boldnessLevel > 0) {            // 10-level weight booster for Carta 1300 & LCD
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    val wght = (400 + t.boldnessLevel * 50).coerceIn(100, 950)
                    fontVariationSettings = "'wght' $wght"
                } catch (_: Throwable) {}
            }
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = textSize * 0.012f * t.boldnessLevel
        }
    }

    /**
     * Deterministic, uniform line-height and paragraph-gap span.
     * - Locks every text line's ascent/descent to exact uniform metrics so lines mixing Korean (CJK fallback)
     *   and English (Latin primary font) never shift baseline or alter line spacing.
     * - Applies paragraph spacing ONLY on the final line of a paragraph (ending with '\n'), never accumulating across wrapped lines.
     * - Preserves full image height on lines containing a block [BookImageSpan].
     */
    private class BookLineHeightSpan(
        private val lockedAscent: Int,
        private val lockedDescent: Int,
        private val paraGapPx: Int
    ) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence?,
            start: Int,
            end: Int,
            spanstartv: Int,
            lineHeight: Int,
            fm: Paint.FontMetricsInt?
        ) {
            fm ?: return
            val isParaEnd = text != null && end > start && text[end - 1] == '\n'
            val extraGap = if (isParaEnd) paraGapPx else 0

            if (text is android.text.Spanned) {
                val imgSpans = text.getSpans(start, end, BookImageSpan::class.java)
                if (imgSpans != null && imgSpans.any { !it.isInline }) {
                    val maxH = imgSpans.filter { !it.isInline }.maxOf { it.drawH }
                    val padV = 10
                    fm.ascent = -(maxH + padV)
                    fm.top = -(maxH + padV)
                    fm.descent = padV + extraGap
                    fm.bottom = padV + extraGap
                    return
                }
            }

            fm.ascent = lockedAscent
            fm.top = lockedAscent
            fm.descent = lockedDescent + extraGap
            fm.bottom = lockedDescent + extraGap
        }
    }

    private fun buildChapterLayout(chapter: Int): ChLayout {
        val c = container!!; val t = typography!!; val vp = viewport!!
        val doc = c.documentStyle
        applyGeometry()
        val paint = buildPaint(t, vp, doc)
        if (c.isImageBased) {
            val dummy = c.chapterText(chapter)
            val sl = StaticLayout.Builder.obtain(dummy, 0, dummy.length, paint, colW).build()
            return ChLayout(chapter, dummy, sl, intArrayOf(0, 1), 1, activeMarginLeft, activeMarginTop, activeMarginBottom)
        }

        val rawSpanned = c.chapterSpanned(chapter)
        val sb = if (rawSpanned is SpannableStringBuilder) rawSpanned else SpannableStringBuilder(rawSpanned)
        if (dropCapsEnabled) {
            DropCapsFormatter.apply(sb)
        }

        // Configure embedded EPUB images to fit current column width and page height
        val imgSpans = sb.getSpans(0, sb.length, BookImageSpan::class.java)
        if (imgSpans != null && imgSpans.isNotEmpty()) {
            val maxImgH = (textH * 0.82f).toInt().coerceAtLeast(80)
            val inlineImgH = (paint.textSize * 1.1f).toInt().coerceAtLeast(12)
            for (img in imgSpans) {
                img.configureViewport(colW, maxImgH, inlineImgH)
            }
        }

        val dens = vp.dpi / 160f
        val effectiveParaPx = if (t.useDocumentDefaults) {
            (doc.paragraphSpacingDp * dens).toInt()
        } else {
            t.paragraphSpacingPx
        }
        val effectiveLineHeight = if (t.useDocumentDefaults) doc.lineHeight else t.lineHeight

        // Lock uniform baseline and line spacing across all Korean/English text lines
        val totalLinePx = (paint.textSize * effectiveLineHeight).toInt().coerceAtLeast(12)
        val glyphAscent = (paint.textSize * 0.82f).toInt().coerceAtLeast(8)
        val glyphDescent = (paint.textSize * 0.22f).toInt().coerceAtLeast(2)
        val extraLeading = (totalLinePx - (glyphAscent + glyphDescent)).coerceAtLeast(0)
        val lockedAscent = -(glyphAscent + extraLeading / 2)
        val lockedDescent = glyphDescent + (extraLeading - extraLeading / 2)

        if (sb.isNotEmpty()) {
            sb.setSpan(
                BookLineHeightSpan(lockedAscent, lockedDescent, effectiveParaPx.coerceAtLeast(0)),
                0,
                sb.length,
                android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE
            )
        }

        val builder = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, colW)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            builder.setUseLineSpacingFromFallbacks(true)
        }
        val sl = builder.build()
        val starts = ArrayList<Int>()
        var line = 0
        val n = sl.lineCount
        while (line < n) {
            starts += line
            val top = sl.getLineTop(line)
            var e = line + 1
            while (e < n && sl.getLineBottom(e) - top <= textH) e++
            line = e
        }
        starts += n
        return ChLayout(chapter, sb, sl, starts.toIntArray(), max(1, vp.columns), activeMarginLeft, activeMarginTop, activeMarginBottom)
    }

    private fun layoutOf(chapter: Int): ChLayout {
        synchronized(layoutCache) {
            val existing = layoutCache[chapter]
            if (existing != null) {
                cache = existing
                return existing
            }
        }
        listener?.onChapterLayoutStarted(chapter)
        val lay = buildChapterLayout(chapter)
        synchronized(layoutCache) {
            if (layoutCache.size >= 4) {
                val oldest = layoutCache.keys.firstOrNull()
                if (oldest != null) layoutCache.remove(oldest)
            }
            layoutCache[chapter] = lay
        }
        cache = lay
        if (chapter in chapterPageCounts.indices) {
            chapterPageCounts[chapter] = if (lay.text.isBlank()) 0 else lay.pageCount
            chapterExactMeasured[chapter] = true
        }
        listener?.onChapterLayoutFinished(chapter, lay.pageCount)
        return lay
    }

    /** Initializes whole-book page count array without blocking the current page render. */
    private fun initWholeBookPagination() {
        val c = container ?: return
        val t = typography ?: return
        val vp = viewport ?: return
        layoutEpoch++

        if (c.isImageBased) {
            chapterPageCounts = IntArray(chapters.size) { 1 }
            chapterExactMeasured = BooleanArray(chapters.size) { true }
            return
        }

        val fontPx = (t.fontSizePt * vp.dpi / 72f).coerceAtLeast(8f)
        val lh = if (t.useDocumentDefaults) c.documentStyle.lineHeight else t.lineHeight
        val linesPerCol = max(1, (textH / (fontPx * lh)).toInt())
        val charsPerLine = max(12, (colW / (fontPx * 0.85f)).toInt())
        val estCharsPerPage = max(120, linesPerCol * charsPerLine * max(1, vp.columns))

        chapterPageCounts = IntArray(chapters.size) { idx ->
            val rawLen = chapters[idx].approxChars
            val estChars = if (c is EpubContainer) (rawLen * 0.55f).toLong() else rawLen
            max(1, ((estChars + estCharsPerPage - 1) / estCharsPerPage).toInt())
        }
        chapterExactMeasured = BooleanArray(chapters.size) { false }
    }

    /**
     * Incrementally measures one unmeasured chapter per background step AFTER the current page
     * and its neighbor pages have already been rendered, so user interactions and relayouts are never blocked.
     */
    private fun scheduleBackgroundPagination(myEpoch: Int, startCh: Int = 0) {
        if (isImageBased || myEpoch != layoutEpoch) return
        exec.execute {
            try {
                if (myEpoch != layoutEpoch || isImageBased) return@execute
                var ch = startCh
                while (ch < chapters.size && chapterExactMeasured.getOrElse(ch) { true }) {
                    ch++
                }
                if (ch >= chapters.size) {
                    if (myEpoch == layoutEpoch) {
                        listener?.onChapterLayoutFinished(cur.chapter, chapterPageCounts.sum().coerceAtLeast(1))
                    }
                    return@execute
                }
                val lay = if (cache?.chapter == ch) cache!! else buildChapterLayout(ch)
                if (myEpoch != layoutEpoch) return@execute
                chapterPageCounts[ch] = if (lay.text.isBlank()) 0 else lay.pageCount
                chapterExactMeasured[ch] = true
                if (ch + 1 < chapters.size) {
                    scheduleBackgroundPagination(myEpoch, ch + 1)
                } else {
                    listener?.onChapterLayoutFinished(cur.chapter, chapterPageCounts.sum().coerceAtLeast(1))
                }
            } catch (_: Throwable) {}
        }
    }

    private fun applyGeometry() {
        val t = typography!!; val vp = viewport!!
        val doc = container?.documentStyle ?: DocumentStyle()
        val dens = vp.dpi / 160f
        val footerPx = (dens * 28f).toInt()

        if (t.useDocumentDefaults) {
            val mh = (doc.marginHorizontalDp * dens).toInt()
            val mv = (doc.marginVerticalDp * dens).toInt()
            activeMarginLeft = mh
            activeMarginTop = mv
            activeMarginBottom = mv + footerPx
        } else {
            activeMarginLeft = t.marginLeftPx
            activeMarginTop = t.marginTopPx
            activeMarginBottom = t.marginBottomPx
        }

        val cols = max(1, vp.columns)
        colW = max(50, (vp.widthPx - activeMarginLeft * 2 - COLUMN_GAP * (cols - 1)) / cols)
        textH = max(50, vp.heightPx - activeMarginTop - activeMarginBottom)
    }

    // ---- rendering ------------------------------------------------------------------------

    private fun renderInto(bmp: Bitmap, ref: PageRef) {
        val cObj = container ?: return
        if (cObj.isImageBased) {
            bmp.eraseColor(if (bgColor == Color.BLACK || bgColor == 0xFF121212.toInt() || bgColor == 0xFF141416.toInt()) Color.BLACK else Color.WHITE)
            cObj.renderPageBitmap(ref.chapter, bmp, bgColor)
            drawPdfMarks(bmp, ref.chapter)
            if (bmp.width > 0 && bmp.height > 0) bmp.setPixel(0, 0, bmp.getPixel(0, 0))
            return
        }
        val lay = layoutOf(ref.chapter)
        bmp.eraseColor(bgColor)
        val c = Canvas(bmp)
        for (k in 0 until lay.colsPerPage) {
            val colIdx = ref.page * lay.colsPerPage + k
            if (colIdx >= lay.colCount) break
            val l0 = lay.colStarts[colIdx]; val l1 = lay.colStarts[colIdx + 1]
            if (l1 <= l0) continue
            val top = lay.sl.getLineTop(l0); val bot = lay.sl.getLineBottom(l1 - 1)
            val charStart = lay.sl.getLineStart(l0)
            val charEnd = lay.sl.getLineEnd(l1 - 1)

            // Detect if this page contains exclusively full-page illustrations / image spans
            val isImageOnlyPage = run {
                if (lay.text !is android.text.Spanned) return@run false
                val spanned = lay.text
                val imgSpans = spanned.getSpans(charStart, charEnd, BookImageSpan::class.java)
                if (imgSpans.isNullOrEmpty()) return@run false
                val subText = lay.text.subSequence(charStart, charEnd)
                subText.all { it.isWhitespace() || it == '\uFFFC' || it == '\uFEFF' }
            }

            val x0 = lay.marginLeftPx + k * (colW + COLUMN_GAP)
            val contentH = bot - top
            val drawTop: Float
            val drawLeft: Float

            if (isImageOnlyPage) {
                // Perfect horizontal and vertical centering for full-page images / illustrations
                drawTop = ((bmp.height - contentH) / 2f).coerceAtLeast(0f)
                drawLeft = if (lay.colsPerPage == 1) ((bmp.width - colW) / 2f).coerceAtLeast(0f) else x0.toFloat()
            } else {
                drawTop = lay.marginTopPx.toFloat()
                drawLeft = x0.toFloat()
            }

            c.save()
            c.clipRect(drawLeft, drawTop, drawLeft + colW, drawTop + contentH)
            c.translate(drawLeft, drawTop - top)
            drawBackgroundMarks(c, lay, l0, l1)
            lay.sl.draw(c)
            drawMarks(c, lay, l0, l1)
            c.restore()
            if (lay.colsPerPage > 1 && k == 0) {
                val divP = Paint().apply {
                    color = if (fgColor == 0xFF000000.toInt() || fgColor == 0xFF111111.toInt()) 0x22000000 else 0x22FFFFFF
                    strokeWidth = 1f
                }
                val gx = (x0 + colW + COLUMN_GAP / 2).toFloat()
                c.drawLine(gx, lay.marginTopPx.toFloat(), gx, (bmp.height - lay.marginBottomPx).toFloat(), divP)
            }
        }
        c.setBitmap(null)
        // Guarantee HardwareRenderer texture cache invalidation AFTER all software glyph drawing completes
        if (bmp.width > 0 && bmp.height > 0) {
            bmp.setPixel(0, 0, bmp.getPixel(0, 0))
        }
    }

    /** Draws background color fills (style == 2) behind the text glyphs before StaticLayout draws. */
    private fun drawBackgroundMarks(c: Canvas, lay: ChLayout, l0: Int, l1: Int) {
        val marks = highlightProvider(lay.chapter).filter { it.style == 2 }
        if (marks.isEmpty()) return
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val sl = lay.sl
        val fontPx = sl.paint.textSize
        val isLight = (fgColor == 0xFF000000.toInt() || fgColor == 0xFF111111.toInt())
        for (m in marks) {
            val baseCol = m.color and 0x00FFFFFF
            bgPaint.color = when {
                baseCol == 0x000000 || baseCol == 0x111111 || baseCol == 0xFFFFFF ->
                    if (isLight) 0x36000000 else 0x44FFFFFF
                else -> (0x55 shl 24) or 0x00FFE066 // warm readable highlighter shade
            }
            for (line in l0 until l1) {
                val ls = sl.getLineStart(line)
                val visEnd = sl.getLineVisibleEnd(line)
                if (visEnd <= ls) continue
                val s = max(m.start, ls)
                val e = min(m.end, visEnd)
                if (s >= e) continue
                val x1 = if (s <= ls) sl.getLineLeft(line) else sl.getPrimaryHorizontal(s)
                val x2 = if (e >= visEnd) sl.getLineRight(line) else sl.getPrimaryHorizontal(e)
                val l = min(x1, x2)
                val r = max(x1, x2)
                val baseline = sl.getLineBaseline(line).toFloat()
                val topY = baseline - fontPx * 0.88f
                val botY = baseline + fontPx * 0.24f
                if (r > l) {
                    c.drawRoundRect(l, topY, r, botY, 4f, 4f, bgPaint)
                }
            }
        }
    }

    /** Draws underline marks (style == 0 solid, style == 1 dotted) after StaticLayout draws. */
    private fun drawMarks(c: Canvas, lay: ChLayout, l0: Int, l1: Int) {
        val marks = highlightProvider(lay.chapter).filter { it.style != 2 }
        if (marks.isEmpty()) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; this.style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val sl = lay.sl
        for (m in marks) {
            p.color = m.color
            p.pathEffect = if (m.style == 1) DashPathEffect(floatArrayOf(2f, 7f), 0f) else null
            for (line in l0 until l1) {
                val ls = sl.getLineStart(line)
                val visEnd = sl.getLineVisibleEnd(line)
                if (visEnd <= ls) continue
                val s = max(m.start, ls); val e = min(m.end, visEnd)
                if (s >= e) continue
                val x1 = if (s <= ls) sl.getLineLeft(line) else sl.getPrimaryHorizontal(s)
                val x2 = if (e >= visEnd) sl.getLineRight(line) else sl.getPrimaryHorizontal(e)
                val y = sl.getLineBaseline(line) + 5f
                c.drawLine(min(x1, x2), y, max(x1, x2), y, p)
            }
        }
    }

    /**
     * Accurately detects sentence/line bounds on an image-based (PDF) page bitmap around (xPx, yPx).
     * Returns RectF(leftX, topY, rightX, bottomY) in page bitmap coordinates.
     */
    private fun detectPdfSentenceBounds(bmp: Bitmap?, pageBounds: RectF, xPx: Int, yPx: Int): RectF {
        val pageW = pageBounds.width().coerceAtLeast(1f)
        val pageH = pageBounds.height().coerceAtLeast(1f)
        val defaultLineH = (pageH * 0.022f).coerceAtLeast(18f)
        val halfH = defaultLineH * 0.5f

        val cx = xPx.toFloat().coerceIn(pageBounds.left, pageBounds.right)
        val cy = yPx.toFloat().coerceIn(pageBounds.top, pageBounds.bottom)

        if (bmp == null || bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) {
            val padW = pageW * 0.05f
            return RectF(pageBounds.left + padW, cy - halfH, pageBounds.right - padW, cy + halfH)
        }

        val ix = cx.toInt().coerceIn(0, bmp.width - 1)
        val iy = cy.toInt().coerceIn(0, bmp.height - 1)

        // Sample background luminance from the page top margin
        val cornerX = (pageBounds.left + 16f).toInt().coerceIn(0, bmp.width - 1)
        val cornerY = (pageBounds.top + 16f).toInt().coerceIn(0, bmp.height - 1)
        val bgPix = bmp.getPixel(cornerX, cornerY)
        val bgLum = (Color.red(bgPix) * 299 + Color.green(bgPix) * 587 + Color.blue(bgPix) * 114) / 1000

        fun isTextPix(x: Int, y: Int): Boolean {
            if (x !in 0 until bmp.width || y !in 0 until bmp.height) return false
            val p = bmp.getPixel(x, y)
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            return kotlin.math.abs(lum - bgLum) > 30
        }

        // 1. Find text row around cy within vertical search radius
        val radiusY = (defaultLineH * 1.5f).toInt().coerceIn(12, 60)
        val searchLeft = (cx - pageW * 0.15f).toInt().coerceIn(pageBounds.left.toInt(), pageBounds.right.toInt())
        val searchRight = (cx + pageW * 0.15f).toInt().coerceIn(pageBounds.left.toInt(), pageBounds.right.toInt())
        val stepX = 4

        var bestY = iy
        var maxDarkCount = 0
        for (y in (iy - radiusY)..(iy + radiusY)) {
            if (y !in 0 until bmp.height) continue
            var count = 0
            var x = searchLeft
            while (x <= searchRight) {
                if (isTextPix(x, y)) count++
                x += stepX
            }
            if (count > maxDarkCount) {
                maxDarkCount = count
                bestY = y
            }
        }

        // 2. Expand vertical bounds from bestY to find exact line top & baseline
        var topY = bestY
        var botY = bestY
        val maxLineSpan = (defaultLineH * 1.25f).toInt()
        if (maxDarkCount >= 2) {
            while (topY > pageBounds.top && (bestY - topY) < maxLineSpan) {
                var hasText = false
                var x = searchLeft
                while (x <= searchRight) {
                    if (isTextPix(x, topY)) { hasText = true; break }
                    x += stepX
                }
                if (!hasText) break
                topY--
            }
            while (botY < pageBounds.bottom && (botY - bestY) < maxLineSpan) {
                var hasText = false
                var x = searchLeft
                while (x <= searchRight) {
                    if (isTextPix(x, botY)) { hasText = true; break }
                    x += stepX
                }
                if (!hasText) break
                botY++
            }
        } else {
            topY = (cy - halfH * 0.85f).toInt()
            botY = (cy + halfH * 0.85f).toInt()
        }

        // 3. Scan horizontally along [topY, botY] to find sentence start & end
        var minX = ix
        var maxX = ix

        // Scan left
        var gapLeft = 0
        val maxGap = (pageW * 0.045f).toInt().coerceIn(16, 45)
        var curX = ix
        while (curX >= pageBounds.left) {
            var colHasText = false
            for (y in topY..botY step 2) {
                if (isTextPix(curX, y)) { colHasText = true; break }
            }
            if (colHasText) {
                minX = curX
                gapLeft = 0
            } else {
                gapLeft++
                if (gapLeft > maxGap) break
            }
            curX -= 2
        }

        // Scan right
        var gapRight = 0
        curX = ix
        while (curX <= pageBounds.right) {
            var colHasText = false
            for (y in topY..botY step 2) {
                if (isTextPix(curX, y)) { colHasText = true; break }
            }
            if (colHasText) {
                maxX = curX
                gapRight = 0
            } else {
                gapRight++
                if (gapRight > maxGap) break
            }
            curX += 2
        }

        val padX = (pageW * 0.008f).coerceAtLeast(6f)
        val finalLeft = (minX.toFloat() - padX).coerceAtLeast(pageBounds.left + pageW * 0.04f)
        val finalRight = (maxX.toFloat() + padX).coerceAtMost(pageBounds.right - pageW * 0.04f)
        val clampedRight = finalRight.coerceAtLeast(finalLeft + pageW * 0.12f)

        return RectF(finalLeft, topY.toFloat() - 2f, clampedRight, botY.toFloat() + 2f)
    }

    /**
     * Detects the line vertical span (topY, bottomY) in PDF around yPx.
     */
    private fun detectPdfLineVerticalBounds(bmp: Bitmap?, pageBounds: RectF, xPx: Int, yPx: Int): Pair<Float, Float> {
        val pageH = pageBounds.height().coerceAtLeast(1f)
        val defaultLineH = (pageH * 0.022f).coerceAtLeast(18f)
        val halfH = defaultLineH * 0.5f

        val cy = yPx.toFloat().coerceIn(pageBounds.top, pageBounds.bottom)
        if (bmp == null || bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) {
            return (cy - halfH) to (cy + halfH)
        }

        val ix = xPx.coerceIn(0, bmp.width - 1)
        val iy = cy.toInt().coerceIn(0, bmp.height - 1)

        val cornerX = (pageBounds.left + 16f).toInt().coerceIn(0, bmp.width - 1)
        val cornerY = (pageBounds.top + 16f).toInt().coerceIn(0, bmp.height - 1)
        val bgPix = bmp.getPixel(cornerX, cornerY)
        val bgLum = (Color.red(bgPix) * 299 + Color.green(bgPix) * 587 + Color.blue(bgPix) * 114) / 1000

        fun isTextPix(x: Int, y: Int): Boolean {
            if (x !in 0 until bmp.width || y !in 0 until bmp.height) return false
            val p = bmp.getPixel(x, y)
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            return kotlin.math.abs(lum - bgLum) > 30
        }

        val radiusY = (defaultLineH * 1.5f).toInt().coerceIn(12, 60)
        val searchLeft = (ix - (pageBounds.width() * 0.12f).toInt()).coerceIn(pageBounds.left.toInt(), pageBounds.right.toInt())
        val searchRight = (ix + (pageBounds.width() * 0.12f).toInt()).coerceIn(pageBounds.left.toInt(), pageBounds.right.toInt())
        val stepX = 4

        var bestY = iy
        var maxDarkCount = 0
        for (y in (iy - radiusY)..(iy + radiusY)) {
            if (y !in 0 until bmp.height) continue
            var count = 0
            var x = searchLeft
            while (x <= searchRight) {
                if (isTextPix(x, y)) count++
                x += stepX
            }
            if (count > maxDarkCount) {
                maxDarkCount = count
                bestY = y
            }
        }

        var topY = bestY
        var botY = bestY
        val maxLineSpan = (defaultLineH * 1.25f).toInt()
        if (maxDarkCount >= 2) {
            while (topY > pageBounds.top && (bestY - topY) < maxLineSpan) {
                var hasText = false
                var x = searchLeft
                while (x <= searchRight) {
                    if (isTextPix(x, topY)) { hasText = true; break }
                    x += stepX
                }
                if (!hasText) break
                topY--
            }
            while (botY < pageBounds.bottom && (botY - bestY) < maxLineSpan) {
                var hasText = false
                var x = searchLeft
                while (x <= searchRight) {
                    if (isTextPix(x, botY)) { hasText = true; break }
                    x += stepX
                }
                if (!hasText) break
                botY++
            }
        } else {
            topY = (cy - halfH).toInt()
            botY = (cy + halfH).toInt()
        }

        return (topY.toFloat() - 2f) to (botY.toFloat() + 2f)
    }

    // ---- PDF text-band analysis (shared by live selection and saved highlights) ------------

    private var pdfBandsKey: String? = null
    private var pdfBands: List<RectF> = emptyList()

    /**
     * Detects the real text lines of an image-based page: one RectF per line band, limited to the horizontal
     * extent of that line's ink. Bands never overlap, so highlights built from them never stack or spill
     * into the page margins. Cached per (container, chapter, bitmap size).
     */
    private fun pdfTextBands(chapter: Int, bmp: Bitmap?, bounds: RectF): List<RectF> {
        if (bmp == null || bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return emptyList()
        val key = "${System.identityHashCode(container)}:$chapter:${bmp.width}x${bmp.height}"
        if (key == pdfBandsKey) return pdfBands

        val w = bmp.width
        val h = bmp.height
        val x0 = bounds.left.toInt().coerceIn(0, w - 1)
        val x1 = bounds.right.toInt().coerceIn(x0 + 1, w)
        val y0 = bounds.top.toInt().coerceIn(0, h - 1)
        val y1 = bounds.bottom.toInt().coerceIn(y0 + 1, h)
        val rowLen = x1 - x0

        val cornerPix = bmp.getPixel((x0 + 16).coerceIn(0, w - 1), (y0 + 16).coerceIn(0, h - 1))
        val bgLum = (Color.red(cornerPix) * 299 + Color.green(cornerPix) * 587 + Color.blue(cornerPix) * 114) / 1000

        val rowBuf = IntArray(rowLen)
        val rowMin = IntArray(h) { -1 }
        val rowMax = IntArray(h) { -1 }
        for (y in y0 until y1) {
            bmp.getPixels(rowBuf, 0, rowLen, x0, y, rowLen, 1)
            var mn = -1; var mx = -1; var cnt = 0
            for (i in 0 until rowLen) {
                val p = rowBuf[i]
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (kotlin.math.abs(lum - bgLum) > 40) {
                    if (mn < 0) mn = i
                    mx = i
                    cnt++
                }
            }
            if (cnt >= 2) { rowMin[y] = x0 + mn; rowMax[y] = x0 + mx }
        }

        val nominalLineH = (bounds.height().coerceAtLeast(1f) * 0.022f).coerceAtLeast(18f)
        val mergeGap = 3 // rows: bridges i-dots / accents inside one line
        val raw = ArrayList<FloatArray>() // top, bottom, left, right
        var y = y0
        while (y < y1) {
            if (rowMin[y] < 0) { y++; continue }
            var top = y; var bot = y
            var l = rowMin[y]; var r = rowMax[y]
            var gap = 0
            var yy = y + 1
            while (yy < y1 && gap <= mergeGap) {
                if (rowMin[yy] >= 0) {
                    bot = yy; gap = 0
                    if (rowMin[yy] < l) l = rowMin[yy]
                    if (rowMax[yy] > r) r = rowMax[yy]
                } else gap++
                yy++
            }
            if (bot - top >= 3) raw.add(floatArrayOf(top.toFloat(), (bot + 1).toFloat(), l.toFloat(), (r + 1).toFloat()))
            y = bot + 1
        }

        // Split abnormally tall bands (touching lines) into evenly sized lines.
        val split = ArrayList<FloatArray>()
        for (b in raw) {
            val bh = b[1] - b[0]
            if (bh > nominalLineH * 2.4f) {
                val n = (bh / nominalLineH).toInt().coerceAtLeast(2)
                val step = bh / n
                for (k in 0 until n) split.add(floatArrayOf(b[0] + step * k, b[0] + step * (k + 1), b[2], b[3]))
            } else split.add(b)
        }

        // Pad vertically by up to 2px, never beyond half of the gap to the neighbour => bands cannot overlap.
        val out = ArrayList<RectF>(split.size)
        for (i in split.indices) {
            val b = split[i]
            val gapAbove = if (i > 0) b[0] - split[i - 1][1] else 4f
            val gapBelow = if (i < split.size - 1) split[i + 1][0] - b[1] else 4f
            val padT = minOf(2f, gapAbove / 2f).coerceAtLeast(0f)
            val padB = minOf(2f, gapBelow / 2f).coerceAtLeast(0f)
            out.add(RectF(b[2], b[0] - padT, b[3], b[1] + padB))
        }

        pdfBandsKey = key
        pdfBands = out
        return out
    }

    /**
     * Builds non-overlapping selection rectangles for an image-based page between two packed anchors
     * (packed as yNorm*1000*10000 + xNorm*1000, both anchors referencing a line top).
     */
    private fun pdfSelectionRects(
        chapter: Int, bmp: Bitmap?, bounds: RectF, startChar: Long, endChar: Long, bandSource: Bitmap? = bmp
    ): List<RectF> {
        val pageW = bounds.width().coerceAtLeast(1f)
        val pageH = bounds.height().coerceAtLeast(1f)
        val lineH = (pageH * 0.022f).coerceAtLeast(18f)

        val py0 = ((startChar / 10000) / 1000f).coerceIn(0f, 1f)
        val px0 = ((startChar % 10000) / 1000f).coerceIn(0f, 1f)
        val py1 = ((endChar / 10000) / 1000f).coerceIn(0f, 1f)
        val px1 = ((endChar % 10000) / 1000f).coerceIn(0f, 1f)

        val startFirst = py0 < py1 || (py0 == py1 && px0 <= px1)
        val firstPy = if (startFirst) py0 else py1
        val firstPx = if (startFirst) px0 else px1
        val lastPy = if (startFirst) py1 else py0
        val lastPx = if (startFirst) px1 else px0

        val yFirst = bounds.top + pageH * firstPy
        val yLast = bounds.top + pageH * lastPy
        val xFirst = bounds.left + pageW * firstPx
        val xLast = bounds.left + pageW * lastPx
        val tol = maxOf(4f, pageH * 0.004f)

        val bands = pdfTextBands(chapter, bandSource, bounds)
        val picked = bands.filter { it.bottom > yFirst + tol && it.top <= yLast + tol }
        if (picked.isEmpty()) {
            // No ink found (blank/scanned area): single clean line box at the anchor.
            val l = minOf(xFirst, xLast)
            val r = maxOf(xFirst, xLast)
            return if (r > l) listOf(RectF(l, yFirst, r, yFirst + lineH)) else emptyList()
        }

        val rects = ArrayList<RectF>()
        if (picked.size == 1) {
            val b = picked[0]
            val l = minOf(xFirst, xLast).coerceIn(b.left, b.right)
            val r = maxOf(xFirst, xLast).coerceIn(b.left, b.right)
            if (r > l) rects.add(RectF(l, b.top, r, b.bottom))
            return rects
        }
        for ((i, b) in picked.withIndex()) {
            val l = if (i == 0) xFirst.coerceIn(b.left, b.right) else b.left
            val r = if (i == picked.size - 1) xLast.coerceIn(b.left, b.right) else b.right
            if (r > l) rects.add(RectF(l, b.top, r, b.bottom))
        }
        return rects
    }

    /** Draws highlights and underlines onto rendered image-based/PDF page bitmaps. */
    private fun drawPdfMarks(bmp: Bitmap, chapter: Int) {
        val marks = highlightProvider(chapter)
        if (marks.isEmpty()) return
        val cObj = container ?: return
        val bounds = cObj.getPageBounds(chapter, bmp.width, bmp.height)
            ?: RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val c = Canvas(bmp)
        val isLight = (fgColor == 0xFF000000.toInt() || fgColor == 0xFF111111.toInt())
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 3.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        val pageH = bounds.height().coerceAtLeast(1f)
        val pageW = bounds.width().coerceAtLeast(1f)
        // Analyse text rows on the clean page bitmap first (highlights drawn below would pollute detection).
        val textMapBmp = bmp
        pdfTextBands(chapter, bmp, bounds)

        for (m in marks) {
            val rectList: List<RectF> = if (m.start >= 10000 || m.end >= 10000) {
                pdfSelectionRects(chapter, bmp, bounds, m.start.toLong(), m.end.toLong(), textMapBmp)
            } else {
                val sNorm = (m.start / 1000f).coerceIn(0f, 1f)
                val eNorm = (m.end / 1000f).coerceIn(0f, 1f)
                val topNorm = minOf(sNorm, eNorm)
                val botNorm = maxOf(sNorm, eNorm)
                val y0 = bounds.top + pageH * topNorm
                val y1 = (bounds.top + pageH * botNorm).coerceAtLeast(y0 + 14f)
                listOf(RectF(bounds.left + pageW * 0.05f, y0, bounds.right - pageW * 0.05f, y1))
            }

            if (m.style == 2) {
                // Background highlight
                val baseCol = m.color and 0x00FFFFFF
                val markAlpha = if (isLight) 0x44 else 0x55
                bgPaint.color = when {
                    baseCol == 0x000000 || baseCol == 0x111111 -> if (isLight) 0x33000000 else 0x44FFFFFF
                    baseCol == 0xFFFFFF -> if (isLight) 0x33000000 else 0x44FFFFFF
                    else -> (markAlpha shl 24) or baseCol
                }
                for (r in rectList) {
                    c.drawRoundRect(r, 4f, 4f, bgPaint)
                }
            } else {
                // Underline (0 = solid, 1 = dotted)
                strokePaint.color = m.color
                strokePaint.pathEffect = if (m.style == 1) DashPathEffect(floatArrayOf(4f, 8f), 0f) else null
                for (r in rectList) {
                    val lineY = r.bottom - 1f
                    c.drawLine(r.left, lineY, r.right, lineY, strokePaint)
                }
            }
        }
    }

    // ---- navigation helpers ---------------------------------------------------------------

    private fun pageStartChar(lay: ChLayout, page: Int): Int {
        val colIdx = page * lay.colsPerPage
        return if (colIdx >= lay.colCount) 0 else lay.sl.getLineStart(lay.colStarts[colIdx])
    }

    private fun pageForChar(lay: ChLayout, off: Long): Int {
        var lo = 0; var hi = lay.pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pageStartChar(lay, mid) <= off) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun nextRef(r: PageRef): PageRef? {
        if (isImageBased) {
            return if (r.chapter + 1 < chapters.size) PageRef(r.chapter + 1, 0) else null
        }
        val lay = layoutOf(r.chapter)
        if (r.page + 1 < lay.pageCount) return PageRef(r.chapter, r.page + 1)
        var ch = r.chapter + 1
        while (ch < chapters.size) {
            val l = layoutOf(ch)
            if (l.text.isNotBlank()) return PageRef(ch, 0)
            ch++
        }
        cache = null; layoutOf(r.chapter)
        return null
    }

    private fun prevRef(r: PageRef): PageRef? {
        if (isImageBased) {
            return if (r.chapter > 0) PageRef(r.chapter - 1, 0) else null
        }
        if (r.page > 0) return PageRef(r.chapter, r.page - 1)
        var ch = r.chapter - 1
        while (ch >= 0) {
            val l = layoutOf(ch)
            if (l.text.isNotBlank()) return PageRef(ch, l.pageCount - 1)
            ch--
        }
        cache = null; layoutOf(r.chapter)
        return null
    }

    private fun ready(ref: PageRef) = PageResult.Ready(pool.acquire(BitmapPool.Slot.CURRENT), ref) { }

    private fun rotateRefsForward() { refs[0] = refs[1]; refs[1] = refs[2]; refs[2] = null }
    private fun rotateRefsBackward() { refs[2] = refs[1]; refs[1] = refs[0]; refs[0] = null }

    private fun schedulePrefetch() {
        val myEpoch = layoutEpoch
        exec.execute {
            try {
                if (myEpoch != layoutEpoch) return@execute
                if (!isImageBased) {
                    val lay = cache
                    val c = cur
                    if (lay != null && lay.chapter == c.chapter) {
                        if (c.page + 1 < lay.pageCount) {
                            val n = PageRef(c.chapter, c.page + 1)
                            if (refs[2] != n) { renderInto(pool.acquire(BitmapPool.Slot.NEXT), n); refs[2] = n }
                        } else refs[2] = null
                        if (c.page > 0) {
                            val p = PageRef(c.chapter, c.page - 1)
                            if (refs[0] != p) { renderInto(pool.acquire(BitmapPool.Slot.PREVIOUS), p); refs[0] = p }
                        } else refs[0] = null
                    }
                }
                scheduleBackgroundPagination(myEpoch, 0)
            } catch (_: Throwable) { }
        }
    }

    private fun show(ref: PageRef): PageResult {
        // Render into off-screen staging bitmap so the UI thread's active CURRENT bitmap is never mutated in place
        val staging = pool.acquireStaging()
        renderInto(staging, ref)
        pool.commitStagingToCurrent()
        cur = ref; refs[0] = null; refs[1] = ref; refs[2] = null
        if (!isImageBased) {
            cache?.let { if (it.chapter == ref.chapter) curCharOffset = pageStartChar(it, ref.page).toLong() }
        } else {
            curCharOffset = 0L
        }
        schedulePrefetch()
        return ready(ref)
    }

    // ---- PagingEngine ---------------------------------------------------------------------

    override suspend fun open(path: String, format: BookFormat, typography: TypographySpec, viewport: Viewport, context: android.content.Context?) {
        withContext(dispatcher) {
            container?.close()
            clearLayoutCache()
            java.util.Arrays.fill(refs, null)
            val c = Containers.open(path, context, forcedEncoding, format)
            container = c
            chapters = c.chapters
            this@PagingEngineImpl.typography = typography
            this@PagingEngineImpl.viewport = viewport
            pool.reconfigure(viewport.widthPx, viewport.heightPx, bitmapConfig)
            applyGeometry()
            initWholeBookPagination()
            cur = PageRef(0, 0)
            curCharOffset = 0L
        }
    }

    val bookTitle: String get() = container?.title ?: ""
    val bookAuthor: String get() = container?.author ?: ""
    val bookTranslator: String get() = container?.translator ?: ""
    val bookPublisher: String get() = container?.publisher ?: ""
    val bookPublishYear: String get() = container?.publishYear ?: ""
    fun coverBytes(): ByteArray? = container?.coverBytes()

    override suspend fun goTo(location: BookLocation): PageResult = withContext(dispatcher) {
        try {
            if (chapters.isEmpty()) return@withContext PageResult.EndOfBook
            var ch = location.chapterIndex.coerceIn(0, chapters.size - 1)
            if (isImageBased) {
                curCharOffset = 0L
                return@withContext show(PageRef(ch, 0))
            }
            var lay = layoutOf(ch)
            while (lay.text.isBlank() && ch + 1 < chapters.size) { ch++; lay = layoutOf(ch) }
            val pg = pageForChar(lay, location.charOffset)
            curCharOffset = pageStartChar(lay, pg).toLong()
            show(PageRef(ch, pg))
        } catch (t: Throwable) { PageResult.Failure(t) }
    }

    /** Navigates directly to a 1-based global page across the entire book. */
    suspend fun goToGlobalPage(globalPageOneBased: Int): PageResult = withContext(dispatcher) {
        try {
            if (chapters.isEmpty()) return@withContext PageResult.EndOfBook
            if (isImageBased) {
                val ch = (globalPageOneBased - 1).coerceIn(0, chapters.size - 1)
                return@withContext show(PageRef(ch, 0))
            }
            var remaining = (globalPageOneBased - 1).coerceAtLeast(0)
            for (ch in chapters.indices) {
                val cnt = if (chapterExactMeasured.getOrElse(ch) { false }) {
                    chapterPageCounts[ch]
                } else {
                    val l = layoutOf(ch)
                    if (l.text.isBlank()) 0 else l.pageCount
                }
                if (cnt <= 0) continue
                if (remaining < cnt) {
                    return@withContext show(PageRef(ch, remaining))
                }
                remaining -= cnt
            }
            val lastCh = (chapters.size - 1).coerceAtLeast(0)
            val lastLay = layoutOf(lastCh)
            show(PageRef(lastCh, (lastLay.pageCount - 1).coerceAtLeast(0)))
        } catch (t: Throwable) { PageResult.Failure(t) }
    }

    override suspend fun next(): PageResult = withContext(dispatcher) {
        try {
            val n = nextRef(cur) ?: return@withContext PageResult.EndOfBook
            if (!isImageBased && refs[2] == n && n.chapter == cur.chapter) {
                pool.rotateForward(); rotateRefsForward(); cur = n
                cache?.let { if (it.chapter == n.chapter) curCharOffset = pageStartChar(it, n.page).toLong() }
                schedulePrefetch(); ready(n)
            } else show(n)
        } catch (t: Throwable) { PageResult.Failure(t) }
    }

    override suspend fun previous(): PageResult = withContext(dispatcher) {
        try {
            val p = prevRef(cur) ?: return@withContext PageResult.StartOfBook
            if (!isImageBased && refs[0] == p && p.chapter == cur.chapter) {
                pool.rotateBackward(); rotateRefsBackward(); cur = p
                cache?.let { if (it.chapter == p.chapter) curCharOffset = pageStartChar(it, p.page).toLong() }
                schedulePrefetch(); ready(p)
            } else show(p)
        } catch (t: Throwable) { PageResult.Failure(t) }
    }

    suspend fun refreshCurrent(): PageResult = withContext(dispatcher) {
        try { show(cur) } catch (t: Throwable) { PageResult.Failure(t) }
    }

    /** Returns (currentGlobalPage, totalBookPages) across the entire book. */
    fun pageInfo(): Pair<Int, Int> {
        if (chapters.isEmpty()) return 1 to 1
        if (isImageBased) return (cur.chapter + 1) to max(1, chapters.size)
        val c = cur
        val counts = chapterPageCounts
        if (counts.isEmpty()) {
            val lay = cache ?: return 1 to 1
            return (c.page + 1) to lay.pageCount
        }
        val before = (0 until c.chapter.coerceAtMost(counts.size)).sumOf { counts[it] }
        val globalCur = before + c.page + 1
        val globalTotal = max(globalCur, counts.sum().coerceAtLeast(1))
        return globalCur to globalTotal
    }

    /** 1-based global start page for a given chapter. */
    fun chapterStartPage(ch: Int): Int {
        if (chapters.isEmpty()) return 1
        if (isImageBased) return (ch + 1).coerceIn(1, max(1, chapters.size))
        val counts = chapterPageCounts
        if (counts.isEmpty()) return 1
        val before = (0 until ch.coerceIn(0, counts.size)).sumOf { counts[it] }
        return before + 1
    }

    override fun currentLocation(): BookLocation {
        val c = cur
        val (globalPg, globalTotal) = pageInfo()
        val progress = if (globalTotal <= 1) 1f else ((globalPg - 1).toFloat() / (globalTotal - 1).toFloat()).coerceIn(0f, 1f)
        if (isImageBased) {
            return BookLocation(c.chapter, 0L, progress)
        }
        val lay = cache
        val off = if (lay != null && lay.chapter == c.chapter) {
            pageStartChar(lay, c.page).toLong().also { curCharOffset = it }
        } else {
            curCharOffset
        }
        return BookLocation(c.chapter, off, progress)
    }

    fun currentPageText(): String {
        if (isImageBased) return container?.chapterText(cur.chapter) ?: ""
        val lay = cache ?: return ""
        if (lay.chapter != cur.chapter) return ""
        val s = pageStartChar(lay, cur.page)
        val e = if (cur.page + 1 < lay.pageCount) pageStartChar(lay, cur.page + 1) else lay.text.length
        return lay.text.subSequence(s, max(s, e)).toString()
    }

    fun currentPageCharRange(): Pair<Long, Long>? {
        if (isImageBased) return Pair(0L, Long.MAX_VALUE)
        val lay = cache ?: return null
        if (lay.chapter != cur.chapter) return null
        val s = pageStartChar(lay, cur.page).toLong()
        val e = (if (cur.page + 1 < lay.pageCount) pageStartChar(lay, cur.page + 1) else lay.text.length).toLong()
        return Pair(s, max(s + 1L, e))
    }

    fun footnotesForCurrentChapter(): Map<String, String> =
        container?.footnotesForChapter(cur.chapter) ?: emptyMap()

    suspend fun search(query: String, maxResults: Int = 40): List<SearchMatch> = withContext(dispatcher) {
        val q = query.trim()
        val cObj = container ?: return@withContext emptyList()
        if (q.isEmpty() || cObj.isImageBased) return@withContext emptyList()
        val results = ArrayList<SearchMatch>()
        for (ch in chapters.indices) {
            val text = cObj.chapterText(ch)
            var idx = text.indexOf(q, 0, ignoreCase = true)
            while (idx >= 0) {
                val snipStart = max(0, idx - 24)
                val snipEnd = min(text.length, idx + q.length + 36)
                val snippet = text.substring(snipStart, snipEnd).replace('\n', ' ').trim()
                results += SearchMatch(ch, chapterTitle(ch), idx.toLong(), snippet)
                if (results.size >= maxResults) return@withContext results
                idx = text.indexOf(q, idx + q.length, ignoreCase = true)
            }
        }
        results
    }

    fun chapterTitle(i: Int): String = chapters.getOrNull(i)?.title ?: ""
    fun chapterText(i: Int): String = container?.chapterText(i) ?: ""

    override suspend fun relayout(typography: TypographySpec, viewport: Viewport) {
        withContext(dispatcher) {
            cache?.let {
                if (!isImageBased && it.chapter == cur.chapter) {
                    curCharOffset = pageStartChar(it, cur.page).toLong()
                }
            }
            this@PagingEngineImpl.typography = typography
            this@PagingEngineImpl.viewport = viewport
            clearLayoutCache()
            java.util.Arrays.fill(refs, null)
            pool.reconfigure(viewport.widthPx, viewport.heightPx, bitmapConfig)
            applyGeometry()
            initWholeBookPagination()
            listener?.onPagesInvalidated()
        }
    }

    private fun offsetAt(x: Int, y: Int): Pair<ChLayout, Int>? {
        if (isImageBased) return null
        val lay = cache ?: return null
        if (lay.chapter != cur.chapter) return null
        val sl = lay.sl
        val fontPx = sl.paint.textSize
        for (k in 0 until lay.colsPerPage) {
            val x0 = lay.marginLeftPx + k * (colW + COLUMN_GAP)
            if (x < x0 - COLUMN_GAP / 2 || x > x0 + colW + COLUMN_GAP / 2) continue
            val colIdx = cur.page * lay.colsPerPage + k
            if (colIdx >= lay.colCount) return null
            val l0 = lay.colStarts[colIdx]; val l1 = lay.colStarts[colIdx + 1]
            if (l1 <= l0) return null
            val top = sl.getLineTop(l0)
            val relY = y - lay.marginTopPx + top
            var line = sl.getLineForVertical(relY).coerceIn(l0, l1 - 1)
            // Snap to the line whose visual glyph center is closest to relY (avoids paraGap/leading drift)
            var bestLine = line
            var bestDist = Float.MAX_VALUE
            for (cand in max(l0, line - 1)..min(l1 - 1, line + 1)) {
                if (sl.getLineVisibleEnd(cand) <= sl.getLineStart(cand)) continue
                val glyphCenter = sl.getLineBaseline(cand) - fontPx * 0.35f
                val dist = kotlin.math.abs(relY - glyphCenter)
                if (dist < bestDist) {
                    bestDist = dist
                    bestLine = cand
                }
            }
            line = bestLine
            val ls = sl.getLineStart(line)
            val visEnd = sl.getLineVisibleEnd(line)
            if (visEnd <= ls) return lay to ls
            val rawOff = sl.getOffsetForHorizontal(line, (x - x0).toFloat().coerceIn(0f, colW.toFloat()))
            val off = rawOff.coerceIn(ls, visEnd - 1)
            return lay to off
        }
        return null
    }

    override fun hitTest(xPx: Int, yPx: Int): TextRange? {
        val cObj = container
        if (isImageBased && cObj != null) {
            val vp = viewport ?: return null
            val bounds = cObj.getPageBounds(cur.chapter, vp.widthPx, vp.heightPx)
                ?: RectF(0f, 0f, vp.widthPx.toFloat(), vp.heightPx.toFloat())
            val currentBmp = try { pool.acquire(BitmapPool.Slot.CURRENT) } catch (_: Throwable) { null }
            val (lineTopY, lineBotY) = detectPdfLineVerticalBounds(currentBmp, bounds, xPx, yPx)

            val pageW = bounds.width().coerceAtLeast(1f)
            val pageH = bounds.height().coerceAtLeast(1f)

            val py0 = (((lineTopY - bounds.top) / pageH).coerceIn(0f, 1f) * 1000).toInt()
            // Both anchors store the line TOP so selection bands can be matched unambiguously.
            val py1 = py0
            val px0 = (((xPx.toFloat() - bounds.left) / pageW).coerceIn(0f, 1f) * 1000).toInt()
            // Initial anchor for drag: px0 ~ px0 + 15 (single word / point)
            val px1 = (px0 + 15).coerceAtMost(1000)

            val start = py0.toLong() * 10000L + px0.toLong()
            val end = py1.toLong() * 10000L + px1.toLong()

            val pageNo = cur.chapter + 1
            val quote = "PDF ${pageNo}페이지 문장 선택"
            return TextRange(cur.chapter, start, end, quote)
        }
        val (lay, off) = offsetAt(xPx, yPx) ?: return null
        val text = lay.text
        val range = DropCapsFormatter.findSentenceRange(text, off) ?: return null
        return TextRange(lay.chapter, range.start.toLong(), range.end.toLong(), text.subSequence(range.start, range.end).toString().trim())
    }

    override fun extendSelection(range: TextRange, toXPx: Int, toYPx: Int): TextRange {
        val cObj = container
        if (isImageBased && cObj != null) {
            val vp = viewport ?: return range
            val bounds = cObj.getPageBounds(cur.chapter, vp.widthPx, vp.heightPx)
                ?: RectF(0f, 0f, vp.widthPx.toFloat(), vp.heightPx.toFloat())
            val currentBmp = try { pool.acquire(BitmapPool.Slot.CURRENT) } catch (_: Throwable) { null }
            val (lineTopY, lineBotY) = detectPdfLineVerticalBounds(currentBmp, bounds, toXPx, toYPx)

            val pageW = bounds.width().coerceAtLeast(1f)
            val pageH = bounds.height().coerceAtLeast(1f)

            val pyTarget = (((lineTopY - bounds.top) / pageH).coerceIn(0f, 1f) * 1000).toInt()
            val pxTarget = (((toXPx.toFloat() - bounds.left) / pageW).coerceIn(0f, 1f) * 1000).toInt()

            val startPy = (range.startChar / 10000).toInt()
            val startPx = (range.startChar % 10000).toInt()

            val newStart: Long
            val newEnd: Long
            if (pyTarget < startPy || (pyTarget == startPy && pxTarget < startPx)) {
                // Dragged backwards
                newStart = pyTarget.toLong() * 10000L + pxTarget.toLong()
                newEnd = range.endChar
            } else {
                // Dragged forwards
                newStart = range.startChar
                newEnd = pyTarget.toLong() * 10000L + pxTarget.toLong()
            }

            val pageNo = cur.chapter + 1
            val quote = "PDF ${pageNo}페이지 문장 선택"
            return TextRange(cur.chapter, newStart, newEnd, quote)
        }
        val (lay, off) = offsetAt(toXPx, toYPx) ?: return range
        val s = min(range.startChar.toInt(), off); val e = max(range.endChar.toInt(), off)
        return TextRange(lay.chapter, s.toLong(), e.toLong(), lay.text.subSequence(s, e).toString().trim())
    }

    override fun sentenceBoundsAt(xPx: Int, yPx: Int): List<RectF>? {
        val cObj = container
        if (isImageBased && cObj != null) {
            val vp = viewport ?: return null
            val bounds = cObj.getPageBounds(cur.chapter, vp.widthPx, vp.heightPx)
                ?: RectF(0f, 0f, vp.widthPx.toFloat(), vp.heightPx.toFloat())
            val currentBmp = try { pool.acquire(BitmapPool.Slot.CURRENT) } catch (_: Throwable) { null }
            val rect = detectPdfSentenceBounds(currentBmp, bounds, xPx, yPx)
            return listOf(rect)
        }
        val lay = cache ?: return null
        if (lay.chapter != cur.chapter) return null
        val sl = lay.sl
        val text = lay.text
        if (text.isEmpty()) return null

        val baseColIdx = cur.page * lay.colsPerPage
        if (baseColIdx >= lay.colCount) return null

        var targetCol = 0
        for (k in 0 until lay.colsPerPage) {
            if (baseColIdx + k >= lay.colCount) break
            val cx0 = lay.marginLeftPx + k * (colW + COLUMN_GAP)
            if (xPx >= cx0 - COLUMN_GAP / 2) targetCol = k
        }
        val activeColIdx = baseColIdx + targetCol
        val l0 = lay.colStarts[activeColIdx]
        val l1 = lay.colStarts[min(activeColIdx + 1, lay.colStarts.size - 1)]
        if (l1 <= l0) return null

        val colTop = sl.getLineTop(l0)
        val x0 = lay.marginLeftPx + targetCol * (colW + COLUMN_GAP)
        val fontPx = sl.paint.textSize
        val relY = (yPx - lay.marginTopPx + colTop).coerceIn(colTop, sl.getLineBottom(l1 - 1) - 1)
        val initialLine = sl.getLineForVertical(relY).coerceIn(l0, l1 - 1)

        // Pick non-empty line on this page whose visual glyph center is closest to relY
        var touchedLine = -1
        var bestDist = Float.MAX_VALUE
        for (cand in l0 until l1) {
            if (sl.getLineVisibleEnd(cand) <= sl.getLineStart(cand)) continue
            val glyphCenter = sl.getLineBaseline(cand) - fontPx * 0.35f
            val dist = kotlin.math.abs(relY - glyphCenter)
            if (dist < bestDist) {
                bestDist = dist
                touchedLine = cand
            }
        }
        if (touchedLine < 0) touchedLine = initialLine

        val ls0 = sl.getLineStart(touchedLine)
        val visEnd0 = sl.getLineVisibleEnd(touchedLine)
        if (visEnd0 <= ls0) return null

        val rawOff = sl.getOffsetForHorizontal(touchedLine, (xPx - x0).toFloat().coerceIn(0f, colW.toFloat()))
        val off = rawOff.coerceIn(ls0, visEnd0 - 1)

        val range = DropCapsFormatter.findSentenceRange(text, off) ?: return null
        val s = range.start
        val e = range.end
        if (s >= e) return null

        val sLine = max(sl.getLineForOffset(s), l0)
        val eLine = min(sl.getLineForOffset(e - 1), l1 - 1)
        val rects = ArrayList<RectF>()

        for (line in sLine..eLine) {
            val ls = sl.getLineStart(line)
            val visEnd = sl.getLineVisibleEnd(line)
            if (visEnd <= ls) continue
            val lineS = max(s, ls)
            val lineE = min(e, visEnd)
            if (lineS >= lineE) continue

            val x1 = if (lineS <= ls) sl.getLineLeft(line) else sl.getPrimaryHorizontal(lineS)
            val x2 = if (lineE >= visEnd) sl.getLineRight(line) else sl.getPrimaryHorizontal(lineE)
            val l = min(x1, x2) + x0
            val r = max(x1, x2) + x0
            val baseline = (sl.getLineBaseline(line) - colTop + lay.marginTopPx).toFloat()
            val t = baseline - fontPx * 0.90f
            val b = baseline + fontPx * 0.28f
            if (r > l && b > t) {
                rects.add(RectF(l, t, r, b))
            }
        }
        return if (rects.isNotEmpty()) rects else null
    }

    override fun selectionBounds(range: TextRange): List<RectF>? {
        val cObj = container
        if (isImageBased && cObj != null) {
            val vp = viewport ?: return null
            val bounds = cObj.getPageBounds(cur.chapter, vp.widthPx, vp.heightPx)
                ?: RectF(0f, 0f, vp.widthPx.toFloat(), vp.heightPx.toFloat())
            val currentBmp = try { pool.acquire(BitmapPool.Slot.CURRENT) } catch (_: Throwable) { null }
            val rects = pdfSelectionRects(cur.chapter, currentBmp, bounds, range.startChar, range.endChar)
            return if (rects.isNotEmpty()) rects else null
        }

        val lay = cache ?: return null
        if (lay.chapter != cur.chapter) return null
        val sl = lay.sl
        val s = min(range.startChar.toInt(), range.endChar.toInt())
        val e = max(range.startChar.toInt(), range.endChar.toInt())
        if (s >= e) return null

        val rects = ArrayList<RectF>()
        val fontPx = sl.paint.textSize
        for (k in 0 until lay.colsPerPage) {
            val colIdx = cur.page * lay.colsPerPage + k
            if (colIdx >= lay.colCount) break
            val l0 = lay.colStarts[colIdx]
            val l1 = lay.colStarts[colIdx + 1]
            if (l1 <= l0) continue
            val colTop = sl.getLineTop(l0)
            val x0 = lay.marginLeftPx + k * (colW + COLUMN_GAP)

            val sLine = max(sl.getLineForOffset(s), l0)
            val eLine = min(sl.getLineForOffset(e - 1), l1 - 1)
            for (line in sLine..eLine) {
                val ls = sl.getLineStart(line)
                val visEnd = sl.getLineVisibleEnd(line)
                if (visEnd <= ls) continue
                val lineS = max(s, ls)
                val lineE = min(e, visEnd)
                if (lineS >= lineE) continue

                val x1 = if (lineS <= ls) sl.getLineLeft(line) else sl.getPrimaryHorizontal(lineS)
                val x2 = if (lineE >= visEnd) sl.getLineRight(line) else sl.getPrimaryHorizontal(lineE)
                val l = min(x1, x2) + x0
                val r = max(x1, x2) + x0
                val baseline = (sl.getLineBaseline(line) - colTop + lay.marginTopPx).toFloat()
                val t = baseline - fontPx * 0.90f
                val b = baseline + fontPx * 0.28f
                if (r > l && b > t) {
                    rects.add(RectF(l, t, r, b))
                }
            }
        }
        return if (rects.isNotEmpty()) rects else null
    }

    override fun setListener(listener: PagingListener?) { this.listener = listener }

    override fun close() {
        layoutEpoch++
        container?.close(); container = null; pool.close(); exec.shutdown()
    }

    companion object { const val COLUMN_GAP = 40 }
}
