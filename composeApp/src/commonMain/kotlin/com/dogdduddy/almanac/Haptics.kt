package com.dogdduddy.almanac

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 진동 한 번. 시각은 패턴 시작 기준 ms.
 *
 * 모델은 CoreHaptics 의 어휘(transient / continuous, intensity, sharpness)를 따른다 —
 * 세 플랫폼 중 가장 표현력이 큰 쪽에 맞춰야 나머지가 근사할 수 있다.
 * Android 는 파형(시간·진폭)으로 근사하고 sharpness 는 펄스 길이로 옮긴다. 데스크톱은 무시한다.
 */
data class HapticEvent(
    val atMs: Int,
    val durationMs: Int,
    /** 0..1 */
    val intensity: Float,
    /** 0(둔탁) .. 1(날카로움) */
    val sharpness: Float,
    val transient: Boolean,
) {
    companion object {
        fun transient(atMs: Int, intensity: Float, sharpness: Float) =
            HapticEvent(atMs, durationMs = 0, intensity = intensity, sharpness = sharpness, transient = true)

        fun continuous(atMs: Int, durationMs: Int, intensity: Float, sharpness: Float) =
            HapticEvent(atMs, durationMs, intensity, sharpness, transient = false)
    }
}

data class HapticPattern(val events: List<HapticEvent>) {
    val isEmpty: Boolean get() = events.isEmpty()
}

/**
 * 플랫폼의 진동기.
 *
 * 구현은 **실패해도 조용하다.** 진동은 장식이라 없어도 앱은 온전하고, 진동 때문에
 * 죽는 앱은 있어서는 안 된다. 시스템의 진동 설정을 존중하는 것은 각 구현의 몫이다.
 */
interface Haptics {
    fun play(pattern: HapticPattern)
    fun cancel()
}

object NoHaptics : Haptics {
    override fun play(pattern: HapticPattern) = Unit
    override fun cancel() = Unit
}

/** 화면 트리에 진동기를 흘려보낸다. 기본은 무진동 — 테스트와 프리뷰가 조용하도록. */
val LocalHaptics = staticCompositionLocalOf<Haptics> { NoHaptics }

/**
 * 플랫폼 구현.
 *
 * - Android: `Vibrator` 파형. 진폭 제어가 있으면 세기를, 없으면 켜고 끄기만
 * - iOS: CoreHaptics. 엔진을 못 만들면 `UIImpactFeedbackGenerator` 로 떨어진다
 * - 데스크톱: [NoHaptics]
 */
@Composable
expect fun rememberPlatformHaptics(): Haptics

/**
 * 이벤트 목록 → 켜고 끄는 파형. Android 가 쓰고, 테스트로 고정한다.
 *
 * transient 는 짧은 펄스 하나다. sharpness 가 높을수록 짧다 (12ms .. 32ms).
 * 겹치는 이벤트는 뒤의 것이 이긴다 — 패턴 쪽에서 겹치지 않게 만든다.
 *
 * @return (timings, amplitudes) — 짝수 인덱스부터 off/on 이 번갈아 온다. 첫 원소는 off.
 */
fun HapticPattern.toWaveform(maxAmplitude: Int = 255): Pair<LongArray, IntArray> {
    val timings = ArrayList<Long>()
    val amplitudes = ArrayList<Int>()
    var cursor = 0
    for (event in events.sortedBy { it.atMs }) {
        val start = maxOf(event.atMs, cursor)
        val gap = start - cursor
        val on = if (event.transient) (12 + (1f - event.sharpness) * 20f).toInt() else event.durationMs
        if (on <= 0) continue
        // off 구간. 0 이어도 넣어 off/on 교대를 지킨다.
        timings += gap.toLong(); amplitudes += 0
        timings += on.toLong(); amplitudes += (event.intensity.coerceIn(0f, 1f) * maxAmplitude).toInt().coerceAtLeast(1)
        cursor = start + on
    }
    return timings.toLongArray() to amplitudes.toIntArray()
}
