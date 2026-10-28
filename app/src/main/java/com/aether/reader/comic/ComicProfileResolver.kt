package com.aether.reader.comic

import com.aether.reader.domain.display.DisplayKind
import com.aether.reader.platform.HardwareProfileManager
import com.aether.reader.platform.HardwareSpec

/**
 * Feature Gating & Capability Resolution matrix for Comic/Manga Engine.
 * Dynamically scales memory windows, rendering animations, scrolling modes,
 * and image contrast filtering between low-spec E-Ink devices (Luna X, Poke, Leaf)
 * and high-spec LCD/OLED tablets.
 */
data class ComicProfile(
    val cacheWindowSize: Int,            // 3 pages on low-spec E-Ink; 7 pages on high-spec Tablet
    val allowPageCurlAnim: Boolean,       // Strict false (0ms instant swap) on E-Ink; true on Tablet
    val allowVerticalWebtoon: Boolean,    // Strict false on E-Ink (avoids severe ghosting); true on Tablet
    val autoImageEnhance: Boolean,        // Floyd-Steinberg dithering + gamma boost on E-Ink; direct pass-through on Tablet
    val autoRefreshCyclePages: Int,       // Refresh every N pages (5, 10, or 0 = disabled)
    val subsampleRatio: Int,              // 2 or 4 on low-spec devices; 1 (full-res) on tablets
    val isEink: Boolean
)

object ComicProfileResolver {

    /**
     * Resolves the hardware profile for comics based on [HardwareSpec].
     * Low-spec E-Ink (e.g. Luna X, Snapdragon 630, 2GB RAM):
     * - Cache window: 3 pages (Previous, Current, Next) strictly.
     * - Instant 0ms page turns (no curl/slide animation).
     * - Vertical webtoon mode DISABLED to avoid continuous E-Ink refresh flicker.
     * - Auto image enhancement: Contrast / Gamma boost + Floyd-Steinberg dithering.
     * - Subsampling downscale factor: 2 or 4.
     */
    fun resolve(hardware: HardwareProfileManager): ComicProfile {
        val spec = hardware.spec
        val isLowSpec = spec.isLowSpecEink || spec.totalRamMb < 3000
        val isEink = spec.displayKind == DisplayKind.MONO_EINK || spec.displayKind == DisplayKind.COLOR_EINK || spec.isLowSpecEink || spec.isHighSpecEink

        return if (isLowSpec || isEink) {
            ComicProfile(
                cacheWindowSize = 3,
                allowPageCurlAnim = false,
                allowVerticalWebtoon = !isEink,
                autoImageEnhance = isEink,
                autoRefreshCyclePages = if (isEink) 6 else 0,
                subsampleRatio = if (spec.totalRamMb <= 2048) 2 else 1,
                isEink = isEink
            )
        } else {
            // High-spec tablet (RAM >= 3GB, LCD/OLED)
            ComicProfile(
                cacheWindowSize = 7,
                allowPageCurlAnim = true,
                allowVerticalWebtoon = true,
                autoImageEnhance = false,
                autoRefreshCyclePages = 0,
                subsampleRatio = 1,
                isEink = false
            )
        }
    }
}
