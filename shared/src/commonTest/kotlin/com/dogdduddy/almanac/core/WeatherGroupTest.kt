package com.dogdduddy.almanac.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherGroupTest {

    /** 스펙에 명시된 코드 → 그룹 매핑 전수. 커버리지 구멍이 여기서 잡힌다. */
    @Test
    fun wmoCodeMappingIsExhaustive() {
        val expected = buildMap<Int, WeatherGroup> {
            listOf(0, 1).forEach { put(it, WeatherGroup.CLEAR) }
            listOf(2, 3).forEach { put(it, WeatherGroup.CLOUDY) }
            listOf(45, 48).forEach { put(it, WeatherGroup.FOG) }
            listOf(51, 53, 55, 56, 57).forEach { put(it, WeatherGroup.DRIZZLE) }
            ((61..67) + listOf(80, 81, 82)).forEach { put(it, WeatherGroup.RAIN) }
            ((71..77) + listOf(85, 86)).forEach { put(it, WeatherGroup.SNOW) }
            listOf(95, 96, 99).forEach { put(it, WeatherGroup.THUNDER) }
        }

        expected.forEach { (code, group) ->
            assertEquals(group, weatherGroupFromWmoCode(code), "WMO code $code")
        }

        // 매핑되지 않은 코드는 null 이어야 한다 — 조용히 CLEAR 로 떨어지면 안 된다.
        val unmapped = (0..99).toSet() - expected.keys
        unmapped.forEach { assertNull(weatherGroupFromWmoCode(it), "WMO code $it should be unmapped") }
    }

    @Test
    fun windIsNeverProducedByCodeAlone() {
        assertTrue((0..99).none { weatherGroupFromWmoCode(it) == WeatherGroup.WIND })
    }

    @Test
    fun strongWindOverridesClearAndCloudy() {
        assertEquals(WeatherGroup.WIND, resolveWeatherGroup(wmoCode = 0, windSpeedMs = 12.0))
        assertEquals(WeatherGroup.WIND, resolveWeatherGroup(wmoCode = 3, windSpeedMs = 12.0))
    }

    @Test
    fun strongWindDoesNotOverrideItsOwnWeather() {
        assertEquals(WeatherGroup.RAIN, resolveWeatherGroup(wmoCode = 63, windSpeedMs = 25.0))
        assertEquals(WeatherGroup.SNOW, resolveWeatherGroup(wmoCode = 73, windSpeedMs = 25.0))
        assertEquals(WeatherGroup.THUNDER, resolveWeatherGroup(wmoCode = 95, windSpeedMs = 25.0))
    }

    @Test
    fun calmWindLeavesGroupAlone() {
        assertEquals(WeatherGroup.CLEAR, resolveWeatherGroup(wmoCode = 0, windSpeedMs = 3.0))
        assertEquals(WeatherGroup.CLOUDY, resolveWeatherGroup(wmoCode = 2, windSpeedMs = 10.7))
    }

    @Test
    fun windThresholdIsInclusive() {
        assertEquals(WeatherGroup.WIND, resolveWeatherGroup(0, WindPolicy.STRONG_WIND_MS))
        assertEquals(WeatherGroup.CLEAR, resolveWeatherGroup(0, WindPolicy.STRONG_WIND_MS - 0.1))
    }

    @Test
    fun everyGroupKeyIsUniqueAndStable() {
        val keys = WeatherGroup.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(
            listOf("clear", "cloudy", "fog", "drizzle", "rain", "snow", "thunder", "wind"),
            keys,
        )
    }

    @Test
    fun bucketsAreExactlyTwentyFour() {
        assertEquals(24, Bucket.ALL.size)
        assertEquals(24, Bucket.ALL.map { it.key }.toSet().size)
    }
}
