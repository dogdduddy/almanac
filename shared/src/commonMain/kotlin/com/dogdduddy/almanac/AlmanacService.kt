package com.dogdduddy.almanac

import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.fnv1a64
import com.dogdduddy.almanac.core.randomUuid
import com.dogdduddy.almanac.billing.Billing
import com.dogdduddy.almanac.billing.BillingProduct
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.billing.PromoRedemption
import com.dogdduddy.almanac.billing.RedeemOutcome
import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.data.ResolvedPage
import com.dogdduddy.almanac.demo.DemoControls
import com.dogdduddy.almanac.location.City
import com.dogdduddy.almanac.location.LocationMode
import com.dogdduddy.almanac.location.LocationRepository
import com.dogdduddy.almanac.location.ResolvedLocation
import com.dogdduddy.almanac.weather.DeviceClock
import com.dogdduddy.almanac.weather.LocationKey
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
    /** 오늘 페이지의 섭씨 기온. 과거 기록에는 저장하지 않으므로 null 이다. */
    val temperatureC: Double?,
    val timeOfDay: TimeOfDay,
    val locationLabel: String,
    val dateKey: String,
    /**
     * 지금 이 순간의 페이지인가. 화면은 이때만 날짜를 감춘다("오늘"에는 날짜가 필요 없다).
     *
     * **목록의 첫 줄과 같은 뜻이 아니다.** 오프라인 등으로 오늘 페이지를 못 만들면
     * 첫 줄은 과거 페이지이고, 그걸 오늘로 취급하면 날짜가 사라져 어제 것을 오늘처럼 읽는다.
     */
    val isToday: Boolean = false,
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
    /**
     * 촬영용 조건 고정. 기본값은 아무것도 고정하지 않은 상태라 평소 경로에 영향이 없다.
     * 값을 넣는 창구는 디버그 빌드에서만 만든다.
     */
    private val demo: DemoControls = DemoControls(),
) {

    /**
     * @param refreshLocation 앱에서는 true, **위젯에서는 false**.
     *   위젯은 측위를 기다릴 수 없으므로 저장된 위치만 쓴다.
     * @param countAsRead 앱 화면에서는 true, **위젯에서는 false**.
     *   위젯은 슬롯 고정에는 참여하되(그래야 앱과 같은 문장을 그린다) 소비로는 세지 않는다.
     *   위젯 갱신을 읽음으로 세면 주머니 속에서 하루 3편씩 콘텐츠가 사라진다.
     */
    suspend fun todaysPage(
        language: String = "en",
        refreshLocation: Boolean = false,
        countAsRead: Boolean = false,
    ): PageResult {
        content.ensureBaseEntitlement()

        val place = if (refreshLocation) location.refresh() else location.current()
        val measured = weather.currentConditions(
            latitude = place.coordinates.latitude,
            longitude = place.coordinates.longitude,
        ) ?: return PageResult.Unavailable(PageUnavailable.NO_WEATHER)

        // 촬영용 고정이 걸려 있으면 여기서 갈아 끼운다. 바뀌는 것은 엔진의 **입력**뿐이고
        // 선택 알고리즘은 그대로다 — 그래서 영상에 찍히는 것이 실제 계약이 된다.
        val conditions = demo.override(measured)

        // 진짜 installId 는 항상 만들어 둔다. 고정을 풀면 원래 문장으로 돌아와야 한다.
        val installId = demo.installIdOr(content.installId { newInstallId() })

        val resolved = content.resolvePage(
            dateKey = conditions.dateKey,
            timeOfDay = conditions.timeOfDay,
            weatherGroup = conditions.weatherGroup,
            language = language,
            locationKey = conditions.locationKey,
            windFlag = conditions.windFlag,
            installId = installId,
            countAsRead = countAsRead,
        ) ?: return PageResult.Unavailable(PageUnavailable.NO_CONTENT)

        return PageResult.Ready(
            resolved.toTodaysPage(
                currentYear = currentYear(clock),
                weatherGroup = conditions.weatherGroup,
                temperatureC = conditions.temperatureC,
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
        countAsRead: Boolean = true,
    ): PagesState {
        // pages() 는 앱 화면 전용이다(위젯은 todaysPage 만 쓴다). 그래서 기본이 true 다.
        val today = todaysPage(language, refreshLocation, countAsRead)
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
                temperatureC = null,
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
                PagesState.Ready(
                    listOf(today.page) + withoutToday,
                    place.label,
                    place.mode,
                    hasLockedPacks(),
                )
            }

            is PageResult.Unavailable ->
                if (past.isEmpty()) {
                    PagesState.Empty(today.reason, place.label, place.mode)
                } else {
                    PagesState.Ready(past, place.label, place.mode, hasLockedPacks())
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

    /**
     * [packIds] 가 담고 있는 문장 수. 구매 확인 문구가 쓴다.
     *
     * 세지 못하면 0 을 돌려주고 화면이 숫자 없는 문구로 떨어진다 — 확인을 못 보여주는
     * 것보다는 낫고, 틀린 숫자를 보여주는 것보다는 훨씬 낫다.
     */
    fun entryCount(packIds: Set<String>): Int {
        val counts = runCatching { content.entryCountByPack() }.getOrElse { return 0 }
        return packIds.sumOf { counts[it] ?: 0 }
    }

    /**
     * 아직 열리지 않은 팩이 남았는가.
     *
     * **살 것이 있는가와 다른 질문이다.** 오프라인이거나 상품 심사가 안 끝나면
     * 상품 목록은 비지만 코드로는 열 수 있다. 그 경로까지 감추면 심사위원이
     * 코드를 받고도 넣을 자리를 못 찾는다.
     */
    fun hasLockedPacks(): Boolean = content.lockedPackIds().isNotEmpty()

    /**
     * 프로모션 코드를 받는다. **스토어 코드가 없을 때 쓰는 비상구다.**
     *
     * 결제 파이프라인을 타지 않으므로 RevenueCat 연동을 증명하지 못한다.
     * 심사위원에게 주는 정식 경로는 스토어 코드다 — docs/decisions/promo-code.md 참고.
     */
    fun redeemPromoCode(code: String): RedeemOutcome =
        PromoRedemption(content) { clock.nowEpochSeconds() }.redeem(code)

    // ---- 촬영 준비 -----------------------------------------------------------

    /**
     * **디버그 빌드 전용.** 표시 이력을 지워 두 기기의 출발선을 맞춘다.
     *
     * 시드를 같게 고정해도 히스토리와 읽음 횟수가 다르면 다른 문장이 나온다 —
     * 그 둘도 선택의 입력이기 때문이다(결정론 계약 2.7).
     */
    fun resetHistory() = content.clearHistory()

    /**
     * **디버그 빌드 전용.** 지난 날짜의 페이지를 채워 아카이브를 만든다.
     *
     * 역방향 넘김 컷(#4)이 이걸 필요로 한다. 새로 설치한 기기에는 넘길 과거가 없고,
     * 촬영 메뉴로 시간대만 바꿔 쌓으면 **기록이 전부 오늘 날짜에 몰려** 과거로
     * 되돌아가는 느낌이 죽는다.
     *
     * **앱의 진짜 선택 엔진을 과거 날짜로 돌린다.** 손으로 entry_id 를 박아 넣으면
     * 앱이 고르지 않았을 문장이 화면에 뜬다. 이렇게 하면 나오는 것은
     * "그날 날씨가 이랬다면 이 기기가 보여줬을 바로 그 문장" 이다.
     *
     * **다만 과거 날씨는 지어낸 값이다.** MET Norway 무료 API 는 예보만 주고 과거
     * 관측을 주지 않는다. 날짜와 문장과 그 선택은 진짜이고, 날씨 배열만 합성이다.
     *
     * 오래된 날부터 채운다 — 실제로 쌓인 것과 같은 순서라야 균등 노출 필터가
     * 같은 방식으로 작동한다.
     *
     * @return 실제로 채워진 페이지 수
     */
    fun seedArchive(days: Int = DEMO_ARCHIVE_DAYS, language: String = "en"): Int {
        // 보유 팩이 비면 선택이 즉시 null 이라 한 장도 안 생긴다.
        // 평소에는 todaysPage 가 먼저 지급하지만, 여기가 그 순서에 기대면 안 된다.
        content.ensureBaseEntitlement()

        val place = location.current()
        val locationKey = LocationKey.format(
            place.coordinates.latitude, place.coordinates.longitude
        )
        val installId = content.installId { newInstallId() }
        val now = clock.nowEpochSeconds()

        var made = 0
        for (dayBack in days downTo 1) {
            val dateKey = clock.localDateKey(now - dayBack * SECONDS_PER_DAY)
            for (timeOfDay in TimeOfDay.entries) {
                val seed = fnv1a64("$dateKey|${timeOfDay.key}")
                // 하루 세 칸을 매일 꽉 채우지 않는다. 빈틈이 있어야 사람이 읽은
                // 기록처럼 보이고, 넘길 때 날짜가 자연스럽게 건너뛴다.
                if (seed % 3uL == 0uL) continue

                val resolved = content.resolvePage(
                    dateKey = dateKey,
                    timeOfDay = timeOfDay,
                    weatherGroup = pastWeather(seed),
                    language = language,
                    locationKey = locationKey,
                    windFlag = false,
                    installId = installId,
                    countAsRead = true,
                )
                if (resolved != null) made++
            }
        }
        return made
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
        /** 아직 열리지 않은 팩이 있는가. 서가 진입점의 조건이다. */
        val hasLockedPacks: Boolean = false,
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
    temperatureC: Double?,
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
    temperatureC = temperatureC,
    timeOfDay = timeOfDay,
    locationLabel = locationLabel,
    dateKey = dateKey,
    isToday = true,
)

/** 하루. 과거 날짜를 되짚는 데 쓴다. */
private const val SECONDS_PER_DAY = 86_400L

/** 촬영용 아카이브를 며칠 치 채울지. 3주면 넘기는 컷에 충분하고도 남는다. */
const val DEMO_ARCHIVE_DAYS = 21

/**
 * 촬영용 과거 날씨.
 *
 * **관측값이 아니다.** 흔한 날씨가 자주 나오도록 가중치를 준 목록에서 고른다 —
 * 뇌우와 눈이 사흘에 한 번씩 오는 아카이브는 넘길 때 바로 가짜로 읽힌다.
 */
private fun pastWeather(seed: ULong): WeatherGroup {
    val weighted = listOf(
        WeatherGroup.CLEAR, WeatherGroup.CLEAR, WeatherGroup.CLEAR,
        WeatherGroup.CLOUDY, WeatherGroup.CLOUDY, WeatherGroup.CLOUDY,
        WeatherGroup.RAIN, WeatherGroup.RAIN,
        WeatherGroup.FOG, WeatherGroup.WIND, WeatherGroup.DRIZZLE,
        WeatherGroup.SNOW, WeatherGroup.THUNDER,
    )
    return weighted[((seed / 3uL) % weighted.size.toULong()).toInt()]
}

internal fun currentYear(clock: DeviceClock): Int {
    val key = clock.localDateKey(clock.nowEpochSeconds())
    return key.substringBefore('-').toIntOrNull()
        ?: civilFromDays(clock.nowEpochSeconds() / 86_400L).first
}

/**
 * 설치 ID. UUID 형태의 소문자 문자열.
 *
 * 필요한 건 **설치마다 다르고 이후 불변**인 것이다. 시각으로 만들면 안 된다 —
 * 초 단위 시각을 시드로 쓰면 같은 초에 처음 실행한 설치들이 같은 ID 를 받아
 * 콘텐츠 순서까지 공유한다.
 *
 * 플랫폼 간 같아야 하는 것은 생성 알고리즘이 아니라 **저장 형식**이므로,
 * 난수는 플랫폼에 맡기고 여기서는 소문자로 정규화만 한다.
 */
internal fun newInstallId(): String = randomUuid().lowercase()
