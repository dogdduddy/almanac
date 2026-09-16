package com.dogdduddy.almanac.weather

import java.util.TimeZone

/**
 * 데스크톱(JVM) 구현. Android 구현과 같은 원칙이다 —
 * 오프셋만 플랫폼에서 얻고 날짜·시 계산은 공유 코드가 한다.
 * 그래야 세 플랫폼이 같은 순간에 같은 `dateKey` 를 낸다.
 */
private class DesktopDeviceClock : DeviceClock {

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

actual fun systemDeviceClock(): DeviceClock = DesktopDeviceClock()
