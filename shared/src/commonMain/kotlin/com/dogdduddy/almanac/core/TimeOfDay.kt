package com.dogdduddy.almanac.core

/**
 * 시간대 3분할. 경계는 시계가 아니라 **일출·일몰**이다.
 * `key` 는 DB 값이자 시드 입력이므로 변경 금지.
 */
enum class TimeOfDay(val key: String) {
    MORNING("morning"),
    DAY("day"),
    EVENING_NIGHT("evening_night"),
    ;

    companion object {
        fun fromKey(key: String): TimeOfDay? = entries.firstOrNull { it.key == key }
    }
}

object DaylightPolicy {
    /** 일출 후 이 시간까지를 '아침'으로 본다. */
    const val MORNING_SPAN_SECONDS: Long = 4 * 60 * 60

    /** 일출·일몰을 못 구했을 때(극야/극주/API 실패) 쓰는 폴백 경계 — 로컬 시각 기준. */
    const val FALLBACK_MORNING_START_HOUR: Int = 6
    const val FALLBACK_DAY_START_HOUR: Int = 10
    const val FALLBACK_EVENING_START_HOUR: Int = 18
}

/**
 * 일출·일몰 기준 시간대 판정.
 *
 * 모든 인자는 epoch seconds(UTC). 순수 함수이므로 플랫폼 간 결과가 반드시 일치한다.
 *
 * @param nowEpochSeconds 현재 시각
 * @param sunriseEpochSeconds 그날의 일출. 극야/극주 등으로 없으면 null
 * @param sunsetEpochSeconds 그날의 일몰. 없으면 null
 * @param localHour 폴백용 로컬 시(0..23). 일출·일몰이 null일 때만 사용
 */
fun resolveTimeOfDay(
    nowEpochSeconds: Long,
    sunriseEpochSeconds: Long?,
    sunsetEpochSeconds: Long?,
    localHour: Int,
): TimeOfDay {
    if (sunriseEpochSeconds == null || sunsetEpochSeconds == null) {
        return fallbackTimeOfDay(localHour)
    }
    // 일출이 일몰보다 늦게 오는 데이터는 신뢰할 수 없다 — 폴백.
    if (sunsetEpochSeconds <= sunriseEpochSeconds) return fallbackTimeOfDay(localHour)

    val morningEnd = minOf(sunriseEpochSeconds + DaylightPolicy.MORNING_SPAN_SECONDS, sunsetEpochSeconds)
    return when {
        nowEpochSeconds < sunriseEpochSeconds -> TimeOfDay.EVENING_NIGHT
        nowEpochSeconds < morningEnd -> TimeOfDay.MORNING
        nowEpochSeconds < sunsetEpochSeconds -> TimeOfDay.DAY
        else -> TimeOfDay.EVENING_NIGHT
    }
}

internal fun fallbackTimeOfDay(localHour: Int): TimeOfDay = when (localHour) {
    in DaylightPolicy.FALLBACK_MORNING_START_HOUR until DaylightPolicy.FALLBACK_DAY_START_HOUR -> TimeOfDay.MORNING
    in DaylightPolicy.FALLBACK_DAY_START_HOUR until DaylightPolicy.FALLBACK_EVENING_START_HOUR -> TimeOfDay.DAY
    else -> TimeOfDay.EVENING_NIGHT
}
