package com.dogdduddy.almanac

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android 진동기. 파형(`createWaveform`) 하나로 패턴 전체를 보낸다.
 *
 * 기기별 프리미티브(THUD, TICK 등)는 더 좋게 들리지만 지원 여부가 갈리고 한 번에
 * 10개까지라, 세 플랫폼에서 같은 타임라인을 보장하는 파형을 쓴다. 진폭 제어가 없는
 * 기기에서는 세기가 사라지고 켜고 끄기만 남는다.
 *
 * **용도(usage)가 곧 어느 시스템 설정을 따르느냐다.** 이 앱의 진동은 버튼을 눌렀다는 UI 신호가
 * 아니라 등장 애니메이션과 한 몸인 콘텐츠라 전부 `USAGE_MEDIA` 로 보낸다. 터치 진동
 * (`USAGE_TOUCH`)은 One UI 에서 기본으로 꺼져 있어, 그리로 보내면 조용히 버려진다
 * (dumpsys 에 `ignored_for_settings` 로 남는다 — 실기에서 확인했다).
 * 미디어 진동 설정은 그대로 따르므로 진동을 원치 않는 유저의 선택은 존중된다.
 */
private class AndroidHaptics(context: Context) : Haptics {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    override fun play(pattern: HapticPattern) {
        val vibrator = vibrator ?: return
        if (pattern.isEmpty) return
        runCatching {
            val (timings, amplitudes) = pattern.toWaveform()
            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                vibrator.vibrate(effect)
            }
        }
    }

    override fun cancel() {
        runCatching { vibrator?.cancel() }
    }
}

@Composable
actual fun rememberPlatformHaptics(): Haptics {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidHaptics(context) }
}
