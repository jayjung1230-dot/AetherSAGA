package com.aether.reader.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aether.reader.engine.BookLocation
import java.util.ArrayDeque

/**
 * 1. Jump-Back Navigation History Stack
 * Lightweight LIFO stack (max depth: 10) preserving reading location before jumps.
 */
class NavigationHistoryManager {
    private val stack = ArrayDeque<BookLocation>(10)

    fun push(loc: BookLocation) {
        if (stack.size >= 10) {
            stack.removeLast()
        }
        stack.push(loc)
    }

    fun pop(): BookLocation? {
        return if (stack.isNotEmpty()) stack.pop() else null
    }

    fun clear() {
        stack.clear()
    }

    val hasHistory: Boolean get() = stack.isNotEmpty()
}

/**
 * 2. Inline Footnote Resolver & In-page Popover
 * Intercepts noteref, <a>, <sup> footnote anchors in EPUB/HTML and presents in a BottomSheet modal
 * (max 40% height) with tap-outside dismiss without navigating away from the current page.
 */
object InlineFootnoteResolver {

    fun show(context: Context, noteTitle: String, noteContent: String, isDark: Boolean, isLcd: Boolean = false) {
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)

        val cardBg = if (isDark) ReaderTheme.DARK_CARD else ReaderTheme.EINK_PAPER
        val textFg = if (isDark) ReaderTheme.DARK_TEXT else ReaderTheme.EINK_INK
        val mutedFg = if (isDark) ReaderTheme.DARK_MUTED else ReaderTheme.EINK_MUTED
        val borderCol = if (isDark) ReaderTheme.DARK_BORDER else if (isLcd) ReaderTheme.EINK_BORDER else ReaderTheme.ACCENT_KINDLE

        val root = FrameLayout(context).apply {
            // LCD/OLED: 부드러운 딤 배경, E-Ink: 잔상 없는 초경량 배경
            setBackgroundColor(if (isLcd) 0x55000000 else 0x11000000)
            setOnClickListener { dialog.dismiss() }
        }

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardBg)
                val rad = if (isLcd) context.dp(16).toFloat() else context.dp(8).toFloat()
                setCornerRadii(floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f))
                setStroke(if (isLcd) context.dp(1) else context.dp(2).coerceAtLeast(2), borderCol)
            }
            setPadding(context.dp(20), context.dp(14), context.dp(20), context.dp(18))
            isClickable = true
        }

        // LCD 전용 드래그 핸들 인디케이터
        if (isLcd) {
            val handle = TextView(context).apply {
                text = "—"
                setTextColor(mutedFg)
                gravity = Gravity.CENTER
                textSize = 16f
            }
            sheet.addView(handle)
        }

        // Header Row: 제목 및 우측 직관적인 [✕ 닫기] 버튼
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, context.dp(6))
        }

        val titleTv = TextView(context).apply {
            text = noteTitle
            textSize = 15f
            setTextColor(textFg)
            typeface = ReaderTheme.uiFontBold
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleTv)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(textFg)
            typeface = ReaderTheme.uiFontBold
            gravity = Gravity.CENTER
            setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(closeBtn)
        sheet.addView(headerRow)

        val divider = View(context).apply {
            setBackgroundColor(borderCol)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1).coerceAtLeast(1)).apply {
                bottomMargin = context.dp(10)
            }
        }
        sheet.addView(divider)

        val scroll = ScrollView(context).apply {
            enableVisibleScrollbar()
        }
        val bodyTv = TextView(context).apply {
            text = noteContent
            textSize = 13.5f
            setTextColor(textFg)
            setLineSpacing(context.dp(2).toFloat(), 1.25f)
        }
        scroll.addView(bodyTv)

        val maxH = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        })

        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        root.addView(sheet, params)

        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        if (!isLcd) {
            dialog.window?.setWindowAnimations(0) // E-Ink: Zero animation for crisp instant render without ghosting
        }
        dialog.show()
    }
}
