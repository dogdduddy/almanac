package com.dogdduddy.almanac.weather

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * MET Norway 캐싱 정책.
 *
 * **캐싱은 성능 최적화가 아니라 라이선스 의무다.** 지키지 않으면 차단당한다.
 *
 * 규칙 셋:
 * 1. 응답의 `Expires` 이전에는 다시 요청하지 않는다
 * 2. 그와 별개로 **최소 1시간**은 캐시를 쓴다 — 실측 결과 MET 의 Expires 가
 *    32분짜리로 오는 경우가 있어 Expires 만 믿으면 1시간 규칙이 깨진다
 * 3. 재요청 시 `If-Modified-Since` 를 붙인다. 304 면 본문 없이 신선도만 갱신한다
 *
 * 위젯이 있는 앱이라 이 로직이 느슨하면 백그라운드에서 조용히 쿼터를 태운다.
 */
object WeatherCachePolicy {

    /** 스펙이 정한 최소 캐시 수명. */
    const val MIN_TTL_SECONDS: Long = 3600

    /** 요청이 실패했을 때의 재시도 간격. 실패를 1시간 캐시로 취급하면 복구가 너무 늦다. */
    const val FAILURE_BACKOFF_SECONDS: Long = 15 * 60

    /** 연속 실패 시 백오프 상한. */
    const val MAX_FAILURE_BACKOFF_SECONDS: Long = MIN_TTL_SECONDS

    /**
     * 다음 요청이 허용되는 시각(epoch seconds).
     *
     * `Expires` 와 "받은 시각 + 1시간" 중 **늦은 쪽**이다.
     */
    fun nextAllowedRequestAt(
        fetchedAtEpochSeconds: Long,
        expiresAtEpochSeconds: Long?,
    ): Long {
        val minimum = fetchedAtEpochSeconds + MIN_TTL_SECONDS
        return maxOf(minimum, expiresAtEpochSeconds ?: minimum)
    }

    /** 캐시가 아직 유효한가. 유효하면 네트워크를 건드리지 않는다. */
    fun isFresh(
        fetchedAtEpochSeconds: Long,
        expiresAtEpochSeconds: Long?,
        nowEpochSeconds: Long,
    ): Boolean = nowEpochSeconds < nextAllowedRequestAt(fetchedAtEpochSeconds, expiresAtEpochSeconds)

    /**
     * 실패 후 재시도 가능 시각. 연속 실패마다 두 배로 늘리되 1시간을 넘기지 않는다.
     *
     * @param consecutiveFailures 1 이상
     */
    fun retryAfterFailureAt(
        failedAtEpochSeconds: Long,
        consecutiveFailures: Int,
    ): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 8)
        val backoff = (FAILURE_BACKOFF_SECONDS shl exponent)
            .coerceAtMost(MAX_FAILURE_BACKOFF_SECONDS)
        return failedAtEpochSeconds + backoff
    }
}

/**
 * 좌표 → 캐시 키.
 *
 * 두 가지를 동시에 해결한다.
 * - MET Norway 는 **좌표를 소수점 4자리 이하로 잘라 보낼 것**을 요구한다 (캐시 적중률 때문).
 * - 결정론 계약: 같은 장소인데 소수점 자리가 달라 다른 예보 셀을 타면
 *   두 기기가 다른 문장을 낸다. 반올림 규칙을 한 곳에 고정한다.
 *
 * 부호 있는 0(-0.0)이 "-0.0000" 으로 나가지 않도록 정규화한다.
 */
object LocationKey {

    const val DECIMALS: Int = 4
    private const val SCALE: Double = 10_000.0

    fun round(value: Double): Double {
        val rounded = (value * SCALE).roundToLong() / SCALE
        return if (rounded == 0.0) 0.0 else rounded
    }

    fun format(latitude: Double, longitude: Double): String =
        "${fixed(round(latitude))},${fixed(round(longitude))}"

    /** 소수점 4자리 고정 문자열. 플랫폼 로케일에 흔들리지 않도록 직접 만든다. */
    private fun fixed(value: Double): String {
        val negative = value < 0
        val scaled = (abs(value) * SCALE).roundToLong()
        val whole = scaled / 10_000
        val frac = (scaled % 10_000).toString().padStart(DECIMALS, '0')
        return "${if (negative) "-" else ""}$whole.$frac"
    }
}
