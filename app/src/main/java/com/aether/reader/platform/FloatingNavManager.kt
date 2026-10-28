package com.aether.reader.platform

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aether.reader.app.DocumentPickerActivity

/**
 * Manages the persistent, always-on-top floating navigation bar
 * ("[✥ 이동] [◀ 뒤로] [✕ 탐색기 닫기]") over the Android system file picker.
 *
 * Technical Highlights:
 * 1. Prioritizes TYPE_ACCESSIBILITY_OVERLAY (highest z-order, bypasses anti-tapjacking hides).
 *    Falls back to TYPE_APPLICATION_OVERLAY if accessibility service is not yet enabled.
 * 2. Uses a dedicated drag handle "[✥]" for repositioning, keeping the action buttons
 *    purely click-based (eliminating E-ink touch-jitter accidental drag issues).
 * 3. Session-based lifecycle: Remains persistently visible as long as the picker session is active,
 *    never disappearing on transient window state change events.
 */
object FloatingNavManager {

    @Volatile var isSessionActive: Boolean = false
        private set

    private var appContext: Context? = null
    private var overlayBar: View? = null
    private var activeWindowManager: WindowManager? = null
    private var currentLp: WindowManager.LayoutParams? = null

    fun startSession(context: Context) {
        appContext = context.applicationContext
        isSessionActive = true
        ensureViewVisible()
    }

    fun stopSession() {
        isSessionActive = false
        hideView()
    }

    @Synchronized
    fun ensureViewVisible() {
        if (!isSessionActive) return
        val context = appContext ?: return
        if (overlayBar != null) return // Already added and visible

        // 1. Determine WindowManager and Window Type
        // We strictly require TYPE_ACCESSIBILITY_OVERLAY from NavAssistService.
        // TYPE_APPLICATION_OVERLAY is deliberately avoided because Android's DocumentsUI
        // hides non-system overlays (SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS),
        // causing it to fail inside the picker and mistakenly leak onto the launcher desktop.
        val a11yService = NavAssistService.instance ?: return
        val wm = a11yService.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        activeWindowManager = wm

        val dm = context.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        // 2. Build the Floating Bar UI
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(16).toFloat()
            // High-contrast clean E-ink card: pure white pill with crisp 2.5dp solid black border
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(2.5f.toInt().coerceAtLeast(2)), Color.BLACK)
            }
            setPadding(dp(6), dp(5), dp(10), dp(5))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(32) // Float comfortably 32dp above screen bottom
        }
        currentLp = lp

        // 2-1. Drag Handle: [✥ 이동]
        val dragHandle = TextView(context).apply {
            text = " ✥ "
            textSize = 15f
            setTextColor(0xFF555555.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        @SuppressLint("ClickableViewAccessibility")
        val dragListener = View.OnTouchListener { _, event ->
            val startCoords = dragHandle.getTag(0x7f0a0002) as? FloatArray ?: FloatArray(4)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startCoords[0] = event.rawX
                    startCoords[1] = event.rawY
                    startCoords[2] = lp.x.toFloat()
                    startCoords[3] = lp.y.toFloat()
                    dragHandle.setTag(0x7f0a0002, startCoords)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startCoords[0]).toInt()
                    val dy = (startCoords[1] - event.rawY).toInt() // Inverted because y is from bottom
                    lp.x = (startCoords[2] + dx).toInt()
                    lp.y = (startCoords[3] + dy).toInt().coerceAtLeast(dp(8))
                    try {
                        wm.updateViewLayout(bar, lp)
                    } catch (_: Throwable) {}
                    true
                }
                else -> false
            }
        }
        dragHandle.setOnTouchListener(dragListener)
        bar.addView(dragHandle)

        // Thin separator
        fun divider() = View(context).apply {
            setBackgroundColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(22)).apply {
                setMargins(dp(2), 0, dp(4), 0)
            }
        }
        bar.addView(divider())

        // 2-2. Back Button: [◀ 뒤로]
        val backBtn = TextView(context).apply {
            text = "◀ 뒤로"
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                val service = NavAssistService.instance
                if (service != null) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } else {
                    DocumentPickerActivity.cancelActivePicker()
                }
            }
        }
        bar.addView(backBtn)

        bar.addView(divider())

        // 2-3. Close Explorer Button: [✕ 탐색기 닫기]
        val closeBtn = TextView(context).apply {
            text = "✕ 탐색기 닫기"
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                DocumentPickerActivity.cancelActivePicker()
                stopSession()
            }
        }
        bar.addView(closeBtn)

        try {
            wm.addView(bar, lp)
            overlayBar = bar
        } catch (_: Throwable) {
            overlayBar = null
        }
    }

    @Synchronized
    fun hideView() {
        val bar = overlayBar ?: return
        overlayBar = null
        val wm = activeWindowManager ?: (appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        try {
            wm?.removeViewImmediate(bar)
        } catch (_: Throwable) {}
        activeWindowManager = null
    }
}
