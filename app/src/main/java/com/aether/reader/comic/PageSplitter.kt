package com.aether.reader.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Reading Navigation Directions:
 * - LTR: Western comic / Webtoon (left-to-right swipe, right tap goes forward)
 * - RTL: Japanese Manga (right-to-left swipe, left tap goes forward, hardware keys inverted)
 * - VERTICAL: Continuous vertical webtoon scroll (tablets only)
 */
enum class ReadingDirection { LTR, RTL, VERTICAL }

/**
 * Split mode for double-page spreads.
 */
enum class SplitMode { AUTO_SPLIT, FIT_WIDTH, FIT_HEIGHT, SPREAD_PAIR }

/**
 * Smart Double-Page Spread & Split Logic.
 * 1. Wide Spread Detection:
 *    Inspects aspect ratio: if Width / Height > 1.2, classifies as a double-page spread.
 * 2. Portrait / 6-inch E-Ink Mode:
 *    Automatically splits wide spreads into 2 virtual sub-pages:
 *    - If RTL (Manga): Renders Right Half first, then Left Half.
 *    - If LTR: Renders Left Half first, then Right Half.
 * 3. Landscape / Tablet Spread View:
 *    - Renders intact as full-bleed spread, or pairs 2 sequential pages side-by-side with 0.5px gutter.
 */
object PageSplitter {

    data class VirtualPage(
        val rawPageIndex: Int,
        val part: Part // FULL, LEFT_HALF, RIGHT_HALF
    ) {
        enum class Part { FULL, LEFT_HALF, RIGHT_HALF }
    }

    /**
     * Checks if a page is a wide double-page spread based on its dimensions.
     */
    fun isWideSpread(rawWidth: Int, rawHeight: Int): Boolean {
        if (rawHeight <= 0) return false
        val aspect = rawWidth.toFloat() / rawHeight.toFloat()
        return aspect > 1.2f
    }

    /**
     * Splits or maps raw comic pages into an ordered list of [VirtualPage]s.
     */
    fun generateVirtualPages(
        pageCount: Int,
        isPortraitMode: Boolean,
        direction: ReadingDirection,
        dimensionProvider: ((pageIndex: Int) -> Pair<Int, Int>?)? = null
    ): List<VirtualPage> {
        val virtualList = ArrayList<VirtualPage>()

        for (i in 0 until pageCount) {
            val dims = dimensionProvider?.invoke(i)
            val isWide = if (dims != null) isWideSpread(dims.first, dims.second) else false

            if (isWide && isPortraitMode) {
                // Split into 2 virtual pages
                if (direction == ReadingDirection.RTL) {
                    // Manga: Right half first, then left half
                    virtualList.add(VirtualPage(i, VirtualPage.Part.RIGHT_HALF))
                    virtualList.add(VirtualPage(i, VirtualPage.Part.LEFT_HALF))
                } else {
                    // LTR: Left half first, then right half
                    virtualList.add(VirtualPage(i, VirtualPage.Part.LEFT_HALF))
                    virtualList.add(VirtualPage(i, VirtualPage.Part.RIGHT_HALF))
                }
            } else {
                virtualList.add(VirtualPage(i, VirtualPage.Part.FULL))
            }
        }
        return virtualList
    }

    /**
     * Renders a [VirtualPage] into [targetBmp] with optimal scaling and aspect-fit.
     */
    fun drawVirtualPage(
        sourceBmp: Bitmap,
        part: VirtualPage.Part,
        targetBmp: Bitmap,
        bgColor: Int
    ) {
        targetBmp.eraseColor(bgColor)
        val canvas = Canvas(targetBmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val srcW = sourceBmp.width
        val srcH = sourceBmp.height

        val srcRect = when (part) {
            VirtualPage.Part.FULL -> Rect(0, 0, srcW, srcH)
            VirtualPage.Part.LEFT_HALF -> Rect(0, 0, srcW / 2, srcH)
            VirtualPage.Part.RIGHT_HALF -> Rect(srcW / 2, 0, srcW, srcH)
        }

        val partW = srcRect.width().toFloat().coerceAtLeast(1f)
        val partH = srcRect.height().toFloat().coerceAtLeast(1f)

        val vw = targetBmp.width.toFloat()
        val vh = targetBmp.height.toFloat()

        val scale = minOf(vw / partW, vh / partH)
        val dw = partW * scale
        val dh = partH * scale
        val dx = (vw - dw) / 2f
        val dy = (vh - dh) / 2f

        val dstRect = RectF(dx, dy, dx + dw, dy + dh)
        canvas.drawBitmap(sourceBmp, srcRect, dstRect, paint)
    }

    /**
     * Draws two sequential comic pages side-by-side in landscape tablet view.
     */
    fun drawPairedSpread(
        page1: Bitmap,
        page2: Bitmap,
        direction: ReadingDirection,
        targetBmp: Bitmap,
        bgColor: Int
    ) {
        targetBmp.eraseColor(bgColor)
        val canvas = Canvas(targetBmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val leftBmp = if (direction == ReadingDirection.RTL) page2 else page1
        val rightBmp = if (direction == ReadingDirection.RTL) page1 else page2

        val halfW = (targetBmp.width / 2f)
        val vh = targetBmp.height.toFloat()

        // Draw left page
        val scaleL = minOf(halfW / leftBmp.width, vh / leftBmp.height)
        val dwL = leftBmp.width * scaleL
        val dhL = leftBmp.height * scaleL
        val dxL = halfW - dwL
        val dyL = (vh - dhL) / 2f
        canvas.drawBitmap(leftBmp, null, RectF(dxL, dyL, dxL + dwL, dyL + dhL), paint)

        // Draw right page
        val scaleR = minOf(halfW / rightBmp.width, vh / rightBmp.height)
        val dwR = rightBmp.width * scaleR
        val dhR = rightBmp.height * scaleR
        val dxR = halfW
        val dyR = (vh - dhR) / 2f
        canvas.drawBitmap(rightBmp, null, RectF(dxR, dyR, dxR + dwR, dyR + dhR), paint)

        // Draw subtle 0.5px gutter divider
        val divPaint = Paint().apply {
            color = 0x22888888.toInt()
            strokeWidth = 1f
        }
        canvas.drawLine(halfW, 0f, halfW, vh, divPaint)
    }
}
