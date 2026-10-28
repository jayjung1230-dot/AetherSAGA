package com.aether.reader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.aether.reader.App
import com.aether.reader.domain.display.DisplayKind
import com.aether.reader.platform.FloatingNavManager
import com.aether.reader.platform.NavAssistService
import java.lang.ref.WeakReference

/**
 * Transparent bridge activity that wraps ACTION_OPEN_DOCUMENT and coordinates with
 * [FloatingNavManager] to ensure persistent on-screen navigation buttons
 * ("[ ✥ ] [◀ 뒤로] [✕ 탐색기 닫기]") stay on top during the entire picker session on E-Ink readers.
 */
class DocumentPickerActivity : Activity() {

    private var mode: String = "BOOK"
    private var safLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("EXTRA_MODE") ?: "BOOK"
        active = WeakReference(this)

        val a = application as? App
        val floatingNavEnabled = a?.prefs?.floatingPickerNavEnabled ?: true
        val hasA11y = NavAssistService.isEnabled(this)
        val prefs = getSharedPreferences("aether", MODE_PRIVATE)

        if (!floatingNavEnabled) {
            // 사용자가 플로팅 네비게이션을 꺼둔 경우 (기본 소프트키/제스처 탐색기 사용)
            launchSafPicker(enableFloatingNav = false)
            return
        }

        if (hasA11y) {
            // 플로팅 버튼이 켜져 있고 접근성 권한이 있으면 즉시 실행
            launchSafPicker(enableFloatingNav = true)
        } else if (prefs.getBoolean(KEY_SKIP_PROMPT, false)) {
            // 이번 실행에서 안내 건너뛰기를 누른 경우
            launchSafPicker(enableFloatingNav = false)
        } else {
            // 권한 안내 다이얼로그 표시
            showEnableButtonDialog()
        }
    }

    private fun showEnableButtonDialog() {
        val a = application as? App
        AlertDialog.Builder(this)
            .setTitle("탐색기 뒤로가기 버튼 안내")
            .setMessage(
                "안드로이드 시스템 탐색기 화면은 보안 정책상 일반 앱의 플로팅 버튼을 숨깁니다.\n\n" +
                "탐색기 화면 위에 [◀ 뒤로] / [✕ 탐색기 닫기] 버튼을 지속적으로 유지하려면 " +
                "접근성 설정에서 'Aether SAGA 뒤로가기 버튼'을 켜주세요.\n\n" +
                "• [접근성 설정 열기]: 권한을 켠 후 뒤로 돌아오면 플로팅 버튼과 함께 열립니다.\n" +
                "• [버튼 없이 열기]: 이번 한 번만 플로팅 버튼 없이 탐색기를 엽니다.\n" +
                "• [플로팅 버튼 끄기]: 항상 기본 제스처/소프트키로 사용합니다."
            )
            .setPositiveButton("접근성 설정 열기") { _, _ ->
                try {
                    startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), REQ_A11Y)
                } catch (_: Throwable) {
                    launchSafPicker(enableFloatingNav = false)
                }
            }
            .setNeutralButton("버튼 없이 열기") { _, _ ->
                getSharedPreferences("aether", MODE_PRIVATE).edit().putBoolean(KEY_SKIP_PROMPT, true).apply()
                launchSafPicker(enableFloatingNav = false)
            }
            .setNegativeButton("플로팅 버튼 끄기") { _, _ ->
                a?.prefs?.floatingPickerNavEnabled = false
                launchSafPicker(enableFloatingNav = false)
            }
            .setCancelable(false)
            .show()
    }

    private fun launchSafPicker(enableFloatingNav: Boolean = true) {
        // Start floating session so the bar is added and stays visible over the system picker
        if (enableFloatingNav) {
            FloatingNavManager.startSession(this)
        }

        val requestMimeTypes = if (mode == "FONT") {
            arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")
        } else {
            arrayOf(
                "application/epub+zip", "text/plain", "text/markdown", "application/pdf",
                "application/x-cbz", "application/zip", "application/octet-stream"
            )
        }
        val safIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, requestMimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            safLaunched = true
            startActivityForResult(safIntent, REQ_SAF)
        } catch (e: Exception) {
            safLaunched = false
            FloatingNavManager.stopSession()
            Toast.makeText(this, "시스템 탐색기를 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /** Closes the system picker above us, stops floating overlay, and returns to caller. */
    fun cancelPicker() {
        FloatingNavManager.stopSession()
        if (safLaunched) {
            try { finishActivity(REQ_SAF) } catch (_: Throwable) {}
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_A11Y -> {
                val hasA11y = NavAssistService.isEnabled(this)
                launchSafPicker(enableFloatingNav = hasA11y)
            }
            REQ_SAF -> {
                safLaunched = false
                FloatingNavManager.stopSession()
                data?.let { intent ->
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    intent.data?.let { uri ->
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Throwable) {}
                    }
                    intent.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) {
                            try {
                                contentResolver.takePersistableUriPermission(clip.getItemAt(i).uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (_: Throwable) {}
                        }
                    }
                }
                setResult(resultCode, data)
                finish()
            }
        }
    }

    override fun onDestroy() {
        FloatingNavManager.stopSession()
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    companion object {
        const val REQ_SAF = 777
        const val REQ_A11Y = 779
        private const val KEY_SKIP_PROMPT = "navAssistPromptSkipped"

        @Volatile private var active: WeakReference<DocumentPickerActivity>? = null

        /** Called by [FloatingNavManager] when user taps close. */
        fun cancelActivePicker(): Boolean {
            val a = active?.get() ?: return false
            if (a.isFinishing || a.isDestroyed) return false
            a.runOnUiThread { a.cancelPicker() }
            return true
        }
    }
}
