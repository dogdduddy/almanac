package com.dogdduddy.almanac

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreHaptics.CHHapticEngine
import platform.CoreHaptics.CHHapticEvent
import platform.CoreHaptics.CHHapticEventParameter
import platform.CoreHaptics.CHHapticEventParameterIDHapticIntensity
import platform.CoreHaptics.CHHapticEventParameterIDHapticSharpness
import platform.CoreHaptics.CHHapticEventTypeHapticContinuous
import platform.CoreHaptics.CHHapticEventTypeHapticTransient
import platform.CoreHaptics.CHHapticPattern
import platform.CoreHaptics.CHHapticTimeImmediate
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

/**
 * iOS 진동기. CoreHaptics 가 모델과 1:1 이라 그대로 옮긴다.
 *
 * 엔진은 처음 쓸 때 만들고, 실패하면(구형 기기, 시뮬레이터) `UIImpactFeedbackGenerator` 로
 * 떨어져 첫 이벤트 하나만 낸다 — 아무것도 없는 것보다는 낫다.
 * 엔진은 앱이 백그라운드로 가면 멈추므로 재생 전에 매번 다시 시작한다 (이미 돌고 있으면 무해).
 */
@OptIn(ExperimentalForeignApi::class)
private class IosHaptics : Haptics {

    private val engine: CHHapticEngine? by lazy {
        if (!CHHapticEngine.capabilitiesForHardware().supportsHaptics) return@lazy null
        runCatching { CHHapticEngine(andReturnError = null) }.getOrNull()
    }

    override fun play(pattern: HapticPattern) {
        if (pattern.isEmpty) return
        val engine = engine
        if (engine == null) {
            fallback(pattern)
            return
        }
        runCatching {
            engine.startAndReturnError(null)
            val events = pattern.events.map { event ->
                CHHapticEvent(
                    eventType = if (event.transient) CHHapticEventTypeHapticTransient else CHHapticEventTypeHapticContinuous,
                    parameters = listOf(
                        CHHapticEventParameter(CHHapticEventParameterIDHapticIntensity, event.intensity),
                        CHHapticEventParameter(CHHapticEventParameterIDHapticSharpness, event.sharpness),
                    ),
                    relativeTime = event.atMs / 1000.0,
                    duration = if (event.transient) 0.0 else event.durationMs / 1000.0,
                )
            }
            val chPattern = CHHapticPattern(events = events, parameters = emptyList<Any>(), error = null)
            val player = engine.createPlayerWithPattern(chPattern, error = null)
            player?.startAtTime(CHHapticTimeImmediate, error = null)
        }.onFailure { fallback(pattern) }
    }

    override fun cancel() {
        runCatching { engine?.stopWithCompletionHandler(null) }
    }

    private fun fallback(pattern: HapticPattern) {
        val first = pattern.events.minByOrNull { it.atMs } ?: return
        val style = when {
            first.intensity > 0.7f -> UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy
            first.intensity > 0.35f -> UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
            else -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
        }
        runCatching { UIImpactFeedbackGenerator(style).impactOccurred() }
    }
}

@Composable
actual fun rememberPlatformHaptics(): Haptics = remember { IosHaptics() }
