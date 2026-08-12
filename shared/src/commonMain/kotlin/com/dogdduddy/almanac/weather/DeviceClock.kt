package com.dogdduddy.almanac.weather

/**
 * 기기 시각과 로컬 달력.
 *
 * **여기가 결정론 계약의 가장 취약한 지점이다.** `dateKey` 와 로컬 시(hour)가
 * 시드와 시간대 판정에 들어가므로, 두 플랫폼이 같은 순간에 같은 값을 내야 한다.
 *
 * 그래서 인터페이스로 뽑아 테스트에서 고정할 수 있게 하고,
 * 실제 구현은 플랫폼별로 **그레고리력을 명시적으로 강제**한다
 * (일본 연호력·불교력 등을 쓰는 유저가 있어도 같은 날짜가 나와야 한다).
 */
interface DeviceClock {
    fun nowEpochSeconds(): Long

    /** 기기 로컬 달력 날짜. ISO-8601 `yyyy-MM-dd`, 제로 패딩. */
    fun localDateKey(epochSeconds: Long): String

    /** 일출·일몰을 못 구했을 때의 폴백용 로컬 시(0..23). */
    fun localHour(epochSeconds: Long): Int

    /** MET 일출 API 의 `offset` 파라미터 형식. `+09:00`. */
    fun utcOffsetString(epochSeconds: Long): String
}

/** 플랫폼 구현. */
expect fun systemDeviceClock(): DeviceClock

/** 테스트·위젯 미리보기용 고정 시계. */
class FixedDeviceClock(
    private var now: Long,
    private val dateKey: String,
    private val hour: Int,
    private val offset: String = "+00:00",
) : DeviceClock {
    override fun nowEpochSeconds() = now
    override fun localDateKey(epochSeconds: Long) = dateKey
    override fun localHour(epochSeconds: Long) = hour
    override fun utcOffsetString(epochSeconds: Long) = offset
    fun advance(seconds: Long) { now += seconds }
}

/** epoch seconds + 오프셋 → `yyyy-MM-dd`. 플랫폼 포매터를 안 타므로 로케일에 흔들리지 않는다. */
internal fun formatDateKey(epochSeconds: Long, offsetSeconds: Long): String {
    val local = epochSeconds + offsetSeconds
    var days = local.floorDiv(86_400L)
    val (year, month, day) = civilFromDays(days)
    return "${year.toString().padStart(4, '0')}-" +
        "${month.toString().padStart(2, '0')}-" +
        day.toString().padStart(2, '0')
}

internal fun localHourFrom(epochSeconds: Long, offsetSeconds: Long): Int {
    val secondsOfDay = (epochSeconds + offsetSeconds).mod(86_400L)
    return (secondsOfDay / 3600L).toInt()
}

internal fun formatUtcOffset(offsetSeconds: Long): String {
    val sign = if (offsetSeconds < 0) "-" else "+"
    val abs = if (offsetSeconds < 0) -offsetSeconds else offsetSeconds
    val hours = (abs / 3600).toString().padStart(2, '0')
    val minutes = ((abs % 3600) / 60).toString().padStart(2, '0')
    return "$sign$hours:$minutes"
}

/** days_from_civil 의 역함수. */
internal fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    val z = daysSinceEpoch + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}
