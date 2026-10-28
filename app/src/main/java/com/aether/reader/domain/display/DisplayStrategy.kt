package com.aether.reader.domain.display

import android.graphics.Bitmap

/** Which family of panel the app is running on. */
enum class DisplayKind { MONO_EINK, COLOR_EINK, LCD_OLED }

/** Long-side / short-side ratio buckets. Luna X (1448x1072 = 1.351) falls in RATIO_4_3. */
enum class AspectClass(val ratio: Float) {
    RATIO_4_3(1.333f), RATIO_3_2(1.5f), RATIO_16_10(1.6f), RATIO_16_9(1.778f), ULTRAWIDE(2.0f);
    companion object {
        fun classify(r: Float): AspectClass = entries.minByOrNull { kotlin.math.abs(it.ratio - r) }!!
    }
}

enum class PageTransition { INSTANT, SLIDE, SCROLL, CURL_3D }

enum class RefreshMode { PARTIAL, FULL_INVERT, FULL_GC16 }

/** Immutable snapshot of detected display metrics. */
data class DisplayMetricsInfo(
    val widthPx: Int,
    val heightPx: Int,
    val ppi: Float,
    val refreshRatesHz: List<Float>,
    val isLandscape: Boolean,
    val isOled: Boolean,
    val isEinkHint: Boolean,
    val isColorEinkHint: Boolean
) {
    val aspectRatio: Float get() = maxOf(widthPx, heightPx).toFloat() / minOf(widthPx, heightPx)
    val aspectClass: AspectClass get() = AspectClass.classify(aspectRatio)
}

/** Pixel format + post-processing the rasterizer must honour. */
data class RenderSpec(
    val config: Bitmap.Config,       // ALPHA_8 for mono, RGB_565/ARGB_8888 otherwise
    val grayLevels: Int,             // 2, 16 or 256
    val boldnessLevel: Int,          // 0..10 (font weight booster)
    val illustrationSaturation: Float, // 1.0 = off, 1.15..1.30 for Kaleido 3
    val illustrationGamma: Float,
    val colorLayerSeparate: Boolean, // text on B/W layer, images on color buffer
    val highlightDithering: Boolean  // pattern-dither the 4 highlight colors
)

data class LayoutSpec(
    val columns: Int,                // 1 or 2 (book spread)
    val marginScale: Float,          // multiplier on user margins (aspect-dependent)
    val maxLineWidthEm: Float,       // readable measure cap for wide/4:3 landscape
    val allowDarkMode: Boolean,
    val trueBlackBackground: Boolean
)

/** Receives instructions for panel-specific refresh; implemented in platform layer. */
interface PanelRefreshController {
    fun requestRefresh(mode: RefreshMode)
}

/**
 * Strategy contract for a display family. Stateless w.r.t. book content; the
 * only mutable state is user-tunable settings injected via [DisplaySettings].
 */
interface DisplayStrategy {
    val kind: DisplayKind
    val transition: PageTransition
    val transitionDurationMs: Int       // 0 for E-Ink
    val renderSpec: RenderSpec
    val layoutSpec: LayoutSpec
    val targetFrameRateHz: Float

    /** Called after every page swap. Implementations decide whether a full refresh is due. */
    fun onPagePresented(pageNumberSincePurge: Int, refresh: PanelRefreshController)

    /** Whether the supplied transition requested by user prefs is permitted on this panel. */
    fun resolveTransition(requested: PageTransition): PageTransition

    /** Pixel post-processing hook for embedded illustrations (no-op on mono / LCD). */
    fun processIllustration(src: Bitmap): Bitmap = src
}

data class DisplaySettings(
    val fullRefreshEveryNPages: Int = 6,   // clamp 1..30
    val boldnessLevel: Int = 0,            // clamp 0..10
    val preferredTransition: PageTransition = PageTransition.SLIDE,
    val forcedKind: DisplayKind? = null,   // user override of auto-detect
    val devicePreset: String = "AUTO",     // AUTO, ONYX_LEAF_7, LUNA_X_78, STANDARD_6, LARGE_103, GENERIC_TABLET
    val ghostClearEnabled: Boolean = true  // LCD/OLED: user opt-in only
)

interface DisplayProfileManager {
    /** Inspect system props/modes/metrics and return the active strategy. */
    fun detect(settings: DisplaySettings): DisplayStrategy
    fun metrics(): DisplayMetricsInfo
    /** Re-run detection on config change / display hot-plug / fold state change. */
    fun addListener(listener: (DisplayStrategy) -> Unit)
    fun removeListener(listener: (DisplayStrategy) -> Unit)
}
