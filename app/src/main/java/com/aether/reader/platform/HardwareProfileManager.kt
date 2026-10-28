package com.aether.reader.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.aether.reader.domain.display.DisplayKind
import com.aether.reader.domain.display.DisplayProfileManager
import java.io.File
import java.io.FileFilter

enum class FeatureFlag(val title: String, val description: String) {
    FEATURE_TURBO_PAGING("터보 고속 넘김 (Turbo Paging)", "버튼 길게 누름 시 초당 5~10페이지 고속 프리뷰 넘김"),
    FEATURE_LOCAL_WEB_REMOTE("로컬 웹 리모컨 (Web Remote)", "거치대 독서 시 스마트폰 브라우저로 원격 넘김"),
    FEATURE_INLINE_FOOTNOTE("인라인 각주 팝오버 (Inline Footnote)", "미주/각주 이동 없이 제자리 팝업으로 내용 열람"),
    FEATURE_JUMP_BACK_STACK("페이지 점프 히스토리 (Jump Back)", "목차/슬라이더 점프 시 이전 위치 스택 복귀"),
    FEATURE_READING_TIMER("포모도로 집중 타이머 (Timer)", "E-Ink 화면 갱신을 최소화한 저전력 독서 타이머"),
    FEATURE_READING_RULER("독서 룰러 / 집중 가이드 (Ruler)", "현재 읽는 단락/행 시선 가이드 라인 표시")
}

data class HardwareSpec(
    val totalRamMb: Long,
    val cpuCores: Int,
    val isLowSpecEink: Boolean,
    val isHighSpecEink: Boolean,
    val isTablet: Boolean,
    val displayKind: DisplayKind
)

class HardwareProfileManager(
    private val context: Context,
    private val profileManager: DisplayProfileManager
) {
    val spec: HardwareSpec by lazy { inspectHardware() }

    private fun inspectHardware(): HardwareSpec {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val ramMb = memInfo.totalMem / (1024 * 1024)

        val cores = getCpuCoreCount()
        val metrics = profileManager.metrics()
        val isEink = metrics.isEinkHint || metrics.isColorEinkHint

        val isLowSpecEink = isEink && ramMb < 3000 // Luna X (2GB RAM)
        val isHighSpecEink = isEink && ramMb >= 3000
        val isTablet = !isEink

        val kind = when {
            metrics.isColorEinkHint -> DisplayKind.COLOR_EINK
            metrics.isEinkHint -> DisplayKind.MONO_EINK
            else -> DisplayKind.LCD_OLED
        }

        return HardwareSpec(
            totalRamMb = ramMb,
            cpuCores = cores,
            isLowSpecEink = isLowSpecEink,
            isHighSpecEink = isHighSpecEink,
            isTablet = isTablet,
            displayKind = kind
        )
    }

    private fun getCpuCoreCount(): Int {
        return try {
            val dir = File("/sys/devices/system/cpu/")
            dir.listFiles(FileFilter { it.name.matches(Regex("cpu[0-9]+")) })?.size ?: Runtime.getRuntime().availableProcessors()
        } catch (_: Exception) {
            Runtime.getRuntime().availableProcessors()
        }
    }

    /**
     * Baseline capability gating decision matrix.
     * Evaluates whether a feature should be enabled by default or restricted.
     */
    fun isFeatureAllowedByDefault(flag: FeatureFlag): Boolean {
        return when (flag) {
            FeatureFlag.FEATURE_TURBO_PAGING -> !spec.isLowSpecEink
            FeatureFlag.FEATURE_LOCAL_WEB_REMOTE -> true
            FeatureFlag.FEATURE_INLINE_FOOTNOTE -> true
            FeatureFlag.FEATURE_JUMP_BACK_STACK -> true
            FeatureFlag.FEATURE_READING_TIMER -> true
            FeatureFlag.FEATURE_READING_RULER -> true
        }
    }

    fun getDisabledReason(flag: FeatureFlag): String? {
        if (isFeatureAllowedByDefault(flag)) return null
        return when (flag) {
            FeatureFlag.FEATURE_TURBO_PAGING -> "2GB RAM E-Ink 기기의 웨이브폼 크래시 및 잔상 방지를 위해 기본 비활성화됨"
            FeatureFlag.FEATURE_LOCAL_WEB_REMOTE -> "가용 램 600~800MB 유지 및 백그라운드 OOM 방지를 위해 기본 비활성화됨"
            else -> null
        }
    }
}
