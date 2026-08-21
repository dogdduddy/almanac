package com.dogdduddy.almanac.weather

import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.core.isWindFlag
import com.dogdduddy.almanac.core.resolveWeatherGroup

/**
 * 버킷 판정에 필요한 최소한의 날씨 상태.
 *
 * 예보 전체를 들고 다니지 않는다. 문장을 고르는 값과 오늘 페이지에 작게 보여줄
 * 기온만 남기고, 강수량·체감온도 같은 날씨 앱용 세부 정보는 보존하지 않는다.
 */
data class WeatherSnapshot(
    val wmoCode: Int,
    val windSpeedMs: Double,
    val temperatureC: Double?,
    /** 이 관측/예보 시각 (epoch seconds). */
    val observedAtEpochSeconds: Long,
) {
    val weatherGroup: WeatherGroup? get() = resolveWeatherGroup(wmoCode, windSpeedMs)
    val windFlag: Boolean get() = isWindFlag(windSpeedMs)
}

/**
 * 그날의 일출·일몰 (epoch seconds).
 *
 * 극야·극주에서는 둘 다 null 일 수 있다. 그 경우 시간대 판정은 시계 기준으로 폴백한다.
 */
data class SunTimes(
    val sunriseEpochSeconds: Long?,
    val sunsetEpochSeconds: Long?,
)

/** 네트워크 응답의 신선도 메타. 캐시 정책이 쓴다. */
data class FetchMeta(
    val fetchedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long?,
    /** 다음 요청의 `If-Modified-Since` 에 그대로 넣을 원문 헤더 값. */
    val lastModified: String?,
)

/**
 * 날씨 출처. 구현은 [MetNorwayClient] 하나뿐이지만, 저장소가 Ktor 없이
 * 테스트될 수 있도록 인터페이스로 끊는다.
 */
interface WeatherSource {
    suspend fun fetchWeather(
        latitude: Double,
        longitude: Double,
        lastModified: String? = null,
    ): WeatherFetch

    suspend fun fetchSunTimes(
        latitude: Double,
        longitude: Double,
        date: String,
        utcOffset: String,
    ): SunTimes?
}

sealed interface WeatherFetch {
    /** 새 데이터를 받았다. */
    data class Updated(val snapshot: WeatherSnapshot, val meta: FetchMeta) : WeatherFetch

    /** 304 — 서버가 "안 바뀌었다"고 답했다. 본문은 없고 신선도만 갱신한다. */
    data class NotModified(val meta: FetchMeta) : WeatherFetch

    /** 실패. 호출자는 캐시된 값을 계속 쓰고 백오프한다. */
    data class Failed(val reason: String, val cause: Throwable? = null) : WeatherFetch
}
