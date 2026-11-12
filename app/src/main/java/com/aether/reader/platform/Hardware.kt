package com.aether.reader.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.os.Build
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.aether.reader.data.Prefs
import com.aether.reader.data.ReaderAction
import java.util.Locale

/** Intercepts hardware / Bluetooth-remote keys and maps short & long presses via [Prefs]. */
class KeyEventDispatcher(private val prefs: Prefs, private val onAction: (ReaderAction) -> Unit) {
    private val longHandled = HashSet<Int>()

    fun onKeyDown(code: Int, ev: KeyEvent): Boolean {
        if (prefs.keyAction(code, false) == ReaderAction.NONE && prefs.keyAction(code, true) == ReaderAction.NONE) return false
        if (ev.repeatCount == 0) ev.startTracking()
        return true
    }

    fun onKeyLongPress(code: Int): Boolean {
        val a = prefs.keyAction(code, true)
        if (a == ReaderAction.NONE) return false
        longHandled += code
        onAction(a)
        return true
    }

    fun onKeyUp(code: Int, ev: KeyEvent): Boolean {
        if (longHandled.remove(code)) return true
        val a = prefs.keyAction(code, false)
        if (a == ReaderAction.NONE) return false
        if (!ev.isCanceled) onAction(a)
        return true
    }
}

/**
 * Brightness via window attributes (+ system setting when WRITE_SETTINGS is granted).
 * Supports system default brightness toggle, LCD warm tint overlay, and vendor E-Ink frontlight (Onyx/Boyue).
 */
/**
 * Composite screen filter drawable that renders both:
 * 1. Warm candlelight / color-temperature filter (0..100%)
 * 2. Software brightness attenuation (1..100%) so brightness changes are immediately visible
 *    even on E-Ink panels or Android ROMs where window.attributes.screenBrightness is ignored.
 */
private class FrontlightFilterDrawable : Drawable() {
    var active: Boolean = false
    var brightness: Float = 1.0f
    var warmth: Float = 0.0f
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        if (!active) return
        val bounds = bounds
        val w = warmth.coerceIn(0f, 1f)
        val b = brightness.coerceIn(0.01f, 1f)

        // 1. Warmth / Color Temperature amber layer
        if (w > 0.005f) {
            val warmAlpha = (w * 155f).toInt().coerceIn(0, 155)
            paint.color = Color.argb(warmAlpha, 255, 136, 0)
            canvas.drawRect(bounds, paint)
        }

        // 2. Brightness attenuation layer (100% = 0 alpha, 1% = 205 alpha)
        if (b < 0.995f) {
            val dimAlpha = ((1f - b) * 205f).toInt().coerceIn(0, 205)
            paint.color = Color.argb(dimAlpha, 0, 0, 0)
            canvas.drawRect(bounds, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Brightness & Color Temperature controller:
 * - Drives Activity window + active Dialog window attributes (`screenBrightness`)
 * - Drives composite `FrontlightFilterDrawable` on both the main reader overlay and any open Settings dialog
 * - Drives system settings (`WRITE_SETTINGS`) and vendor E-Ink frontlight APIs (Onyx Boox / Boyue).
 */
class FrontlightController(private val act: Activity, private val overlay: View, private val prefs: Prefs) {
    private var dimmed = false
    private val mainFilter = FrontlightFilterDrawable()
    private val dialogFilter = FrontlightFilterDrawable()
    private var dialogWindow: Window? = null

    var isEink: Boolean = false
        set(value) {
            field = value
            if (value) {
                prefs.followSystemBrightness = true
            }
            syncVisualState()
        }

    init {
        overlay.background = mainFilter
    }

    /**
     * Attaches an open AlertDialog's Window so brightness & warmth changes are immediately visible
     * on both the dialog itself and the book page behind it while dragging sliders.
     */
    fun attachDialogWindow(win: Window?) {
        detachDialogWindow()
        if (win == null) return
        dialogWindow = win
        // Remove Android's artificial black backdrop dim so the user sees true page brightness & warmth
        win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val decor = win.decorView
        decor.post {
            dialogFilter.setBounds(0, 0, decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1))
            decor.overlay.add(dialogFilter)
            syncVisualState()
        }
        decor.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            dialogFilter.setBounds(0, 0, v.width.coerceAtLeast(1), v.height.coerceAtLeast(1))
            dialogFilter.invalidateSelf()
        }
        syncVisualState()
    }

    fun detachDialogWindow() {
        val win = dialogWindow ?: return
        try {
            win.decorView.overlay.remove(dialogFilter)
        } catch (_: Throwable) {}
        dialogWindow = null
    }

    private fun syncVisualState() {
        if (isEink) {
            val lp = act.window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            act.window.attributes = lp
            dialogWindow?.let { dWin ->
                val dlp = dWin.attributes
                dlp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                dWin.attributes = dlp
            }
            mainFilter.active = false
            dialogFilter.active = false
            mainFilter.invalidateSelf()
            dialogFilter.invalidateSelf()
            overlay.invalidate()
            dialogWindow?.decorView?.invalidate()
            return
        }

        val isSys = prefs.followSystemBrightness
        val b = prefs.brightness.coerceIn(0.01f, 1f)
        val w = prefs.warmth.coerceIn(0f, 1f)

        // 1. Update Activity Window hardware brightness override
        val lp = act.window.attributes
        lp.screenBrightness = if (isSys) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b
        act.window.attributes = lp

        // 2. Update active Dialog Window hardware brightness override (if open)
        dialogWindow?.let { dWin ->
            val dlp = dWin.attributes
            dlp.screenBrightness = if (isSys) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b
            dWin.attributes = dlp
        }

        // 3. Update composite software filter on both main overlay and dialog overlay
        mainFilter.active = !isSys
        mainFilter.brightness = b
        mainFilter.warmth = w
        mainFilter.invalidateSelf()
        overlay.invalidate()

        dialogFilter.active = !isSys
        dialogFilter.brightness = b
        dialogFilter.warmth = w
        dialogFilter.invalidateSelf()
        dialogWindow?.decorView?.invalidate()
    }

    fun apply() {
        if (isEink || prefs.followSystemBrightness) {
            setSystemDefault()
        } else {
            setBrightness(prefs.brightness, userAction = false)
            setWarmth(prefs.warmth, userAction = false)
        }
    }

    /** Restores display to follow system-managed brightness and resets warm tint overlays. */
    fun setSystemDefault() {
        prefs.followSystemBrightness = true
        syncVisualState()
        tryOnyxFrontLight(isReset = true)
    }

    fun setBrightness(v: Float, userAction: Boolean = true) {
        if (userAction) prefs.followSystemBrightness = false
        val b = v.coerceIn(0.01f, 1f)
        prefs.brightness = b
        syncVisualState()

        val scaled255 = (b * 255).toInt().coerceIn(1, 255)
        if (android.provider.Settings.System.canWrite(act)) {
            try {
                android.provider.Settings.System.putInt(act.contentResolver, "screen_brightness_mode", 0)
                android.provider.Settings.System.putInt(act.contentResolver, "screen_brightness", scaled255)
                android.provider.Settings.System.putInt(act.contentResolver, "front_light_brightness", scaled255)
                android.provider.Settings.System.putInt(act.contentResolver, "screen_cold_brightness", scaled255)
            } catch (_: Exception) { }
        }
        try {
            act.sendBroadcast(Intent("com.onyx.android.sdk.action.COLD_LIGHT").putExtra("value", scaled255))
            act.sendBroadcast(Intent("action.onyx.frontlight.cold").putExtra("value", scaled255))
        } catch (_: Throwable) {}
        tryOnyxFrontLight(brightnessVal = b)
    }

    fun setWarmth(v: Float, userAction: Boolean = true) {
        if (userAction) prefs.followSystemBrightness = false
        val w = v.coerceIn(0f, 1f)
        prefs.warmth = w
        syncVisualState()

        val scaled255 = (w * 255).toInt().coerceIn(0, 255)
        if (android.provider.Settings.System.canWrite(act)) {
            try {
                android.provider.Settings.System.putInt(act.contentResolver, "screen_warm_brightness", scaled255)
                android.provider.Settings.System.putInt(act.contentResolver, "front_light_ctm", scaled255)
            } catch (_: Exception) { }
        }

        // Custom CTM broadcast if configured
        if (prefs.ctmBroadcastAction.isNotBlank()) {
            act.sendBroadcast(Intent(prefs.ctmBroadcastAction).putExtra(prefs.ctmBroadcastExtra, w))
        }
        // Onyx Boox / Boyue common frontlight intents
        try {
            act.sendBroadcast(Intent("com.onyx.android.sdk.action.WARM_LIGHT").putExtra("value", scaled255))
            act.sendBroadcast(Intent("action.onyx.frontlight.warm").putExtra("value", scaled255))
        } catch (_: Throwable) {}

        tryOnyxFrontLight(warmthVal = w)
    }

    private fun tryOnyxFrontLight(brightnessVal: Float = -1f, warmthVal: Float = -1f, isReset: Boolean = false) {
        try {
            val flClass = Class.forName("com.onyx.android.sdk.api.device.FrontLightController")
            if (isReset) return
            try { flClass.getMethod("turnOn", Context::class.java).invoke(null, act) } catch (_: Throwable) {}
            try { flClass.getMethod("open", Context::class.java).invoke(null, act) } catch (_: Throwable) {}

            if (brightnessVal >= 0f) {
                val v255 = (brightnessVal * 255).toInt().coerceIn(0, 255)
                for (mName in listOf("setColdLightDeviceValue", "setColdLightConfigValue", "setColdLightValue", "setBrightness")) {
                    try {
                        val m = flClass.getMethod(mName, Context::class.java, Int::class.javaPrimitiveType)
                        m.invoke(null, act, v255)
                        break
                    } catch (_: Throwable) {}
                }
            }
            if (warmthVal >= 0f) {
                val w255 = (warmthVal * 255).toInt().coerceIn(0, 255)
                for (mName in listOf("setWarmLightDeviceValue", "setWarmLightConfigValue", "setWarmLightValue", "setWarmLight")) {
                    try {
                        val m = flClass.getMethod(mName, Context::class.java, Int::class.javaPrimitiveType)
                        m.invoke(null, act, w255)
                        break
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            // Not an Onyx SDK environment
        }
    }

    fun dim() {
        if (dimmed) return
        dimmed = true
        val lp = act.window.attributes; lp.screenBrightness = 0.0f; act.window.attributes = lp
    }

    fun restore() {
        if (!dimmed) return
        dimmed = false
        syncVisualState()
    }
}

/**
 * Modularized TextToSpeech controller in `features/tts/TtsEngineController.kt`.
 * - LCD/OLED tablets: Allows speech reading directly via built-in speaker or any connected audio output.
 * - E-Ink devices: Requires Bluetooth audio or connected earphones/headset to prevent unintended loud playback.
 */
typealias TtsController = com.aether.reader.features.tts.TtsEngineController

