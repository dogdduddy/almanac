package com.dogdduddy.almanac.location

import com.dogdduddy.almanac.weather.LocationKey
import kotlin.math.abs

data class Coordinates(val latitude: Double, val longitude: Double) {
    /** 캐시·결정론 키. 소수점 4자리로 접힌다. */
    val key: String get() = LocationKey.format(latitude, longitude)
}

/** 위치를 어떻게 얻었는지. 우선순위 판단에 쓴다. */
enum class LocationMode(val wire: String) {
    /** 유저가 직접 고른 도시. **GPS 보다 우선한다** — 명시적 선택이 추론을 이긴다. */
    MANUAL("manual"),
    GPS("gps"),
    /** 아직 아무것도 없을 때. 위젯이 빈 화면이 되지 않도록 하는 안전망. */
    DEFAULT("default"),
    ;

    companion object {
        fun fromWire(value: String): LocationMode =
            entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}

data class ResolvedLocation(
    val coordinates: Coordinates,
    val label: String,
    val mode: LocationMode,
)

/** 수동 선택용 도시. 좌표는 도심 근처면 충분하다 — 예보 셀 단위로 접힌다. */
data class City(
    val id: String,
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
) {
    val coordinates: Coordinates get() = Coordinates(latitude, longitude)
}

/**
 * 플랫폼 측위.
 *
 * 권한 요청은 UI 계층이 하고, 여기서는 **이미 권한이 있을 때만** 좌표를 얻는다.
 * 실패·거부는 예외가 아니라 null 이다 — 폴백이 정상 경로이기 때문이다.
 */
interface LocationSource {
    fun hasPermission(): Boolean

    /** @return 좌표. 권한이 없거나 측위에 실패하면 null */
    suspend fun currentCoordinates(): Coordinates?
}

expect fun systemLocationSource(): LocationSource

/**
 * 수동 선택용 도시 목록.
 *
 * 큰 데이터베이스를 붙이지 않는다. 이 앱은 지도 앱이 아니고, 목록의 목적은
 * **GPS 를 거부한 유저와 심사위원이 30초 안에 그럴듯한 하늘을 보는 것**이다.
 * 영어·일본어 시장과 심사위원이 있을 만한 도시를 우선해 골랐다.
 *
 * 좌표는 도심 근처 값이며, 예보는 어차피 격자 셀 단위라 이 정도면 충분하다.
 */
object Cities {

    val ALL: List<City> = listOf(
        City("seoul", "Seoul", "KR", 37.5665, 126.9780),
        City("busan", "Busan", "KR", 35.1796, 129.0756),
        City("tokyo", "Tokyo", "JP", 35.6762, 139.6503),
        City("osaka", "Osaka", "JP", 34.6937, 135.5023),
        City("kyoto", "Kyoto", "JP", 35.0116, 135.7681),
        City("sapporo", "Sapporo", "JP", 43.0618, 141.3545),
        City("new-york", "New York", "US", 40.7128, -74.0060),
        City("san-francisco", "San Francisco", "US", 37.7749, -122.4194),
        City("seattle", "Seattle", "US", 47.6062, -122.3321),
        City("chicago", "Chicago", "US", 41.8781, -87.6298),
        City("austin", "Austin", "US", 30.2672, -97.7431),
        City("los-angeles", "Los Angeles", "US", 34.0522, -118.2437),
        City("toronto", "Toronto", "CA", 43.6532, -79.3832),
        City("vancouver", "Vancouver", "CA", 49.2827, -123.1207),
        City("london", "London", "GB", 51.5074, -0.1278),
        City("edinburgh", "Edinburgh", "GB", 55.9533, -3.1883),
        City("dublin", "Dublin", "IE", 53.3498, -6.2603),
        City("paris", "Paris", "FR", 48.8566, 2.3522),
        City("berlin", "Berlin", "DE", 52.5200, 13.4050),
        City("amsterdam", "Amsterdam", "NL", 52.3676, 4.9041),
        City("copenhagen", "Copenhagen", "DK", 55.6761, 12.5683),
        City("stockholm", "Stockholm", "SE", 59.3293, 18.0686),
        City("oslo", "Oslo", "NO", 59.9139, 10.7522),
        City("helsinki", "Helsinki", "FI", 60.1699, 24.9384),
        City("reykjavik", "Reykjavik", "IS", 64.1466, -21.9426),
        City("lisbon", "Lisbon", "PT", 38.7223, -9.1393),
        City("madrid", "Madrid", "ES", 40.4168, -3.7038),
        City("rome", "Rome", "IT", 41.9028, 12.4964),
        City("zurich", "Zurich", "CH", 47.3769, 8.5417),
        City("warsaw", "Warsaw", "PL", 52.2297, 21.0122),
        City("sydney", "Sydney", "AU", -33.8688, 151.2093),
        City("melbourne", "Melbourne", "AU", -37.8136, 144.9631),
        City("auckland", "Auckland", "NZ", -36.8485, 174.7633),
        City("singapore", "Singapore", "SG", 1.3521, 103.8198),
        City("taipei", "Taipei", "TW", 25.0330, 121.5654),
        City("bangkok", "Bangkok", "TH", 13.7563, 100.5018),
        City("mumbai", "Mumbai", "IN", 19.0760, 72.8777),
        City("cape-town", "Cape Town", "ZA", -33.9249, 18.4241),
        City("sao-paulo", "São Paulo", "BR", -23.5505, -46.6333),
        City("buenos-aires", "Buenos Aires", "AR", -34.6037, -58.3816),
        City("mexico-city", "Mexico City", "MX", 19.4326, -99.1332),
    )

    /**
     * 앱을 한 번도 안 열었을 때 위젯이 쓸 기본값.
     * 아무 데도 아닌 곳보다는 어딘가의 진짜 하늘이 낫다.
     */
    val DEFAULT: City = ALL.first { it.id == "seoul" }

    fun byId(id: String): City? = ALL.firstOrNull { it.id == id }

    fun search(query: String): List<City> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return ALL
        return ALL.filter { it.name.lowercase().contains(q) || it.country.lowercase() == q }
    }

    /**
     * GPS 좌표에 가장 가까운 도시. 화면에 보여줄 **이름**을 얻기 위한 것이다.
     *
     * 역지오코딩을 쓰지 않는 이유: 플랫폼마다 결과 문자열이 다르고 네트워크를 타며
     * 로케일에 따라 번역된다. 이름이 흔들리면 캐시 키와 무관하더라도 화면이 흔들린다.
     *
     * 너무 멀면(기본 300km) null — "서울"이라고 우기느니 좌표만 보여주는 게 낫다.
     */
    fun nearest(coordinates: Coordinates, maxDistanceKm: Double = 300.0): City? =
        ALL.map { it to haversineKm(coordinates, it.coordinates) }
            .minByOrNull { it.second }
            ?.takeIf { it.second <= maxDistanceKm }
            ?.first
}

/** 두 좌표 사이 거리(km). 지구를 구로 근사한다 — 도시 매칭에는 충분하다. */
internal fun haversineKm(a: Coordinates, b: Coordinates): Double {
    val earthRadiusKm = 6371.0
    val dLat = toRadians(b.latitude - a.latitude)
    val dLon = toRadians(b.longitude - a.longitude)
    val lat1 = toRadians(a.latitude)
    val lat2 = toRadians(b.latitude)

    val h = sinSquaredHalf(dLat) + kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinSquaredHalf(dLon)
    return 2 * earthRadiusKm * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}

private fun sinSquaredHalf(radians: Double): Double {
    val s = kotlin.math.sin(radians / 2)
    return s * s
}

private fun toRadians(degrees: Double): Double = degrees * kotlin.math.PI / 180.0

/** 좌표를 사람이 읽을 형태로. 가까운 도시가 없을 때 라벨로 쓴다. */
internal fun formatCoordinateLabel(coordinates: Coordinates): String {
    val ns = if (coordinates.latitude >= 0) "N" else "S"
    val ew = if (coordinates.longitude >= 0) "E" else "W"
    fun one(value: Double) = (abs(value) * 10).toLong().let { "${it / 10}.${it % 10}" }
    return "${one(coordinates.latitude)}°$ns ${one(coordinates.longitude)}°$ew"
}
