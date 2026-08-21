package com.dogdduddy.almanac.weather

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.db.user.UserDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 캐시 오케스트레이션 검증.
 *
 * **여기가 라이선스가 걸린 지점이다.** 네트워크 호출 여부를 결정하는 유일한 곳이므로,
 * 실제로 몇 번 호출했는지를 세어서 확인한다.
 */
class WeatherRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var user: UserDatabase
    private lateinit var source: FakeWeatherSource
    private lateinit var clock: FixedDeviceClock
    private lateinit var repo: WeatherRepository

    private val lat = 37.5665
    private val lon = 126.9780
    private val start = 1_786_369_300L

    /** 2026-08-10 서울 실제 값. */
    private val sunrise = 1_786_308_180L
    private val sunset = 1_786_357_860L

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserDatabase.Schema.create(driver)
        user = UserDatabase(driver)
        source = FakeWeatherSource(sunrise, sunset) { clock.nowEpochSeconds() }
        clock = FixedDeviceClock(now = start, dateKey = "2026-08-10", hour = 22, offset = "+09:00")
        repo = WeatherRepository(user, source, clock)
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun firstCallFetchesAndCaches() = runTest {
        val conditions = repo.currentConditions(lat, lon)
        assertNotNull(conditions)
        assertEquals(1, source.weatherCalls)
        assertEquals("37.57,126.98", conditions.locationKey)
        assertEquals(WeatherGroup.CLOUDY, conditions.weatherGroup)
        assertEquals(27.7, conditions.temperatureC)
        assertTrue(!conditions.servedFromCache)
    }

    /** 라이선스 핵심: 1시간 안에는 절대 다시 요청하지 않는다. */
    @Test
    fun withinOneHourNeverHitsTheNetworkAgain() = runTest {
        repo.currentConditions(lat, lon)
        assertEquals(1, source.weatherCalls)

        for (minutes in listOf(1, 10, 31, 45, 59)) {
            clock.advance(60L)
            val result = repo.currentConditions(lat, lon)
            assertNotNull(result)
            assertTrue(result.servedFromCache, "${minutes}분 시점에 캐시를 안 썼다")
        }
        assertEquals(1, source.weatherCalls, "1시간 안에 네트워크를 다시 건드렸다")
    }

    /**
     * 서버가 준 Expires 가 32분이어도(실측값) 1시간을 채워야 한다.
     * 이 테스트가 깨지면 곧 차단이다.
     */
    @Test
    fun shortServerExpiresDoesNotShortenOurCache() = runTest {
        source.expiresAfterSeconds = 32 * 60
        repo.currentConditions(lat, lon)

        clock.advance(50 * 60)
        repo.currentConditions(lat, lon)
        assertEquals(1, source.weatherCalls, "Expires 만 보고 50분 만에 다시 요청했다")

        clock.advance(11 * 60)
        repo.currentConditions(lat, lon)
        assertEquals(2, source.weatherCalls, "1시간이 지났는데 갱신하지 않았다")
    }

    @Test
    fun refetchSendsIfModifiedSince() = runTest {
        repo.currentConditions(lat, lon)
        clock.advance(WeatherCachePolicy.MIN_TTL_SECONDS + 1)
        repo.currentConditions(lat, lon)
        assertEquals("Mon, 10 Aug 2026 13:40:45 GMT", source.lastIfModifiedSince)
    }

    @Test
    fun notModifiedKeepsValuesAndRefreshesFreshness() = runTest {
        repo.currentConditions(lat, lon)
        clock.advance(WeatherCachePolicy.MIN_TTL_SECONDS + 1)

        source.nextIsNotModified = true
        val result = repo.currentConditions(lat, lon)
        assertNotNull(result)
        assertEquals(WeatherGroup.CLOUDY, result.weatherGroup)
        assertEquals(27.7, result.temperatureC)
        assertEquals(2, source.weatherCalls)

        // 신선도가 갱신됐으므로 다시 1시간 동안 조용해야 한다.
        clock.advance(30 * 60)
        repo.currentConditions(lat, lon)
        assertEquals(2, source.weatherCalls)
    }

    /** 실패해도 예외가 아니라 캐시로 답해야 한다. 위젯이 죽으면 안 된다. */
    @Test
    fun failureFallsBackToCache() = runTest {
        repo.currentConditions(lat, lon)
        clock.advance(WeatherCachePolicy.MIN_TTL_SECONDS + 1)

        source.failNext = true
        val result = repo.currentConditions(lat, lon)
        assertNotNull(result)
        assertEquals(WeatherGroup.CLOUDY, result.weatherGroup)
        assertEquals(27.7, result.temperatureC)
        assertTrue(result.servedFromCache)
    }

    @Test
    fun failureWithNoCacheYieldsNull() = runTest {
        source.failNext = true
        assertNull(repo.currentConditions(lat, lon))
    }

    /** 서버가 죽었을 때 계속 두드리면 안 된다. */
    @Test
    fun failureBacksOffBeforeRetrying() = runTest {
        repo.currentConditions(lat, lon)
        clock.advance(WeatherCachePolicy.MIN_TTL_SECONDS + 1)
        source.failNext = true
        repo.currentConditions(lat, lon)
        assertEquals(2, source.weatherCalls)

        // 백오프(15분) 안에는 재시도하지 않는다.
        clock.advance(5 * 60)
        repo.currentConditions(lat, lon)
        assertEquals(2, source.weatherCalls, "백오프 중에 재시도했다")

        // 백오프가 지나면 재시도한다.
        clock.advance(11 * 60)
        repo.currentConditions(lat, lon)
        assertEquals(3, source.weatherCalls, "백오프가 끝났는데 재시도하지 않았다")
    }

    /** 실패 재시도는 정상 TTL(1시간)보다 빨라야 한다. */
    @Test
    fun failureRecoversFasterThanNormalTtl() = runTest {
        repo.currentConditions(lat, lon)
        clock.advance(WeatherCachePolicy.MIN_TTL_SECONDS + 1)
        source.failNext = true
        repo.currentConditions(lat, lon)

        clock.advance(WeatherCachePolicy.FAILURE_BACKOFF_SECONDS + 1)
        repo.currentConditions(lat, lon)
        assertEquals(3, source.weatherCalls)
    }

    // ---- 일출·일몰 -----------------------------------------------------------

    @Test
    fun sunTimesAreFetchedOncePerDay() = runTest {
        repo.currentConditions(lat, lon)
        assertEquals(1, source.sunCalls)

        clock.advance(WeatherCachePolicy.MIN_TTL_SECONDS + 1)
        repo.currentConditions(lat, lon)
        assertEquals(1, source.sunCalls, "같은 날인데 일출을 다시 물었다")
    }

    @Test
    fun timeOfDayComesFromSunNotClock() = runTest {
        // now(1786369300) 는 일몰(1786357860) 이후 → 저녁·밤
        assertEquals(TimeOfDay.EVENING_NIGHT, repo.currentConditions(lat, lon)?.timeOfDay)

        // 일출 직후로 옮기면 아침이어야 한다.
        val morning = FixedDeviceClock(sunrise + 600, "2026-08-10", hour = 5, offset = "+09:00")
        val morningRepo = WeatherRepository(user, FakeWeatherSource(sunrise, sunset) { morning.nowEpochSeconds() }, morning)
        assertEquals(TimeOfDay.MORNING, morningRepo.currentConditions(lat, lon)?.timeOfDay)
    }

    /** 극야·극주: 일출·일몰이 없으면 시계 기준으로 폴백해야 한다. */
    @Test
    fun polarDayFallsBackToClockHour() = runTest {
        val noonClock = FixedDeviceClock(start, "2026-08-10", hour = 13, offset = "+00:00")
        val polarSource = FakeWeatherSource(sunrise = null, sunset = null) { noonClock.nowEpochSeconds() }
        val polarRepo = WeatherRepository(user, polarSource, noonClock)
        assertEquals(TimeOfDay.DAY, polarRepo.currentConditions(lat, lon)?.timeOfDay)
    }

    /** 미세하게 다른 좌표는 같은 캐시 키로 접혀야 한다 — 아니면 캐시가 무의미해진다. */
    @Test
    fun nearbyCoordinatesShareTheCache() = runTest {
        repo.currentConditions(37.56651, 126.97801)
        repo.currentConditions(37.56649, 126.97799)
        assertEquals(1, source.weatherCalls)
    }
}

private class FakeWeatherSource(
    private val sunrise: Long?,
    private val sunset: Long?,
    /** 응답의 fetched_at 은 실제 서버처럼 "지금" 이어야 한다. 0 으로 두면 캐시가 늘 만료로 보인다. */
    private val now: () -> Long = { 0L },
) : WeatherSource {
    var weatherCalls = 0
    var sunCalls = 0
    var failNext = false
    var nextIsNotModified = false
    var expiresAfterSeconds = 32 * 60L
    var lastIfModifiedSince: String? = null

    override suspend fun fetchWeather(
        latitude: Double,
        longitude: Double,
        lastModified: String?,
    ): WeatherFetch {
        weatherCalls++
        lastIfModifiedSince = lastModified
        if (failNext) {
            failNext = false
            return WeatherFetch.Failed("테스트 실패")
        }
        val meta = FetchMeta(
            fetchedAtEpochSeconds = now(),
            expiresAtEpochSeconds = now() + expiresAfterSeconds,
            lastModified = "Mon, 10 Aug 2026 13:40:45 GMT",
        )
        if (nextIsNotModified) {
            nextIsNotModified = false
            return WeatherFetch.NotModified(meta)
        }
        return WeatherFetch.Updated(
            // partlycloudy → WMO 2 → CLOUDY, 풍속 4.1 m/s (강풍 아님)
            snapshot = WeatherSnapshot(2, 4.1, 27.7, now()),
            meta = meta,
        )
    }

    override suspend fun fetchSunTimes(
        latitude: Double,
        longitude: Double,
        date: String,
        utcOffset: String,
    ): SunTimes {
        sunCalls++
        return SunTimes(sunrise, sunset)
    }
}
