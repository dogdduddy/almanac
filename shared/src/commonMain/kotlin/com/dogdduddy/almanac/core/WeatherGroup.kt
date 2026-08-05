package com.dogdduddy.almanac.core

/**
 * 날씨 8그룹. `key` 는 DB의 `entries.weather_group` 컬럼 값이자 시드 입력이므로
 * **절대 변경하지 말 것** — 바꾸면 과거 히스토리의 재현성이 깨진다.
 */
enum class WeatherGroup(val key: String) {
    CLEAR("clear"),
    CLOUDY("cloudy"),
    FOG("fog"),
    DRIZZLE("drizzle"),
    RAIN("rain"),
    SNOW("snow"),
    THUNDER("thunder"),
    WIND("wind"),
    ;

    companion object {
        fun fromKey(key: String): WeatherGroup? = entries.firstOrNull { it.key == key }
    }
}

/**
 * WMO weather code → 날씨 그룹.
 *
 * 바람(WIND)은 WMO 코드가 없으므로 여기서는 절대 반환되지 않는다.
 * 풍속 판정은 [resolveWeatherGroup] 이 담당한다.
 */
fun weatherGroupFromWmoCode(code: Int): WeatherGroup? = when (code) {
    0, 1 -> WeatherGroup.CLEAR
    2, 3 -> WeatherGroup.CLOUDY
    45, 48 -> WeatherGroup.FOG
    51, 53, 55, 56, 57 -> WeatherGroup.DRIZZLE
    in 61..67, 80, 81, 82 -> WeatherGroup.RAIN
    in 71..77, 85, 86 -> WeatherGroup.SNOW
    95, 96, 99 -> WeatherGroup.THUNDER
    else -> null
}

object WindPolicy {
    /**
     * 바람 버킷 판정 임계값 (m/s). Beaufort 6 "strong breeze" = 10.8 m/s.
     * 나뭇가지가 흔들리고 우산을 못 쓰는 수준 — 문학에서 "바람"이라고 부르는 지점.
     */
    const val STRONG_WIND_MS: Double = 10.8

    /**
     * 바람이 덮어쓸 수 있는 그룹.
     *
     * 맑음/흐림은 그 자체로 날씨의 주인공이 아니므로 바람에 양보한다.
     * 반면 비·눈·뇌우는 강풍이어도 그쪽이 장면의 주인공이라 유지한다.
     * (스펙의 "코드가 맑음이어도 풍속이 세면 바람 우선" 을 이렇게 해석했다.)
     */
    val OVERRIDABLE: Set<WeatherGroup> = setOf(WeatherGroup.CLEAR, WeatherGroup.CLOUDY)
}

/**
 * 최종 날씨 그룹 판정. WMO 코드 + 풍속을 함께 본다.
 *
 * @param wmoCode MET Norway → WMO 매핑 코드
 * @param windSpeedMs 풍속 (m/s)
 * @return 판정된 그룹. 알 수 없는 코드면 null
 */
fun resolveWeatherGroup(wmoCode: Int, windSpeedMs: Double): WeatherGroup? {
    val base = weatherGroupFromWmoCode(wmoCode) ?: return null
    val windy = windSpeedMs >= WindPolicy.STRONG_WIND_MS
    return if (windy && base in WindPolicy.OVERRIDABLE) WeatherGroup.WIND else base
}

/** 강풍이었는지 여부 — `daily_page.wind_flag` 에 기록해 사후 분석에 쓴다. */
fun isWindFlag(windSpeedMs: Double): Boolean = windSpeedMs >= WindPolicy.STRONG_WIND_MS
