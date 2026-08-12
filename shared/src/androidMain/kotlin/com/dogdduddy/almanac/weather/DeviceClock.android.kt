package com.dogdduddy.almanac.weather

import java.util.TimeZone

/**
 * Android 구현.
 *
 * 날짜 계산을 `java.time.LocalDate` 나 `SimpleDateFormat` 에 맡기지 않고
 * **오프셋만 얻어서 공유 코드로 계산한다.** 그래야 iOS 와 같은 코드 경로를 타고,
 * 로케일·달력(연호력 등) 설정이 날짜를 흔들지 못한다.
 */
private class AndroidDeviceClock : DeviceClock {

    override fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    override fun localDateKey(epochSeconds: Long): String =
        formatDateKey(epochSeconds, offsetSeconds(epochSeconds))

    override fun localHour(epochSeconds: Long): Int =
        localHourFrom(epochSeconds, offsetSeconds(epochSeconds))

    override fun utcOffsetString(epochSeconds: Long): String =
        formatUtcOffset(offsetSeconds(epochSeconds))

    /** DST 를 반영하려면 해당 시각 기준으로 물어야 한다. */
    private fun offsetSeconds(epochSeconds: Long): Long =
        TimeZone.getDefault().getOffset(epochSeconds * 1000).toLong() / 1000
}

actual fun systemDeviceClock(): DeviceClock = AndroidDeviceClock()
