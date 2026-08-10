package com.dogdduddy.almanac.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeatherCachePolicyTest {

    private val fetchedAt = 1_800_000_000L
    private val oneHour = WeatherCachePolicy.MIN_TTL_SECONDS

    /**
     * 실측: MET 이 Expires 를 32분 뒤로 주는 경우가 있다.
     * Expires 만 믿으면 1시간 최소 캐시 규칙이 깨진다 — 라이선스 위반이다.
     */
    @Test
    fun shortExpiresIsRaisedToOneHour() {
        val expires = fetchedAt + 32 * 60
        assertEquals(fetchedAt + oneHour, WeatherCachePolicy.nextAllowedRequestAt(fetchedAt, expires))
        assertTrue(WeatherCachePolicy.isFresh(fetchedAt, expires, fetchedAt + 50 * 60))
    }

    /** 반대로 Expires 가 더 멀면 그쪽을 따른다. 서버가 원하는 것보다 자주 묻지 않는다. */
    @Test
    fun longExpiresIsRespected() {
        val expires = fetchedAt + 3 * oneHour
        assertEquals(expires, WeatherCachePolicy.nextAllowedRequestAt(fetchedAt, expires))
        assertTrue(WeatherCachePolicy.isFresh(fetchedAt, expires, fetchedAt + 2 * oneHour))
        assertFalse(WeatherCachePolicy.isFresh(fetchedAt, expires, expires))
    }

    @Test
    fun missingExpiresFallsBackToOneHour() {
        assertEquals(fetchedAt + oneHour, WeatherCachePolicy.nextAllowedRequestAt(fetchedAt, null))
        assertTrue(WeatherCachePolicy.isFresh(fetchedAt, null, fetchedAt + oneHour - 1))
        assertFalse(WeatherCachePolicy.isFresh(fetchedAt, null, fetchedAt + oneHour))
    }

    @Test
    fun freshnessBoundaryIsExclusive() {
        val at = WeatherCachePolicy.nextAllowedRequestAt(fetchedAt, null)
        assertTrue(WeatherCachePolicy.isFresh(fetchedAt, null, at - 1))
        assertFalse(WeatherCachePolicy.isFresh(fetchedAt, null, at))
    }

    @Test
    fun failureBackoffGrowsButIsCapped() {
        val first = WeatherCachePolicy.retryAfterFailureAt(fetchedAt, 1)
        assertEquals(fetchedAt + WeatherCachePolicy.FAILURE_BACKOFF_SECONDS, first)

        val second = WeatherCachePolicy.retryAfterFailureAt(fetchedAt, 2)
        assertEquals(fetchedAt + 2 * WeatherCachePolicy.FAILURE_BACKOFF_SECONDS, second)

        // 아무리 실패해도 1시간을 넘겨 기다리지 않는다.
        for (n in 1..50) {
            val at = WeatherCachePolicy.retryAfterFailureAt(fetchedAt, n)
            assertTrue(at <= fetchedAt + WeatherCachePolicy.MAX_FAILURE_BACKOFF_SECONDS, "n=$n")
        }
    }

    /** 실패 재시도는 정상 캐시보다 빨라야 한다 — 아니면 장애에서 복구가 너무 늦다. */
    @Test
    fun firstFailureRetriesSoonerThanNormalTtl() {
        assertTrue(
            WeatherCachePolicy.retryAfterFailureAt(fetchedAt, 1) <
                WeatherCachePolicy.nextAllowedRequestAt(fetchedAt, null)
        )
    }
}

class LocationKeyTest {

    /** MET Norway 는 좌표를 소수점 4자리 이하로 잘라 보낼 것을 요구한다. */
    @Test
    fun formatsToFourDecimals() {
        assertEquals("37.5665,126.9780", LocationKey.format(37.5665, 126.9780))
        assertEquals("37.5665,126.9780", LocationKey.format(37.56652871, 126.97803123))
    }

    @Test
    fun negativeCoordinatesKeepSign() {
        assertEquals("-33.8688,-151.2093", LocationKey.format(-33.8688, -151.2093))
    }

    @Test
    fun wholeNumbersArePadded() {
        assertEquals("0.0000,0.0000", LocationKey.format(0.0, 0.0))
        assertEquals("90.0000,180.0000", LocationKey.format(90.0, 180.0))
    }

    /** -0.0 이 "-0.0000" 으로 새면 같은 장소가 두 개의 캐시 키를 갖는다. */
    @Test
    fun negativeZeroIsNormalized() {
        assertEquals("0.0000,0.0000", LocationKey.format(-0.0, -0.00001))
    }

    /**
     * 결정론 계약: 같은 장소면 두 기기가 같은 키를 만들어야 한다.
     * 미세하게 다른 GPS 좌표가 같은 키로 접히는지 확인한다.
     */
    @Test
    fun nearbyCoordinatesCollapseToSameKey() {
        val a = LocationKey.format(37.56651, 126.97801)
        val b = LocationKey.format(37.56649, 126.97799)
        assertEquals(a, b)
    }

    @Test
    fun roundingIsHalfUpAndStable() {
        assertEquals(0.1235, LocationKey.round(0.12345))
        assertEquals(-0.1234, LocationKey.round(-0.12344))
        assertEquals(0.0, LocationKey.round(0.00001))
    }
}
