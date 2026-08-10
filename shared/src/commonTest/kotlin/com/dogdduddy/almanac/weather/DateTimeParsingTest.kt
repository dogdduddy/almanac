package com.dogdduddy.almanac.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 기대값은 참조 구현(Python datetime / email.utils)에서 독립적으로 계산했다.
 * JVM 과 Kotlin/Native 양쪽에서 돌며, 어긋나면 두 플랫폼이 다른 시간대를 판정하게 된다.
 */
class DateTimeParsingTest {

    @Test
    fun parsesForecastTimestamps() {
        assertEquals(1786366800L, parseIsoToEpochSeconds("2026-08-10T13:00:00Z"))
        assertEquals(0L, parseIsoToEpochSeconds("1970-01-01T00:00:00Z"))
    }

    /** 일출·일몰은 초가 없고 오프셋이 붙는다. */
    @Test
    fun parsesSunriseTimestampsWithoutSeconds() {
        assertEquals(1786308180L, parseIsoToEpochSeconds("2026-08-10T05:43+09:00"))
        assertEquals(1786357860L, parseIsoToEpochSeconds("2026-08-10T19:31+09:00"))
    }

    @Test
    fun handlesLeapDayAndHalfHourOffsets() {
        assertEquals(951825600L, parseIsoToEpochSeconds("2000-02-29T12:00:00Z"))
        assertEquals(1798774199L, parseIsoToEpochSeconds("2026-12-31T23:59:59-03:30"))
    }

    @Test
    fun fractionalSecondsAreIgnored() {
        assertEquals(1786366800L, parseIsoToEpochSeconds("2026-08-10T13:00:00.123Z"))
    }

    @Test
    fun malformedInputReturnsNullInsteadOfGuessing() {
        assertNull(parseIsoToEpochSeconds(""))
        assertNull(parseIsoToEpochSeconds("2026-08-10"))
        assertNull(parseIsoToEpochSeconds("10/08/2026 13:00"))
        assertNull(parseIsoToEpochSeconds("2026-13-10T13:00:00Z"))
        assertNull(parseIsoToEpochSeconds("2026-08-10T25:00:00Z"))
        assertNull(parseIsoToEpochSeconds("2026-08-10T13:00:00+9"))
    }

    @Test
    fun parsesHttpExpiresHeader() {
        assertEquals(1786371120L, parseHttpDateToEpochSeconds("Mon, 10 Aug 2026 14:12:00 GMT"))
        assertEquals(1786369245L, parseHttpDateToEpochSeconds("Mon, 10 Aug 2026 13:40:45 GMT"))
    }

    /**
     * 파싱 실패는 null 이어야 한다. 그러면 캐시가 "최소 1시간" 규칙으로 떨어져
     * 라이선스 위반 쪽으로는 절대 기울지 않는다.
     */
    @Test
    fun malformedHttpDateReturnsNull() {
        assertNull(parseHttpDateToEpochSeconds(""))
        assertNull(parseHttpDateToEpochSeconds("not a date"))
        assertNull(parseHttpDateToEpochSeconds("Mon, 10 Xxx 2026 14:12:00 GMT"))
    }

    @Test
    fun utcOffsetVariants() {
        assertEquals(0L, parseUtcOffsetSeconds("Z"))
        assertEquals(0L, parseUtcOffsetSeconds(""))
        assertEquals(32400L, parseUtcOffsetSeconds("+09:00"))
        assertEquals(-12600L, parseUtcOffsetSeconds("-0330"))
        assertNull(parseUtcOffsetSeconds("09:00"))
    }

    @Test
    fun daysFromCivilMatchesKnownEpochs() {
        assertEquals(0L, daysFromCivil(1970, 1, 1))
        assertEquals(-1L, daysFromCivil(1969, 12, 31))
        assertEquals(11016L, daysFromCivil(2000, 2, 29))
    }
}
