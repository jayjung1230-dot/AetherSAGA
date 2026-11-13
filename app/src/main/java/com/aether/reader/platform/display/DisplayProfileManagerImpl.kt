package com.aether.reader.platform.display

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.aether.reader.domain.display.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot

/**
 * Detects the active panel family once, then re-detects on configuration change.
 * No reliable public API tells E-Ink from LCD, so detection is layered:
 *   1. user override  2. known vendor/model table  3. system-property hints  4. LCD default.
 */
class DisplayProfileManagerImpl(
    private val context: Context,
    private val darkModeProvider: () -> Boolean = {
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
) : DisplayProfileManager {

    private val listeners = CopyOnWriteArrayList<(DisplayStrategy) -> Unit>()
    @Volatile private var lastSettings = DisplaySettings()
    @Volatile private var current: DisplayStrategy? = null

    private val einkKeywords = listOf(
        "epdc", "epd", "e-ink", "eink", "ed060", "ed078",
        "rk3566", "rk3326", "rk3026", "rk3188", "rockchip", "allwinner", "sun8iw10p1", "sprd"
    )
    private val colorHints = listOf("color", "colour", "ceink")

    private fun sysProp(key: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
    } catch (_: Throwable) { "" }

    override fun metrics(widthPx: Int?, heightPx: Int?): DisplayMetricsInfo {
        val dm = DisplayMetrics()
        val display: Display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") display.getRealMetrics(dm)
        var ppi = (dm.xdpi + dm.ydpi) / 2f
        val rates = display.supportedModes.map { it.refreshRate }.distinct().sorted()
        val id = (Build.MANUFACTURER + " " + Build.MODEL + " " + Build.BRAND + " " + Build.DEVICE + " " +
            Build.PRODUCT + " " + Build.HARDWARE + " " +
            sysProp("ro.product.board") + " " + sysProp("ro.hardware.eink") + " " +
            sysProp("ro.board.platform") + " " + sysProp("ro.boot.hardware")).lowercase()

        val rawW = if (widthPx != null && widthPx > 0) widthPx else dm.widthPixels
        val rawH = if (heightPx != null && heightPx > 0) heightPx else dm.heightPixels
        val maxDim = maxOf(rawW, rawH)
        val minDim = minOf(rawW, rawH)

        val isFlipResolution =
            (maxDim in 2600..2680 && minDim in 1050..1120) || // Clamshell Main (2640x1080, 22:9)
            (maxDim in 730..760 && minDim in 700..735) ||     // Clamshell Cover (748x720)
            (maxDim in 500..525 && minDim in 250..270)        // Compact Clamshell Cover (512x260)

        val isFoldResolution =
            (maxDim in 2140..2220 && minDim in 1780..1990) || // Foldable Main (2176x1812, 2156x1856, 2184x1968)
            (maxDim in 2250..2550 && minDim in 820..1090)     // Foldable Cover (2316x904, 2376x968, 2520x1080)

        val isFoldFamily = id.contains("fold") || id.contains("flip") ||
            isFlipResolution || isFoldResolution

        val hasEinkVendorHint = einkKeywords.any { id.contains(it) }

        val isKnownLcdOledBrand = !hasEinkVendorHint && (
            isFoldFamily || id.contains("sdk_gphone") || id.contains("emulator")
        )

        val hasEinkSysProp = sysProp("ro.vendor.eink").isNotEmpty() ||
            sysProp("ro.hardware.eink").isNotEmpty() ||
            sysProp("ro.epd.present").isNotEmpty() ||
            sysProp("ro.vendor.epd").isNotEmpty() ||
            sysProp("sys.eink.app.name").isNotEmpty() ||
            sysProp("persist.sys.eink").isNotEmpty() ||
            sysProp("ro.platform.has.epd").isNotEmpty() ||
            sysProp("ro.boot.epd").isNotEmpty()

        // Check standard E-Ink panel resolutions:
        val isStandardEinkResolution = !isKnownLcdOledBrand && (
            (maxDim in 1420..1480 && minDim in 1050..1100) || // 6.0" 1448x1072
            (maxDim in 1650..1710 && minDim in 1230..1290) || // 7.0" 1680x1264
            (maxDim in 1840..1900 && minDim in 1380..1430) || // 7.8" & 10.3" 1872x1404
            (maxDim in 1000..1050 && minDim in 740..780)   || // 6.0" 1024x758
            (maxDim in 1620..1670 && minDim in 1210..1260) || // 6.8" 1648x1236
            (maxDim in 1180..1220 && minDim in 810..840)   || // 6.0" 1200x825
            (maxDim in 2450..2520 && minDim in 1830..1890) || // 13.3" 2480x1860
            (maxDim in 780..820 && minDim in 580..620)        // 800x600 (Legacy E-Ink)
        )

        val einkHint = !isKnownLcdOledBrand && (hasEinkVendorHint || hasEinkSysProp || isStandardEinkResolution)
        if (isStandardEinkResolution && (ppi < 200f || ppi > 450f)) {
            ppi = if (maxDim in 1000..1050) 212f else 300f
        }
        val colorHint = einkHint && colorHints.any { id.contains(it) }
        val diag = hypot(rawW / dm.xdpi.coerceAtLeast(160f), rawH / dm.ydpi.coerceAtLeast(160f))
        val oled = isFoldFamily || (!einkHint && diag < 14f && display.isWideColorGamut)
        if (isFoldFamily && (ppi < 200f || ppi > 600f)) {
            ppi = when {
                maxDim in 730..760 && minDim in 700..735 -> 306f // Clamshell Cover
                id.contains("flip") || isFlipResolution -> 425f // Clamshell Main
                maxDim.toFloat() / minDim.toFloat() < 1.35f -> 374f // Foldable Main
                else -> 402f // Foldable Cover
            }
        }
        return DisplayMetricsInfo(
            widthPx = rawW, heightPx = rawH, ppi = ppi,
            refreshRatesHz = rates, isLandscape = rawW > rawH,
            isOled = oled, isEinkHint = einkHint, isColorEinkHint = colorHint
        )
    }

    override fun detect(settings: DisplaySettings, widthPx: Int?, heightPx: Int?): DisplayStrategy {
        lastSettings = settings
        val m = metrics(widthPx, heightPx)

        // Apply hardware display resolution profile overrides (Recommended devices sorted by size)
        val effectiveMetrics = when (settings.devicePreset) {
            "EINK_60_300", "STANDARD_6" -> m.copy(
                widthPx = if (m.isLandscape) 1448 else 1072,
                heightPx = if (m.isLandscape) 1072 else 1448,
                ppi = 300f,
                isEinkHint = true,
                isColorEinkHint = false
            )
            "EINK_60_HD" -> m.copy(
                widthPx = if (m.isLandscape) 1024 else 758,
                heightPx = if (m.isLandscape) 758 else 1024,
                ppi = 212f,
                isEinkHint = true,
                isColorEinkHint = false
            )
            "EINK_68_300" -> m.copy(
                widthPx = if (m.isLandscape) 1648 else 1236,
                heightPx = if (m.isLandscape) 1236 else 1648,
                ppi = 300f,
                isEinkHint = true,
                isColorEinkHint = false
            )
            "EINK_70_300" -> m.copy(
                widthPx = if (m.isLandscape) 1680 else 1264,
                heightPx = if (m.isLandscape) 1264 else 1680,
                ppi = 300f,
                isEinkHint = true,
                isColorEinkHint = false
            )
            "EINK_78_300" -> m.copy(
                widthPx = if (m.isLandscape) 1872 else 1404,
                heightPx = if (m.isLandscape) 1404 else 1872,
                ppi = 300f,
                isEinkHint = true,
                isColorEinkHint = false
            )
            "LARGE_103" -> m.copy(
                widthPx = if (m.isLandscape) 1872 else 1404,
                heightPx = if (m.isLandscape) 1404 else 1872,
                ppi = 227f,
                isEinkHint = true,
                isColorEinkHint = false
            )
            "MAX_133" -> m.copy(
                widthPx = if (m.isLandscape) 2200 else 1650,
                heightPx = if (m.isLandscape) 1650 else 2200,
                ppi = 207f,
                isEinkHint = true,
                isColorEinkHint = false
            )
            "FOLD_MAIN" -> m.copy(
                widthPx = if (m.isLandscape) 2176 else 1812,
                heightPx = if (m.isLandscape) 1812 else 2176,
                ppi = 374f,
                isOled = true,
                isEinkHint = false,
                isColorEinkHint = false
            )
            "FOLD_COVER" -> m.copy(
                widthPx = if (m.isLandscape) 2316 else 904,
                heightPx = if (m.isLandscape) 904 else 2316,
                ppi = 402f,
                isOled = true,
                isEinkHint = false,
                isColorEinkHint = false
            )
            "FOLD_ULTRA_MAIN" -> m.copy(
                widthPx = if (m.isLandscape) 2184 else 1968,
                heightPx = if (m.isLandscape) 1968 else 2184,
                ppi = 372f,
                isOled = true,
                isEinkHint = false,
                isColorEinkHint = false
            )
            "CLAMSHELL_MAIN" -> m.copy(
                widthPx = if (m.isLandscape) 2640 else 1080,
                heightPx = if (m.isLandscape) 1080 else 2640,
                ppi = 425f,
                isOled = true,
                isEinkHint = false,
                isColorEinkHint = false
            )
            "CLAMSHELL_COVER" -> m.copy(
                widthPx = if (m.isLandscape) 748 else 720,
                heightPx = if (m.isLandscape) 720 else 748,
                ppi = 306f,
                isOled = true,
                isEinkHint = false,
                isColorEinkHint = false
            )
            "GENERIC_TABLET" -> m.copy(
                isEinkHint = false,
                isColorEinkHint = false
            )
            else -> {
                // AUTO: Detect E-Ink hardware automatically
                val maxDim = maxOf(m.widthPx, m.heightPx)
                val minDim = minOf(m.widthPx, m.heightPx)
                val id = (Build.MANUFACTURER + " " + Build.MODEL + " " + Build.BRAND + " " + Build.DEVICE).lowercase()
                val isKnownLcdOled = m.isOled || id.contains("sdk_gphone") || id.contains("emulator")
                val isStandardResolution = !isKnownLcdOled && (
                    (maxDim in 1420..1480 && minDim in 1050..1100) ||
                    (maxDim in 1600..1710 && minDim in 1200..1300) ||
                    (maxDim in 1840..1900 && minDim in 1380..1430) ||
                    (maxDim in 1000..1050 && minDim in 740..780)
                )
                if ((m.isEinkHint || isStandardResolution) && !isKnownLcdOled) {
                    m.copy(ppi = if (m.ppi < 200f) 300f else m.ppi, isEinkHint = true)
                } else {
                    m
                }
            }
        }

        val kind = settings.forcedKind ?: when (settings.devicePreset) {
            "EINK_60_300", "STANDARD_6", "EINK_60_HD", "EINK_68_300",
            "EINK_70_300", "EINK_78_300", "LARGE_103", "MAX_133" -> DisplayKind.MONO_EINK
            "GENERIC_TABLET", "FOLD_MAIN", "FOLD_COVER", "FOLD_ULTRA_MAIN",
            "CLAMSHELL_MAIN", "CLAMSHELL_COVER" -> DisplayKind.LCD_OLED
            else -> when {
                effectiveMetrics.isColorEinkHint -> DisplayKind.COLOR_EINK
                effectiveMetrics.isEinkHint -> DisplayKind.MONO_EINK
                else -> DisplayKind.LCD_OLED
            }
        }
        val s = when (kind) {
            DisplayKind.MONO_EINK -> MonoEInkStrategy(effectiveMetrics, settings)
            DisplayKind.COLOR_EINK -> ColorEInkStrategy(effectiveMetrics, settings)
            DisplayKind.LCD_OLED -> LcdOledStrategy(effectiveMetrics, settings, darkModeProvider())
        }
        current = s
        return s
    }

    /** Call from Activity.onConfigurationChanged (rotation, fold, density, night mode). */
    fun onConfigurationChanged() {
        val s = detect(lastSettings)
        listeners.forEach { it(s) }
    }

    override fun addListener(listener: (DisplayStrategy) -> Unit) { listeners += listener }
    override fun removeListener(listener: (DisplayStrategy) -> Unit) { listeners -= listener }
}
