package com.dogdduddy.almanac

import com.dogdduddy.almanac.core.WeatherGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 햅틱 패턴이 등장 타임라인 안에 있고, 파형 변환이 off/on 교대를 지키는지.
 * 파형은 Android 가 그대로 진동기에 넣는 값이라 여기서 고정한다.
 */
class HapticWaveformTest {

    @Test
    fun everyWeatherPatternFitsInsideItsEntrance() {
        WeatherGroup.entries.forEach { group ->
            val pattern = entranceHaptics(group, wordCount = 45)
            val duration = entranceDurationMs(group)
            pattern.events.forEach { event ->
                assertTrue(event.atMs >= 0, "${group.key}: 음수 시각 $event")
                assertTrue(event.atMs + event.durationMs <= duration + 50, "${group.key}: 등장 뒤에 진동 $event")
                assertTrue(event.intensity in 0f..1f && event.sharpness in 0f..1f, "${group.key}: 범위 밖 $event")
            }
        }
        assertTrue(entranceHaptics(WeatherGroup.CLOUDY, 45).isEmpty, "흐림은 조용해야 한다")
        assertTrue(entranceHaptics(WeatherGroup.RAIN, 45).events.size > 8, "비는 여러 번 두드려야 한다")
    }

    @Test
    fun waveformAlternatesOffAndOnAndKeepsOrder() {
        val pattern = HapticPattern(
            listOf(
                HapticEvent.transient(100, intensity = 1f, sharpness = 1f),      // 12ms
                HapticEvent.continuous(20, durationMs = 50, intensity = 0.5f, sharpness = 0f),
                HapticEvent.transient(300, intensity = 0.2f, sharpness = 0f),    // 32ms
            )
        )
        val (timings, amplitudes) = pattern.toWaveform()
        assertEquals(listOf(20L, 50L, 30L, 12L, 188L, 32L), timings.toList())
        assertEquals(listOf(0, 127, 0, 255, 0, 51), amplitudes.toList())
    }

    @Test
    fun overlappingEventsArePushedBackNotDropped() {
        val pattern = HapticPattern(
            listOf(
                HapticEvent.continuous(0, durationMs = 100, intensity = 1f, sharpness = 0f),
                HapticEvent.transient(50, intensity = 1f, sharpness = 1f),
            )
        )
        val (timings, _) = pattern.toWaveform()
        assertEquals(listOf(0L, 100L, 0L, 12L), timings.toList())
    }
}
