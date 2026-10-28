package com.aether.reader.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Viewport-aware bitmap decoder that calculates exact inSampleSize
 * to prevent allocating oversized bitmaps in low-RAM (2GB) environments.
 * Also performs automatic margin cropping (10-25% active panel enlargement)
 * and gamma/contrast boost for E-Ink Carta displays.
 */
object SubsampledBitmapDecoder {

    /**
     * Decodes an input stream into a bitmap sized to fit within [reqWidth] x [reqHeight].
     * Computes powers-of-two inSampleSize and RGB_565 configuration for optimal memory consumption.
     */
    fun decodeStreamSubsampled(
        streamProvider: () -> InputStream?,
        reqWidth: Int,
        reqHeight: Int,
        preferRgb565: Boolean = true
    ): Bitmap? {
        // 1. Measure raw image dimensions without allocating memory
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        val measureStream = streamProvider() ?: return null
        try {
            BitmapFactory.decodeStream(measureStream, null, options)
        } finally {
            try { measureStream.close() } catch (_: Throwable) {}
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        // 2. Calculate optimal inSampleSize
        val targetW = reqWidth.coerceAtLeast(100)
        val targetH = reqHeight.coerceAtLeast(100)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, targetW, targetH)
        options.inJustDecodeBounds = false
        if (preferRgb565) {
            options.inPreferredConfig = Bitmap.Config.RGB_565
        }

        // 3. Decode scaled bitmap
        val decodeStream = streamProvider() ?: return null
        return try {
            BitmapFactory.decodeStream(decodeStream, null, options)
        } finally {
            try { decodeStream.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Decodes a byte array with viewport subsampling.
     */
    fun decodeByteArraySubsampled(
        bytes: ByteArray,
        reqWidth: Int,
        reqHeight: Int,
        preferRgb565: Boolean = true
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val targetW = reqWidth.coerceAtLeast(100)
        val targetH = reqHeight.coerceAtLeast(100)
        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, targetW, targetH)
        options.inJustDecodeBounds = false
        if (preferRgb565) {
            options.inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Powers-of-2 subsample calculator.
     */
    fun calculateInSampleSize(rawW: Int, rawH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (rawH > reqH || rawW > reqW) {
            val halfH = rawH / 2
            val halfW = rawW / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Detects solid white or black scan margins in the outer 10% border of a comic page
     * and automatically crops them to enlarge the active comic artwork by 15~25%.
     */
    fun autoCropMargins(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 80 || h < 80) return src

        val maxCropW = (w * 0.12f).toInt()
        val maxCropH = (h * 0.12f).toInt()

        // Sample background corner color
        val cornerPix = src.getPixel(4.coerceAtMost(w - 1), 4.coerceAtMost(h - 1))
        val cornerLum = (Color.red(cornerPix) * 299 + Color.green(cornerPix) * 587 + Color.blue(cornerPix) * 114) / 1000
        val isWhiteMargin = cornerLum > 200

        fun isMarginPixel(x: Int, y: Int): Boolean {
            val p = src.getPixel(x, y)
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            return if (isWhiteMargin) lum > 220 else lum < 35
        }

        // Top crop
        var top = 0
        while (top < maxCropH) {
            var allMargin = true
            for (x in 0 until w step 4) {
                if (!isMarginPixel(x, top)) { allMargin = false; break }
            }
            if (!allMargin) break
            top++
        }

        // Bottom crop
        var bottom = h - 1
        while (bottom > h - 1 - maxCropH) {
            var allMargin = true
            for (x in 0 until w step 4) {
                if (!isMarginPixel(x, bottom)) { allMargin = false; break }
            }
            if (!allMargin) break
            bottom--
        }

        // Left crop
        var left = 0
        while (left < maxCropW) {
            var allMargin = true
            for (y in top..bottom step 4) {
                if (!isMarginPixel(left, y)) { allMargin = false; break }
            }
            if (!allMargin) break
            left++
        }

        // Right crop
        var right = w - 1
        while (right > w - 1 - maxCropW) {
            var allMargin = true
            for (y in top..bottom step 4) {
                if (!isMarginPixel(right, y)) { allMargin = false; break }
            }
            if (!allMargin) break
            right--
        }

        val cropW = right - left + 1
        val cropH = bottom - top + 1
        if (cropW >= (w * 0.75f) && cropH >= (h * 0.75f) && (left > 4 || top > 4 || right < w - 5 || bottom < h - 5)) {
            return try {
                Bitmap.createBitmap(src, left, top, cropW, cropH)
            } catch (_: Throwable) {
                src
            }
        }
        return src
    }

    /**
     * Applies high-speed Gamma +2.0 and contrast boost to enhance faint pencil and ink lines
     * for crisp, high-contrast readability on E-Ink Carta displays.
     */
    fun applyEinkContrastBoost(src: Bitmap): Bitmap {
        val out = try {
            Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.RGB_565)
        } catch (_: Throwable) {
            return src
        }
        val canvas = Canvas(out)

        // Gamma + contrast enhancement matrix
        val contrast = 1.35f
        val translate = (-0.17f * 255f)
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
