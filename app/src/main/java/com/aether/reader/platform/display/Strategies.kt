package com.aether.reader.platform.display

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import com.aether.reader.domain.display.*

/**
 * Aspect-aware layout: 4:3 panels (Luna X, iPad-class tablets) are "squarer", so
 * they get a slightly larger margin and a 2-column spread only in landscape where
 * each column still holds a comfortable measure. 16:9 / 16:10 / ultrawide
 * landscape always spread; portrait is always a single column.
 */
internal fun layoutFor(m: DisplayMetricsInfo, allowDarkMode: Boolean, trueBlack: Boolean, allowSpread: Boolean = true): LayoutSpec {
    val spread = allowSpread && m.isLandscape && when (m.aspectClass) {
        AspectClass.RATIO_4_3 -> m.widthPx >= 1600       // small 4:3 e-ink (1448 px) stays single; big tablets spread
        else -> true
    }
    val (marginScale, measure) = when (m.aspectClass) {
        AspectClass.RATIO_4_3 -> 1.1f to 38f
        AspectClass.RATIO_3_2 -> 1.05f to 40f
        AspectClass.RATIO_16_10 -> 1.0f to 42f
        AspectClass.RATIO_16_9 -> 0.95f to 44f
        AspectClass.ULTRAWIDE -> 0.9f to 46f
    }
    return LayoutSpec(if (spread) 2 else 1, marginScale, measure, allowDarkMode, trueBlack)
}

class MonoEInkStrategy(
    private val m: DisplayMetricsInfo,
    private val s: DisplaySettings
) : DisplayStrategy {
    override val kind = DisplayKind.MONO_EINK
    override val transition = PageTransition.INSTANT
    override val transitionDurationMs = 0
    override val targetFrameRateHz = 60f
    override val renderSpec = RenderSpec(
        config = Bitmap.Config.ALPHA_8, grayLevels = 16,
        boldnessLevel = s.boldnessLevel.coerceIn(0, 10),
        illustrationSaturation = 1f, illustrationGamma = 1f,
        colorLayerSeparate = false, highlightDithering = false
    )
    // E-Ink: always white page / black text; dark mode is just panel inversion handled by refresh.
    override val layoutSpec = layoutFor(m, allowDarkMode = false, trueBlack = false)

    override fun resolveTransition(requested: PageTransition) = PageTransition.INSTANT

    override fun onPagePresented(pageNumberSincePurge: Int, refresh: PanelRefreshController) {
        val n = s.fullRefreshEveryNPages.coerceIn(1, 30)
        if (pageNumberSincePurge >= n) refresh.requestRefresh(RefreshMode.FULL_INVERT)
        else refresh.requestRefresh(RefreshMode.PARTIAL)
    }

    override fun processIllustration(src: Bitmap): Bitmap = src // grayscale handled in rasterizer
}

class ColorEInkStrategy(
    private val m: DisplayMetricsInfo,
    private val s: DisplaySettings
) : DisplayStrategy {
    override val kind = DisplayKind.COLOR_EINK
    override val transition = PageTransition.INSTANT
    override val transitionDurationMs = 0
    override val targetFrameRateHz = 60f
    override val renderSpec = RenderSpec(
        config = Bitmap.Config.ARGB_8888, grayLevels = 16,
        boldnessLevel = s.boldnessLevel.coerceIn(0, 10),
        illustrationSaturation = 1.25f,   // +15..30% -> Kaleido 3 washes colors out
        illustrationGamma = 0.85f,
        colorLayerSeparate = true,         // text -> 300 PPI B/W layer, images -> color layer
        highlightDithering = true
    )
    override val layoutSpec = layoutFor(m, allowDarkMode = false, trueBlack = false)
    override fun resolveTransition(requested: PageTransition) = PageTransition.INSTANT

    override fun onPagePresented(pageNumberSincePurge: Int, refresh: PanelRefreshController) {
        val n = s.fullRefreshEveryNPages.coerceIn(1, 30)
        // Color layer ghosts more than mono: purge with GC16 rather than a plain invert.
        refresh.requestRefresh(if (pageNumberSincePurge >= n) RefreshMode.FULL_GC16 else RefreshMode.PARTIAL)
    }

    private val boost = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val sat = ColorMatrix().apply { setSaturation(renderSpec.illustrationSaturation) }
        val g = renderSpec.illustrationGamma
        // Approximate gamma/contrast lift with scale+offset (cheap, no LUT on 2GB device).
        val c = 1f + (1f - g)
        val t = 128f * (1f - c)
        val contrast = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,  0f, c, 0f, 0f, t,  0f, 0f, c, 0f, t,  0f, 0f, 0f, 1f, 0f))
        sat.postConcat(contrast)
        colorFilter = ColorMatrixColorFilter(sat)
    }

    override fun processIllustration(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, boost)
        return out
    }
}

class LcdOledStrategy(
    private val m: DisplayMetricsInfo,
    private val s: DisplaySettings,
    private val darkMode: Boolean
) : DisplayStrategy {
    override val kind = DisplayKind.LCD_OLED
    private val maxHz = m.refreshRatesHz.maxOrNull() ?: 60f
    override val targetFrameRateHz = maxHz.coerceAtMost(120f)
    override val transition = PageTransition.INSTANT
    override val transitionDurationMs = 0
    override val renderSpec = RenderSpec(
        config = Bitmap.Config.ARGB_8888, grayLevels = 256,
        boldnessLevel = s.boldnessLevel.coerceIn(0, 10),
        illustrationSaturation = 1f, illustrationGamma = 1f,
        colorLayerSeparate = false, highlightDithering = false
    )
    override val layoutSpec = layoutFor(m, allowDarkMode = true, trueBlack = darkMode && m.isOled)

    override fun resolveTransition(requested: PageTransition): PageTransition = PageTransition.INSTANT

    override fun onPagePresented(pageNumberSincePurge: Int, refresh: PanelRefreshController) {
        // LCD/OLED have no ghosting: purge only if the user explicitly enabled it (default off).
        if (!s.ghostClearEnabled) return
        val n = s.fullRefreshEveryNPages.coerceIn(1, 30)
        if (pageNumberSincePurge >= n) refresh.requestRefresh(RefreshMode.FULL_INVERT)
    }
}
