package com.aether.reader.platform

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

import com.aether.reader.app.DocumentPickerActivity

/**
 * Accessibility service providing trusted system-level overlay capabilities
 * (TYPE_ACCESSIBILITY_OVERLAY) for the floating file-picker navigation bar on E-Ink devices.
 *
 * This allows the floating [◀ 뒤로] / [✕ 탐색기 닫기] bar to remain visible
 * above DocumentsUI without being hidden by SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS.
 */
class NavAssistService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (FloatingNavManager.isSessionActive) {
            FloatingNavManager.ensureViewVisible()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // While a picker session is active, keep the bar visible ONLY when inside the document picker or our app!
        if (FloatingNavManager.isSessionActive) {
            if (isDocumentPicker(pkg) || pkg == packageName) {
                FloatingNavManager.ensureViewVisible()
            } else {
                // If user switched to launcher, settings, or any other app, hide immediately!
                FloatingNavManager.hideView()
                if (isLauncher(pkg)) {
                    // Tapping home cleanly cancels any pending picker session
                    DocumentPickerActivity.cancelActivePicker()
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun isLauncher(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("launcher") || lower.contains("home") ||
                lower == "com.android.launcher" || lower == "com.google.android.apps.nexuslauncher" ||
                lower == "com.sec.android.app.launcher" || lower == "com.miui.home"
    }

    private fun isDocumentPicker(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("documentsui") ||
                lower.contains("filemanager") ||
                lower.contains("externalstorage") ||
                lower == "com.android.documentsui" ||
                lower == "com.google.android.documentsui"
    }

    companion object {
        @Volatile var instance: NavAssistService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, NavAssistService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it)?.let { c -> c == me } == true
            }
        }
    }
}
