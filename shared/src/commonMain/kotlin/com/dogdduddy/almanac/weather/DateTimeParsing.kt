package com.dogdduddy.almanac.weather

/**
 * 날짜 파싱. 외부 라이브러리 없이 직접 소유한다.
 *
 * 여기서 나온 값(일출·일몰, 관측 시각)이 시간대 판정에 들어가고, 시간대는 시드의
 * 일부다. 즉 **파싱이 플랫폼마다 다르면 iOS 와 Android 가 다른 문장을 낸다.**
 * 해시를 직접 구현한 것과 같은 이유로 파서도 공유 코드에 둔다.
 */

/** 1970-01-01 부터의 일수. Howard Hinnant 의 days_from_civil. */
internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    var y = year.toLong()
    if (month <= 2) y -= 1
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

/**
 * ISO-8601 → epoch seconds.
 *
 * MET Norway 가 쓰는 두 형태를 모두 받는다.
 * - `2026-08-10T13:00:00Z`      (예보 timeseries)
 * - `2026-08-10T05:43+09:00`    (일출·일몰, 초 없음)
 *
 * @return 형식이 어긋나면 null — 추측하지 않는다.
 */
fun parseIsoToEpochSeconds(value: String): Long? {
    val text = value.trim()
    if (text.length < 16) return null
    if (text[4] != '-' || text[7] != '-') return null
    if (text[10] != 'T' && text[10] != 't' && text[10] != ' ') return null
    if (text[13] != ':') return null

    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val month = text.substring(5, 7).toIntOrNull() ?: return null
    val day = text.substring(8, 10).toIntOrNull() ?: return null
    val hour = text.substring(11, 13).toIntOrNull() ?: return null
    val minute = text.substring(14, 16).toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null

    var index = 16
    var second = 0
    if (index < text.length && text[index] == ':') {
        second = text.substring(index + 1, minOf(index + 3, text.length)).toIntOrNull() ?: return null
        if (second !in 0..60) return null
        index += 3
        // 소수점 이하 초는 버린다.
        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && text[index].isDigit()) index++
        }
    }

    val offsetSeconds = parseUtcOffsetSeconds(text.substring(minOf(index, text.length))) ?: return null
    val local = daysFromCivil(year, month, day) * 86_400L + hour * 3600L + minute * 60L + second
    return local - offsetSeconds
}

/** `Z` / `+09:00` / `-0330` / 빈 문자열(=UTC 취급). */
internal fun parseUtcOffsetSeconds(suffix: String): Long? {
    val text = suffix.trim()
    if (text.isEmpty() || text == "Z" || text == "z") return 0
    val sign = when (text[0]) {
        '+' -> 1L
        '-' -> -1L
        else -> return null
    }
    val digits = text.drop(1).replace(":", "")
    if (digits.length != 4) return null
    val hours = digits.substring(0, 2).toIntOrNull() ?: return null
    val minutes = digits.substring(2, 4).toIntOrNull() ?: return null
    if (hours > 18 || minutes > 59) return null
    return sign * (hours * 3600L + minutes * 60L)
}

private val HTTP_MONTHS = listOf(
    "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
)

/**
 * RFC 1123 HTTP-date → epoch seconds.
 *
 * `Expires: Mon, 10 Aug 2026 14:12:00 GMT` 를 캐시 정책에 넘기기 위해 필요하다.
 * 파싱에 실패하면 null 이고, 그러면 캐시는 최소 1시간 규칙으로 떨어진다 —
 * 즉 실패해도 라이선스 위반 쪽으로는 기울지 않는다.
 */
fun parseHttpDateToEpochSeconds(value: String): Long? {
    val parts = value.trim().removeSuffix(",").split(Regex("[ ,]+")).filter { it.isNotEmpty() }
    // ["Mon", "10", "Aug", "2026", "14:12:00", "GMT"]
    if (parts.size < 5) return null

    val day = parts[1].toIntOrNull() ?: return null
    val month = HTTP_MONTHS.indexOf(parts[2].lowercase()).takeIf { it >= 0 }?.plus(1) ?: return null
    val year = parts[3].toIntOrNull() ?: return null

    val time = parts[4].split(":")
    if (time.size != 3) return null
    val hour = time[0].toIntOrNull() ?: return null
    val minute = time[1].toIntOrNull() ?: return null
    val second = time[2].toIntOrNull() ?: return null
    if (day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

    return daysFromCivil(year, month, day) * 86_400L + hour * 3600L + minute * 60L + second
}
