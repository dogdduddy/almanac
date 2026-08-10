package com.dogdduddy.almanac.weather

/**
 * MET Norway `symbol_code` → WMO weather code.
 *
 * **MET Norway 는 WMO 코드를 주지 않는다.** `partlycloudy_night` 같은 심볼 문자열을 준다.
 * 우리 버킷 판정은 WMO 코드로 쓰여 있고 그 로직은 전수 테스트가 붙어 있으므로,
 * 코어를 건드리는 대신 여기서 심볼을 WMO 로 번역한다.
 *
 * 심볼 목록은 metno/weathericons 의 `weather/legend.csv` (41종) 를 기준으로 한다.
 * 변형 접미사 `_day` / `_night` / `_polartwilight` 는 하늘 상태와 무관하므로 떼어낸다.
 */
object MetSymbol {

    /** 밤낮 구분은 시간대 판정(일출·일몰)이 따로 하므로 여기서는 버린다. */
    private val VARIANT_SUFFIXES = listOf("_day", "_night", "_polartwilight")

    fun normalize(symbolCode: String): String {
        var base = symbolCode.trim().lowercase()
        for (suffix in VARIANT_SUFFIXES) {
            if (base.endsWith(suffix)) {
                base = base.removeSuffix(suffix)
                break
            }
        }
        return base
    }

    /**
     * @return WMO 코드. 모르는 심볼이면 null — 조용히 '맑음'으로 떨어뜨리지 않는다.
     */
    fun toWmoCode(symbolCode: String): Int? = when (normalize(symbolCode)) {
        // ---- 맑음 ----
        "clearsky" -> 0
        "fair" -> 1

        // ---- 흐림 ----
        "partlycloudy" -> 2
        "cloudy" -> 3

        // ---- 안개 ----
        "fog" -> 45

        // ---- 이슬비 ----
        // MET 은 drizzle 과 light rain 을 구분하지 않는다. lightrain 을 비로 보내면
        // 이슬비 버킷 3개(시간대 3)가 영원히 도달 불가능해지고, 거기 큐레이션한 문장이
        // 한 번도 안 나온다. 그래서 light 계열을 이슬비로 보낸다. (editorial 판단)
        "lightrain" -> 51
        "lightrainshowers" -> 53

        // ---- 비 ----
        "rain" -> 63
        "heavyrain" -> 65
        "rainshowers" -> 81
        "heavyrainshowers" -> 82

        // ---- 눈 ----
        "lightsnow" -> 71
        "snow" -> 73
        "heavysnow" -> 75
        "lightsnowshowers", "snowshowers" -> 85
        "heavysnowshowers" -> 86

        // ---- 진눈깨비 → 눈 ----
        // WMO 의 진눈깨비(68, 69, 83, 84)는 스펙이 정한 8그룹 어디에도 없다.
        // 매핑을 비워두면 진눈깨비 오는 날 화면이 빈다 (한국·일본 겨울에 실제로 온다).
        // 문학적으로도 진눈깨비 장면은 비보다 눈에 가깝다고 보고 눈 그룹으로 보낸다. (editorial 판단)
        "lightsleet" -> 71
        "sleet" -> 73
        "heavysleet" -> 75
        "lightsleetshowers", "sleetshowers" -> 85
        "heavysleetshowers" -> 86

        // ---- 뇌우 ----
        // 강수 종류와 무관하게 전부 뇌우 그룹이다. 뇌우는 그 자체로 장면의 주인공이다.
        "lightrainandthunder", "lightrainshowersandthunder",
        "rainandthunder", "rainshowersandthunder",
        "lightsleetandthunder", "sleetandthunder", "sleetshowersandthunder",
        "lightsnowandthunder", "snowandthunder", "snowshowersandthunder",
            -> 95

        // MET 자체 데이터의 오타 심볼("lights..."). 정상 철자도 함께 받아둔다.
        "lightssleetshowersandthunder", "lightsleetshowersandthunder",
        "lightssnowshowersandthunder", "lightsnowshowersandthunder",
            -> 95

        "heavyrainandthunder", "heavyrainshowersandthunder",
        "heavysleetandthunder", "heavysleetshowersandthunder",
        "heavysnowandthunder", "heavysnowshowersandthunder",
            -> 99

        else -> null
    }
}
