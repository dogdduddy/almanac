package com.dogdduddy.almanac.weather

import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.core.weatherGroupFromWmoCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetSymbolTest {

    /**
     * metno/weathericons 의 weather/legend.csv 전체(41종). 2026-08-10 확인.
     * MET 이 심볼을 추가하면 이 목록에 넣고 매핑을 채워야 한다 —
     * 안 채우면 그 날씨에 화면이 빈다.
     */
    private val allMetSymbols = listOf(
        "clearsky", "cloudy", "fair", "fog",
        "heavyrain", "heavyrainandthunder", "heavyrainshowers", "heavyrainshowersandthunder",
        "heavysleet", "heavysleetandthunder", "heavysleetshowers", "heavysleetshowersandthunder",
        "heavysnow", "heavysnowandthunder", "heavysnowshowers", "heavysnowshowersandthunder",
        "lightrain", "lightrainandthunder", "lightrainshowers", "lightrainshowersandthunder",
        "lightsleet", "lightsleetandthunder", "lightsleetshowers",
        "lightsnow", "lightsnowandthunder", "lightsnowshowers",
        "lightssleetshowersandthunder", "lightssnowshowersandthunder",
        "partlycloudy",
        "rain", "rainandthunder", "rainshowers", "rainshowersandthunder",
        "sleet", "sleetandthunder", "sleetshowers", "sleetshowersandthunder",
        "snow", "snowandthunder", "snowshowers", "snowshowersandthunder",
    )

    @Test
    fun legendHasExpectedSize() {
        assertEquals(41, allMetSymbols.size)
        assertEquals(41, allMetSymbols.toSet().size)
    }

    /** 41종 전부가 WMO 로 번역되고, 번역 결과가 8그룹 중 하나로 떨어져야 한다. */
    @Test
    fun everyMetSymbolResolvesToAWeatherGroup() {
        for (symbol in allMetSymbols) {
            val wmo = MetSymbol.toWmoCode(symbol)
            assertNotNull(wmo, "심볼 '$symbol' 이 WMO 로 번역되지 않는다 — 그 날씨에 화면이 빈다")
            assertNotNull(
                weatherGroupFromWmoCode(wmo),
                "심볼 '$symbol' → WMO $wmo 인데 이 코드가 어느 그룹에도 안 속한다",
            )
        }
    }

    /** 밤낮·극지 변형이 붙어도 결과는 같아야 한다. */
    @Test
    fun variantSuffixesDoNotChangeResult() {
        for (symbol in allMetSymbols) {
            val base = MetSymbol.toWmoCode(symbol)
            for (suffix in listOf("_day", "_night", "_polartwilight")) {
                assertEquals(base, MetSymbol.toWmoCode(symbol + suffix), "$symbol$suffix")
            }
        }
    }

    /** 바람을 뺀 7그룹이 전부 MET 심볼로 도달 가능해야 한다. 도달 불가 그룹은 죽은 큐레이션이다. */
    @Test
    fun everyGroupExceptWindIsReachable() {
        val reachable = allMetSymbols
            .mapNotNull { MetSymbol.toWmoCode(it) }
            .mapNotNull { weatherGroupFromWmoCode(it) }
            .toSet()

        val expected = WeatherGroup.entries.toSet() - WeatherGroup.WIND
        assertEquals(expected, reachable, "도달 불가능한 그룹이 있다 — 그 버킷의 문장은 영원히 안 나온다")
    }

    @Test
    fun lightRainIsDrizzleNotRain() {
        // editorial 판단: MET 은 drizzle 을 따로 주지 않으므로 light 계열을 이슬비로 본다.
        // 이걸 비로 바꾸면 이슬비 버킷 3개가 죽는다.
        assertEquals(WeatherGroup.DRIZZLE, groupOf("lightrain"))
        assertEquals(WeatherGroup.DRIZZLE, groupOf("lightrainshowers"))
        assertEquals(WeatherGroup.RAIN, groupOf("rain"))
        assertEquals(WeatherGroup.RAIN, groupOf("heavyrain"))
    }

    @Test
    fun sleetIsMappedToSnow() {
        // WMO 의 진눈깨비(68/69/83/84)는 스펙의 8그룹에 없다. 비워두면 화면이 빈다.
        assertEquals(WeatherGroup.SNOW, groupOf("sleet"))
        assertEquals(WeatherGroup.SNOW, groupOf("lightsleet"))
        assertEquals(WeatherGroup.SNOW, groupOf("heavysleetshowers"))
    }

    @Test
    fun anyThunderSymbolBecomesThunder() {
        val thunderSymbols = allMetSymbols.filter { it.contains("thunder") }
        assertTrue(thunderSymbols.size >= 16, "뇌우 심볼이 예상보다 적다: ${thunderSymbols.size}")
        for (symbol in thunderSymbols) {
            assertEquals(WeatherGroup.THUNDER, groupOf(symbol), symbol)
        }
    }

    @Test
    fun metsOwnTypoSpellingIsAccepted() {
        // legend.csv 에 실제로 "lights..." 로 들어 있는 오타 심볼. 정상 철자도 함께 받는다.
        assertEquals(WeatherGroup.THUNDER, groupOf("lightssleetshowersandthunder"))
        assertEquals(WeatherGroup.THUNDER, groupOf("lightsleetshowersandthunder"))
        assertEquals(WeatherGroup.THUNDER, groupOf("lightssnowshowersandthunder"))
        assertEquals(WeatherGroup.THUNDER, groupOf("lightsnowshowersandthunder"))
    }

    @Test
    fun unknownSymbolReturnsNullInsteadOfGuessing() {
        assertNull(MetSymbol.toWmoCode("meteorshower"))
        assertNull(MetSymbol.toWmoCode(""))
        assertNull(MetSymbol.toWmoCode("clearsky_tuesday"))
    }

    @Test
    fun inputIsTrimmedAndCaseInsensitive() {
        assertEquals(0, MetSymbol.toWmoCode("  CLEARSKY_DAY  "))
    }

    private fun groupOf(symbol: String): WeatherGroup? =
        MetSymbol.toWmoCode(symbol)?.let { weatherGroupFromWmoCode(it) }
}
