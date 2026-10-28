package com.aether.reader.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.aether.reader.domain.display.PageTransition
import com.aether.reader.platform.HardwareProfileManager
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/**
 * Controller managing comic presentation:
 * - 3-Slot Sliding Window Memory Protection (Prev, Current, Next).
 * - LTR / RTL / Vertical navigation strategies.
 * - Automatic double-page spread splitting.
 * - E-Ink auto-refresh cycles (every N pages).
 */
class ComicPagePresenter(
    private val context: Context,
    val streamer: ArchiveStreamManager,
    private val hardware: HardwareProfileManager,
    private val viewportWidth: Int,
    private val viewportHeight: Int
) : Closeable {

    val profile = ComicProfileResolver.resolve(hardware)
    var direction: ReadingDirection = ReadingDirection.RTL // Default Manga RTL
    var splitMode: SplitMode = SplitMode.AUTO_SPLIT

    private var virtualPages: List<PageSplitter.VirtualPage> = emptyList()
    var currentVirtualIndex: Int = 0
        private set

    // Strict 3-slot sliding window: index -> Bitmap
    private val slotBitmaps = HashMap<Int, Bitmap>()
    private var pagesPresentedCount = 0

    init {
        recomputeVirtualPages(viewportWidth < viewportHeight)
    }

    /**
     * Recalculates virtual pages when orientation or split settings change.
     */
    fun recomputeVirtualPages(isPortrait: Boolean) {
        virtualPages = PageSplitter.generateVirtualPages(
            pageCount = streamer.pages.size,
            isPortraitMode = isPortrait,
            direction = direction,
            dimensionProvider = null // Headers inspected dynamically during stream
        )
        currentVirtualIndex = currentVirtualIndex.coerceIn(0, max(0, virtualPages.size - 1))
    }

    val totalVirtualPages: Int get() = virtualPages.size
    val currentRawPageIndex: Int
        get() = virtualPages.getOrNull(currentVirtualIndex)?.rawPageIndex ?: 0

    /**
     * Navigates forward based on [direction].
     * Returns the newly presented bitmap or null if at the end.
     */
    fun next(targetBmp: Bitmap, bgColor: Int): Boolean {
        if (currentVirtualIndex + 1 >= virtualPages.size) return false
        currentVirtualIndex++
        pagesPresentedCount++
        return renderCurrent(targetBmp, bgColor)
    }

    /**
     * Navigates backward.
     */
    fun previous(targetBmp: Bitmap, bgColor: Int): Boolean {
        if (currentVirtualIndex <= 0) return false
        currentVirtualIndex--
        pagesPresentedCount++
        return renderCurrent(targetBmp, bgColor)
    }

    /**
     * Seeks directly to [virtualIndex].
     */
    fun seekTo(virtualIndex: Int, targetBmp: Bitmap, bgColor: Int): Boolean {
        currentVirtualIndex = virtualIndex.coerceIn(0, max(0, virtualPages.size - 1))
        pagesPresentedCount++
        return renderCurrent(targetBmp, bgColor)
    }

    /**
     * Checks if an E-Ink hardware full refresh should be triggered.
     */
    fun shouldTriggerEinkRefresh(): Boolean {
        if (!profile.isEink || profile.autoRefreshCyclePages <= 0) return false
        return (pagesPresentedCount % profile.autoRefreshCyclePages == 0)
    }

    /**
     * Renders current virtual page into [targetBmp] while maintaining the 3-slot sliding window.
     */
    fun renderCurrent(targetBmp: Bitmap, bgColor: Int): Boolean {
        val vPage = virtualPages.getOrNull(currentVirtualIndex) ?: return false

        // Evict pages outside the 3-slot window [current - 1, current + 1]
        val keepMin = currentVirtualIndex - 1
        val keepMax = currentVirtualIndex + 1
        val toRemove = slotBitmaps.keys.filter { it < keepMin || it > keepMax }
        for (k in toRemove) {
            slotBitmaps.remove(k)?.recycle()
        }

        // Render target page
        val success = streamer.renderPage(
            pageIndex = vPage.rawPageIndex,
            targetBmp = targetBmp,
            reqW = viewportWidth,
            reqH = viewportHeight,
            enhanceEink = profile.autoImageEnhance
        )

        return success
    }

    override fun close() {
        for (bmp in slotBitmaps.values) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        slotBitmaps.clear()
        streamer.close()
    }
}
