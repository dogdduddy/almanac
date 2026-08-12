package com.dogdduddy.almanac.weather

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

/**
 * iOS 구현.
 *
 * `NSDateFormatter` 를 쓰지 않는다. 유저의 달력 설정(일본 연호력, 불교력 등)이나
 * 로케일이 날짜 문자열을 바꿀 수 있고, 그러면 Android 와 다른 `dateKey` 가 나와
 * 같은 날 다른 문장이 뜬다.
 *
 * 타임존에서 **오프셋만** 얻어 공유 코드로 계산한다 — Android 와 정확히 같은 경로다.
 */
private class IosDeviceClock : DeviceClock {

    override fun nowEpochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

    override fun localDateKey(epochSeconds: Long): String =
        formatDateKey(epochSeconds, offsetSeconds(epochSeconds))

    override fun localHour(epochSeconds: Long): Int =
        localHourFrom(epochSeconds, offsetSeconds(epochSeconds))

    override fun utcOffsetString(epochSeconds: Long): String =
        formatUtcOffset(offsetSeconds(epochSeconds))

    /** DST 를 반영하려면 해당 시각 기준으로 물어야 한다. */
    private fun offsetSeconds(epochSeconds: Long): Long {
        val date = NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble())
        return NSTimeZone.localTimeZone.secondsFromGMTForDate(date).toLong()
    }
}

actual fun systemDeviceClock(): DeviceClock = IosDeviceClock()
