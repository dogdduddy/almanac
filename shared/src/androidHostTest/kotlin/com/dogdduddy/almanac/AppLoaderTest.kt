package com.dogdduddy.almanac

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.dogdduddy.almanac.billing.Billing
import com.dogdduddy.almanac.billing.BillingProduct
import com.dogdduddy.almanac.billing.EntitlementSync
import com.dogdduddy.almanac.billing.PurchaseOutcome
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase
import com.dogdduddy.almanac.location.Coordinates
import com.dogdduddy.almanac.location.LocationRepository
import com.dogdduddy.almanac.location.LocationSource
import com.dogdduddy.almanac.weather.FetchMeta
import com.dogdduddy.almanac.weather.FixedDeviceClock
import com.dogdduddy.almanac.weather.SunTimes
import com.dogdduddy.almanac.weather.WeatherFetch
import com.dogdduddy.almanac.weather.WeatherRepository
import com.dogdduddy.almanac.weather.WeatherSnapshot
import com.dogdduddy.almanac.weather.WeatherSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 시작 절차 검증.
 *
 * **여기서 지키는 것은 하나다 — 첫 프레임은 아무것도 기다리지 않는다.**
 *
 * 0.3.0 빌드 4 는 반대로 동작했다. 측위와 스토어 조회가 전부 끝나야 상태가 들어갔고,
 * 그 전까지 화면은 [AppState.Loading] 이었다. App Review 는 그 빈 화면을 보고
 * 지침 2.1(a) 로 반려했다. 그래서 "느린 의존성을 매달아 두고도 화면이 나오는가" 를
 * 실제로 매달아 두고 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLoaderTest {

    private lateinit var contentDriver: JdbcSqliteDriver
    private lateinit var userDriver: JdbcSqliteDriver
    private lateinit var service: AlmanacService
    private lateinit var repository: AlmanacRepository
    private lateinit var entitlements: EntitlementSync

    private lateinit var location: BlockingLocationSource
    private lateinit var billing: Billing
    private lateinit var weather: StubWeatherSource

    private val states = mutableListOf<AppState>()
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        contentDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ContentDatabase.Schema.create(contentDriver)
        val content = ContentDatabase(contentDriver)

        userDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserDatabase.Schema.create(userDriver)
        val user = UserDatabase(userDriver)

        seedContent()

        val clock = FixedDeviceClock(now = NOW, dateKey = "2026-08-28", hour = 10, offset = "+09:00")
        repository = AlmanacRepository(content, user) { clock.nowEpochSeconds() }

        location = BlockingLocationSource()
        billing = BlockingBilling()
        weather = StubWeatherSource()
        entitlements = EntitlementSync(repository, billing)

        service = AlmanacService(
            content = repository,
            weather = WeatherRepository(user = user, source = weather, clock = clock),
            location = LocationRepository(user, location) { clock.nowEpochSeconds() },
            clock = clock,
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        contentDriver.close()
        userDriver.close()
    }

    /**
     * 측위도 스토어도 응답하지 않는 상태에서 시작해도 화면이 나온다.
     *
     * 반려 사유를 그대로 재현한 조건이다 — 둘 다 매달아 두고 [AppState.Loading] 을
     * 벗어나는지 본다. 벗어나지 못하면 그게 심사에서 본 빈 흰 화면이다.
     */
    @Test
    fun `첫 화면은 측위도 스토어도 기다리지 않는다`() = runTest {
        loader().start()
        advanceUntilIdle()

        // 측위는 여전히 매달려 있다. 그런데도 화면은 있다.
        assertTrue(location.awaited, "측위를 부르지도 않았다면 이 테스트는 아무것도 증명하지 못한다")
        assertTrue(location.pending, "측위가 이미 끝났다면 매달아 둔 의미가 없다")
        val first = states.first()
        assertIs<AppState.Ready>(first)
        assertEquals("Seoul", first.locationLabel, "저장된 위치가 없으면 기본 도시로 그린다")
    }

    /** 측위가 끝나면 그 위치로 다시 그린다. 첫 화면은 버려지는 것이 아니라 갱신된다. */
    @Test
    fun `측위가 끝나면 그 위치로 다시 그린다`() = runTest {
        loader().start()
        advanceUntilIdle()

        location.complete(Coordinates(51.5074, -0.1278))
        advanceUntilIdle()

        val last = states.last()
        assertIs<AppState.Ready>(last)
        assertEquals("London", last.locationLabel)
        assertTrue(states.size >= 2, "첫 화면과 갱신은 서로 다른 두 번의 그리기여야 한다")
    }

    /**
     * 페이지를 만드는 길이 **예외로** 끊겨도 Loading 에는 갇히지 않는다.
     *
     * 예전에는 이 경우 상태를 넣는 줄에 닿지 못했다. 코루틴이 그 자리에서 죽고
     * 화면은 영원히 비었다 — 무엇이 잘못됐는지 유저에게 말할 방법도 없었다.
     */
    @Test
    fun `페이지 조회가 던져도 안내 화면은 세운다`() = runTest {
        weather.throwing = true
        location.complete(Coordinates(37.5665, 126.9780))

        loader().start()
        advanceUntilIdle()

        val last = states.last()
        assertIs<AppState.Empty>(last)
        assertEquals(PageUnavailable.NO_WEATHER, last.reason)
    }

    /** 스토어가 끝내 응답하지 않아도 화면은 멀쩡하고, 잃는 것은 업그레이드 진입점뿐이다. */
    @Test
    fun `스토어가 응답하지 않아도 화면은 남고 상품만 빈다`() = runTest {
        loader().start()
        location.complete(Coordinates(37.5665, 126.9780))
        advanceUntilIdle()

        val last = states.last()
        assertIs<AppState.Ready>(last)
        assertTrue(last.products.isEmpty(), "못 받은 상품은 없는 것으로 둔다")
        assertTrue(last.pages.isNotEmpty())
    }

    /**
     * 상한을 넘겨 도착한 응답은 버린다.
     *
     * 앞 테스트만으로는 상한이 걸려 있는지 알 수 없다 — 영영 안 오는 응답은 상한이
     * 없어도 상품을 비워두기 때문이다. 여기서는 **오기는 오되 늦게** 오게 해서,
     * 우리가 실제로 기다리기를 그만두는지 본다.
     */
    @Test
    fun `상한을 넘겨 도착한 상품은 화면에 붙지 않는다`() = runTest {
        billing = SlowBilling(AppLoaderDelays.OVER_LIMIT_MS)
        entitlements = EntitlementSync(repository, billing)

        loader().start()
        location.complete(Coordinates(37.5665, 126.9780))
        advanceUntilIdle()

        val last = states.last()
        assertIs<AppState.Ready>(last)
        assertTrue(last.products.isEmpty(), "늦게 온 응답을 뒤늦게 붙이면 화면이 흔들린다")
    }

    /** 제때 오면 상품이 화면에 붙는다. 페이월 진입점이 이 값으로 열린다. */
    @Test
    fun `제때 온 상품은 화면에 붙는다`() = runTest {
        billing = SlowBilling(AppLoaderDelays.WITHIN_LIMIT_MS)
        entitlements = EntitlementSync(repository, billing)

        loader().start()
        location.complete(Coordinates(37.5665, 126.9780))
        advanceUntilIdle()

        val last = states.last()
        assertIs<AppState.Ready>(last)
        assertEquals(listOf("core-2026"), last.products.map { it.packId })
    }

    /**
     * 로더에게 **테스트 본문의 자식이 아닌** 스코프를 준다.
     *
     * 응답하지 않는 스토어 호출은 끝나지 않는 것이 정상이다 — 운영 코드도 상한만 걸고
     * 두고 나간다. 그런 코루틴을 본문 스코프에 매달면 runTest 가 그것을 기다리다
     * 실패하고, 검증하려던 동작이 그대로 오탐이 된다.
     *
     * `backgroundScope` 도 답이 아니다. 거기 올린 일은 `advanceUntilIdle` 이 돌려주지
     * 않아서 로더가 아예 시작조차 하지 않는다.
     */
    private fun TestScope.loader(): AppLoader {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scopes += scope
        return AppLoader(
            scope = scope,
            service = { service },
            entitlements = { entitlements },
            billing = { billing },
            onState = { states += it },
        )
    }

    private fun seedContent() {
        contentDriver.execute(
            null,
            "INSERT INTO packs (id, title, subtitle, is_base, auto_grant, sort_order) " +
                "VALUES ('base-2026', 'The Almanac', NULL, 1, 1, 0)",
            0,
        )
        var id = 1L
        for (group in WeatherGroup.entries) {
            for (timeOfDay in TimeOfDay.entries) {
                insertEntry(id++, group, timeOfDay)
            }
        }
    }

    private fun insertEntry(id: Long, group: WeatherGroup, timeOfDay: TimeOfDay) {
        contentDriver.execute(
            null,
            """
            INSERT INTO entries (
                id, pack_id, text, author, title, section, year, source_id, language,
                time_of_day, tier, season_weight, temp_weight, word_count, license_note
            ) VALUES (?, 'base-2026', ?, 'PLACEHOLDER', 'PLACEHOLDER', NULL, 1847,
                      'gutenberg-0', 'en', ?, 'A', NULL, NULL, 50, 'public domain')
            """.trimIndent(),
            3,
        ) {
            bindLong(0, id); bindString(1, "placeholder entry $id"); bindString(2, timeOfDay.key)
        }
        contentDriver.execute(
            null,
            "INSERT INTO entry_weather_groups (entry_id, weather_group) VALUES (?, ?)",
            2,
        ) {
            bindLong(0, id); bindString(1, group.key)
        }
    }

    private companion object {
        const val NOW = 1_787_000_000L
    }
}

/** 테스트가 풀어주기 전까지 측위가 끝나지 않는다. 권한 프롬프트가 떠 있는 상황이다. */
private class BlockingLocationSource : LocationSource {
    private val fix = CompletableDeferred<Coordinates?>()

    /** 아직 좌표를 못 받은 상태인가. */
    val pending: Boolean get() = !fix.isCompleted

    /** 로더가 측위를 부르기는 했는가. 안 불렀다면 "안 기다렸다" 는 증명이 성립하지 않는다. */
    var awaited = false
        private set

    override fun hasPermission() = false

    override suspend fun currentCoordinates(): Coordinates? {
        awaited = true
        return fix.await()
    }

    fun complete(coordinates: Coordinates?) {
        fix.complete(coordinates)
    }
}

/** 어떤 호출도 돌아오지 않는 결제 백엔드. 스토어가 화면을 막지 않는지 보기 위한 것이다. */
private class BlockingBilling : Billing {
    private val never = CompletableDeferred<Nothing>()

    override suspend fun products(): List<BillingProduct> = never.await()
    override suspend fun entitledPackIds(): Set<String> = never.await()
    override suspend fun purchase(product: BillingProduct): PurchaseOutcome = never.await()
    override suspend fun restore(): Set<String> = never.await()
}

/** 늦게 답하는 결제 백엔드. 상한이 실제로 걸려 있는지 재는 데 쓴다. */
private class SlowBilling(private val delayMs: Long) : Billing {

    override suspend fun products(): List<BillingProduct> {
        delay(delayMs)
        return listOf(
            BillingProduct(
                packId = "core-2026",
                productId = "com.dogdduddy.almanac.core2026",
                title = "The 2026 Collection",
                description = "테스트",
                displayPrice = "₩5,900",
            )
        )
    }

    override suspend fun entitledPackIds(): Set<String> {
        delay(delayMs)
        return emptySet()
    }

    override suspend fun purchase(product: BillingProduct): PurchaseOutcome =
        PurchaseOutcome.Cancelled

    override suspend fun restore(): Set<String> = emptySet()
}

/**
 * 상한 앞뒤의 지연.
 *
 * AppLoader 의 상한(10초)을 그대로 쓰지 않고 앞뒤 값만 둔다 — 테스트가 검증하는 것은
 * "몇 초인가" 가 아니라 "상한을 넘기면 버리는가" 이므로, 상수를 따라다닐 이유가 없다.
 */
private object AppLoaderDelays {
    const val WITHIN_LIMIT_MS = 1_000L
    const val OVER_LIMIT_MS = 60_000L
}

/**
 * 항상 같은 맑은 하늘.
 *
 * [throwing] 을 켜면 **예외를 던진다.** 실패를 [WeatherFetch.Failed] 로 돌려주는 경로는
 * WeatherRepositoryTest 가 이미 덮고 있고, 여기서 보고 싶은 것은 그 아래 —
 * 아무도 예상하지 못한 예외가 시작 절차를 뚫고 올라올 때 화면이 어떻게 되는가다.
 */
private class StubWeatherSource : WeatherSource {
    var throwing = false

    override suspend fun fetchWeather(
        latitude: Double,
        longitude: Double,
        lastModified: String?,
    ): WeatherFetch =
        if (throwing) {
            throw IllegalStateException("테스트: 날씨 조회가 통째로 터졌다")
        } else {
            WeatherFetch.Updated(
                snapshot = WeatherSnapshot(
                    wmoCode = 0,
                    windSpeedMs = 1.0,
                    temperatureC = 21.0,
                    observedAtEpochSeconds = 1_787_000_000L,
                ),
                meta = FetchMeta(1_787_000_000L, null, null),
            )
        }

    override suspend fun fetchSunTimes(
        latitude: Double,
        longitude: Double,
        date: String,
        utcOffset: String,
    ): SunTimes? = SunTimes(1_786_960_000L, 1_787_010_000L)
}
