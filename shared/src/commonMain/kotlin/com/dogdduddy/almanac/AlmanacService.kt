package com.dogdduddy.almanac

import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.billing.Billing
import com.dogdduddy.almanac.billing.BillingProduct
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.data.ResolvedPage
import com.dogdduddy.almanac.location.City
import com.dogdduddy.almanac.location.LocationMode
import com.dogdduddy.almanac.location.LocationRepository
import com.dogdduddy.almanac.location.ResolvedLocation
import com.dogdduddy.almanac.weather.DeviceClock
import com.dogdduddy.almanac.weather.WeatherRepository
import com.dogdduddy.almanac.weather.civilFromDays

/**
 * 화면·위젯이 그리는 데 필요한 전부.
 *
 * 주인공은 문장이 아니라 **시간적 거리**다. [yearsAgo] 가 가장 큰 활자로 나간다.
 */
data class TodaysPage(
    val yearsAgo: Int,
    val text: String,
    val author: String,
    val title: String,
    val section: String?,
    val year: Int,
    val weatherGroup: WeatherGroup,
    val timeOfDay: TimeOfDay,
    val locationLabel: String,
    val dateKey: String,
) {
    /** "Wuthering Heights, 1847 · Emily Brontë" */
    val attribution: String get() = "$title, $year · $author"

    /**
     * 앱의 한 줄 컨셉을 화면에서 실제로 말하는 문장.
     *
     * 지금은 영어 고정이다. Android 위젯은 문자열 리소스를 쓰고 있고,
     * 공유 화면의 번역은 CMP resources 를 붙일 때 함께 옮긴다.
     */
    val weatherPhrase: String
        get() = when (weatherGroup) {
            WeatherGroup.CLEAR -> "someone watched this sky"
            WeatherGroup.CLOUDY -> "someone watched these clouds"
            WeatherGroup.FOG -> "someone watched this fog"
            WeatherGroup.DRIZZLE -> "someone watched this drizzle"
            WeatherGroup.RAIN -> "someone watched this rain"
            WeatherGroup.SNOW -> "someone watched this snow"
            WeatherGroup.THUNDER -> "someone watched this storm"
            WeatherGroup.WIND -> "someone watched this wind"
        }
}

/** 페이지를 못 만든 이유. 화면이 무엇을 안내할지 결정한다. */
enum class PageUnavailable {
    /** 날씨를 못 구했고 캐시도 없다. 보통 첫 실행 + 오프라인. */
    NO_WEATHER,

    /** 이 버킷에 문장이 없다. 큐레이션 구멍이거나 보유 팩이 좁다. */
    NO_CONTENT,
}

sealed interface PageResult {
    data class Ready(val page: TodaysPage) : PageResult
    data class Unavailable(val reason: PageUnavailable) : PageResult
}

/**
 * 앱과 위젯이 공유하는 단일 진입점.
 *
 * **위젯이 이 함수를 그대로 부른다.** 그래야 같은 순간에 같은 문장이 나온다 —
 * 위젯이 자기만의 조회 경로를 가지면 결정론이 깨지고 캐시도 두 벌이 된다.
 */
class AlmanacService(
    private val content: AlmanacRepository,
    private val weather: WeatherRepository,
    private val location: LocationRepository,
    private val clock: DeviceClock,
) {

    /**
     * @param refreshLocation 앱에서는 true, **위젯에서는 false**.
     *   위젯은 측위를 기다릴 수 없으므로 저장된 위치만 쓴다.
     */
    suspend fun todaysPage(
        language: String = "en",
        refreshLocation: Boolean = false,
    ): PageResult {
        content.ensureBaseEntitlement()

        val place = if (refreshLocation) location.refresh() else location.current()
        val conditions = weather.currentConditions(
            latitude = place.coordinates.latitude,
            longitude = place.coordinates.longitude,
        ) ?: return PageResult.Unavailable(PageUnavailable.NO_WEATHER)

        val installId = content.installId { newInstallId(clock.nowEpochSeconds()) }

        val resolved = content.resolvePage(
            dateKey = conditions.dateKey,
            timeOfDay = conditions.timeOfDay,
            weatherGroup = conditions.weatherGroup,
            language = language,
            locationKey = conditions.locationKey,
            windFlag = conditions.windFlag,
            installId = installId,
        ) ?: return PageResult.Unavailable(PageUnavailable.NO_CONTENT)

        return PageResult.Ready(
            resolved.toTodaysPage(
                currentYear = currentYear(clock),
                weatherGroup = conditions.weatherGroup,
                timeOfDay = conditions.timeOfDay,
                locationLabel = place.label,
                dateKey = conditions.dateKey,
            )
        )
    }

    /**
     * 오늘 + 지난 기록. 화면이 페이지 넘김으로 훑는 목록이다.
     *
     * **인덱스 0 이 오늘이고 뒤로 갈수록 과거다.** 화면은 이 순서를 역방향 넘김으로
     * 표현한다 — 과거를 되돌아 펼치는 방향.
     *
     * 오늘 페이지를 못 만들어도 아카이브는 보여준다. 오프라인이어도 어제까지의
     * 기록은 읽을 수 있어야 한다.
     */
    suspend fun pages(
        language: String = "en",
        refreshLocation: Boolean = false,
        archiveLimit: Int = 60,
    ): PagesState {
        val today = todaysPage(language, refreshLocation)
        val year = currentYear(clock)

        val past = content.archivedPages(archiveLimit).map { archived ->
            TodaysPage(
                yearsAgo = year - archived.entry.year.toInt(),
                text = archived.entry.text,
                author = archived.entry.author,
                title = archived.entry.title,
                section = archived.entry.section,
                year = archived.entry.year.toInt(),
                weatherGroup = archived.weatherGroup,
                timeOfDay = archived.timeOfDay,
                // 기록에는 위치 이름이 없다(좌표 키만 남는다). 화면은 비면 감춘다.
                locationLabel = "",
                dateKey = archived.dateKey,
            )
        }

        val place = location.current()
        return when (today) {
            is PageResult.Ready -> {
                // 오늘 기록은 이미 daily_page 에 저장돼 아카이브 첫 줄과 겹친다. 중복 제거.
                val withoutToday = past.filterNot {
                    it.dateKey == today.page.dateKey && it.timeOfDay == today.page.timeOfDay
                }
                PagesState.Ready(listOf(today.page) + withoutToday, place.label, place.mode)
            }

            is PageResult.Unavailable ->
                if (past.isEmpty()) {
                    PagesState.Empty(today.reason, place.label, place.mode)
                } else {
                    PagesState.Ready(past, place.label, place.mode)
                }
        }
    }

    // ---- 판매 -----------------------------------------------------------------

    /**
     * 페이월에 필요한 정보.
     *
     * 팔 것이 없으면(전부 보유했거나 결제 미설정) `canUpgrade = false` 이고
     * 화면은 진입점 자체를 감춘다 — 살 수 없는 버튼을 두지 않는다.
     */
    suspend fun paywall(billing: Billing): PaywallInfo {
        val owned = content.ownedPackIds().toSet()
        val products = runCatching { billing.products() }.getOrElse { emptyList() }
            .filterNot { it.packId in owned }
        return PaywallInfo(products = products, canUpgrade = products.isNotEmpty())
    }

    // ---- 위치 선택 -----------------------------------------------------------

    fun currentPlace(): ResolvedLocation = location.current()

    /** 유저가 도시를 골랐다. 이후 GPS 는 이 선택을 덮지 않는다. */
    fun selectCity(city: City): ResolvedLocation = location.selectCity(city)

    /** 수동 선택을 풀고 GPS 로 되돌린다. */
    fun useGps(): ResolvedLocation = location.useGps()
}

/** 페이월 진입 여부와 살 수 있는 상품. */
data class PaywallInfo(
    val products: List<BillingProduct>,
    val canUpgrade: Boolean,
)

sealed interface PagesState {
    /** `pages[0]` 이 가장 최신이다. */
    data class Ready(
        val pages: List<TodaysPage>,
        val locationLabel: String,
        val locationMode: LocationMode,
    ) : PagesState

    data class Empty(
        val reason: PageUnavailable,
        val locationLabel: String,
        val locationMode: LocationMode,
    ) : PagesState
}

private fun ResolvedPage.toTodaysPage(
    currentYear: Int,
    weatherGroup: WeatherGroup,
    timeOfDay: TimeOfDay,
    locationLabel: String,
    dateKey: String,
) = TodaysPage(
    yearsAgo = currentYear - entry.year.toInt(),
    text = entry.text,
    author = entry.author,
    title = entry.title,
    section = entry.section,
    year = entry.year.toInt(),
    weatherGroup = weatherGroup,
    timeOfDay = timeOfDay,
    locationLabel = locationLabel,
    dateKey = dateKey,
)

internal fun currentYear(clock: DeviceClock): Int {
    val key = clock.localDateKey(clock.nowEpochSeconds())
    return key.substringBefore('-').toIntOrNull()
        ?: civilFromDays(clock.nowEpochSeconds() / 86_400L).first
}

/**
 * 설치 ID. UUID v4 형태의 소문자 문자열.
 *
 * 플랫폼 UUID API 에 의존하지 않는다 — 형식이 흔들리면 시드가 갈라지기 때문이다.
 * 암호학적 강도는 필요 없다. 필요한 건 **설치마다 다르고 이후 불변**인 것뿐이다.
 */
internal fun newInstallId(seedEpochSeconds: Long): String {
    var state = seedEpochSeconds.toULong() * 6364136223846793005uL + 1442695040888963407uL
    fun nextHex(): Char {
        state = state * 6364136223846793005uL + 1442695040888963407uL
        return "0123456789abcdef"[((state shr 33) % 16uL).toInt()]
    }
    return buildString {
        repeat(8) { append(nextHex()) }; append('-')
        repeat(4) { append(nextHex()) }; append('-')
        append('4'); repeat(3) { append(nextHex()) }; append('-')
        append("89ab"[((state shr 29) % 4uL).toInt()]); repeat(3) { append(nextHex()) }; append('-')
        repeat(12) { append(nextHex()) }
    }
}
