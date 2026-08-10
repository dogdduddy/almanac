package com.dogdduddy.almanac.weather

import com.dogdduddy.almanac.core.WeatherGroup
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 픽스처는 2026-08-10 에 api.met.no 에서 실제로 받은 응답이다.
 * 손으로 만든 JSON 이 아니라 실물이므로, MET 이 스키마를 바꾸면 이 테스트가 먼저 깨진다.
 */
class MetNorwayClientTest {

    private val forecastJson = resource("met-forecast-seoul.json")
    private val sunriseJson = resource("met-sunrise-seoul.json")

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private var captured: HttpRequestData? = null

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = forecastJson,
        headers: io.ktor.http.Headers = headersOf(
            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
            HttpHeaders.Expires to listOf("Mon, 10 Aug 2026 14:12:00 GMT"),
            HttpHeaders.LastModified to listOf("Mon, 10 Aug 2026 13:40:45 GMT"),
        ),
    ): MetNorwayClient {
        val engine = MockEngine { request ->
            captured = request
            if (status == HttpStatusCode.NotModified) {
                respond("", status, headers)
            } else {
                respond(body, status, headers)
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return MetNorwayClient(http, nowEpochSeconds = { NOW })
    }

    @Test
    fun parsesRealForecastResponse() = runTest {
        val result = client().fetchWeather(37.5665, 126.9780)
        assertTrue(result is WeatherFetch.Updated, "실패: $result")

        // 픽스처의 symbol_code 는 partlycloudy_night, wind_speed 는 4.1 m/s 다.
        assertEquals(WeatherGroup.CLOUDY, result.snapshot.weatherGroup)
        assertEquals(4.1, result.snapshot.windSpeedMs)
        assertEquals(27.7, result.snapshot.temperatureC)
        assertEquals(1786366800L, result.snapshot.observedAtEpochSeconds)
        assertTrue(!result.snapshot.windFlag, "4.1 m/s 는 강풍이 아니다")
    }

    /** Expires / Last-Modified 를 캐시 정책이 쓸 수 있는 형태로 뽑아내야 한다. */
    @Test
    fun extractsCacheHeaders() = runTest {
        val result = client().fetchWeather(37.5665, 126.9780)
        val meta = (result as WeatherFetch.Updated).meta
        assertEquals(1786371120L, meta.expiresAtEpochSeconds)
        assertEquals("Mon, 10 Aug 2026 13:40:45 GMT", meta.lastModified)
        assertEquals(NOW, meta.fetchedAtEpochSeconds)
    }

    /**
     * 실측된 Expires 는 받은 시각 기준 약 32분 뒤다.
     * 캐시 정책이 이를 1시간으로 끌어올려야 라이선스를 지킨다.
     */
    @Test
    fun realExpiresIsShorterThanOneHourAndPolicyRaisesIt() = runTest {
        val meta = (client().fetchWeather(37.5665, 126.9780) as WeatherFetch.Updated).meta
        val expires = assertNotNull(meta.expiresAtEpochSeconds)
        assertTrue(
            expires - meta.fetchedAtEpochSeconds < WeatherCachePolicy.MIN_TTL_SECONDS,
            "픽스처 전제가 깨졌다 — Expires 가 1시간보다 길다",
        )
        assertEquals(
            meta.fetchedAtEpochSeconds + WeatherCachePolicy.MIN_TTL_SECONDS,
            WeatherCachePolicy.nextAllowedRequestAt(meta.fetchedAtEpochSeconds, expires),
        )
    }

    /** MET 은 고유 User-Agent 를 요구한다. 없거나 일반적이면 403 이다. */
    @Test
    fun sendsIdentifyingUserAgentWithContact() = runTest {
        client().fetchWeather(37.5665, 126.9780)
        val ua = captured?.headers?.get(HttpHeaders.UserAgent)
        assertNotNull(ua)
        assertTrue(ua.contains("Almanac"), "앱을 식별할 수 없다: $ua")
        assertTrue(ua.contains("@"), "연락처가 없다: $ua")
    }

    /** MET 은 좌표를 소수점 4자리 이하로 잘라 보낼 것을 요구한다. */
    @Test
    fun roundsCoordinatesBeforeSending() = runTest {
        client().fetchWeather(37.56652871, 126.97803123)
        val url = captured?.url.toString()
        assertTrue("lat=37.5665" in url, url)
        assertTrue("lon=126.978" in url, url)
    }

    @Test
    fun sendsIfModifiedSinceWhenKnown() = runTest {
        client().fetchWeather(37.5665, 126.9780, lastModified = "Mon, 10 Aug 2026 13:40:45 GMT")
        assertEquals(
            "Mon, 10 Aug 2026 13:40:45 GMT",
            captured?.headers?.get(HttpHeaders.IfModifiedSince),
        )
    }

    @Test
    fun notModifiedRefreshesFreshnessWithoutBody() = runTest {
        val result = client(status = HttpStatusCode.NotModified)
            .fetchWeather(37.5665, 126.9780, lastModified = "Mon, 10 Aug 2026 13:40:45 GMT")
        assertTrue(result is WeatherFetch.NotModified, "실패: $result")
        assertEquals(NOW, result.meta.fetchedAtEpochSeconds)
    }

    /** 실패는 예외로 새어나가면 안 된다 — 위젯이 죽는다. 캐시를 계속 쓰도록 값으로 돌려준다. */
    @Test
    fun serverErrorBecomesFailedResult() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val result = MetNorwayClient(http, nowEpochSeconds = { NOW })
            .fetchWeather(37.5665, 126.9780)
        assertTrue(result is WeatherFetch.Failed, "실패로 처리되지 않았다: $result")
    }

    @Test
    fun parsesRealSunriseResponse() = runTest {
        val sun = client(body = sunriseJson).fetchSunTimes(37.5665, 126.9780, "2026-08-10", "+09:00")
        assertNotNull(sun)
        assertEquals(1786308180L, sun.sunriseEpochSeconds)   // 2026-08-10T05:43+09:00
        assertEquals(1786357860L, sun.sunsetEpochSeconds)    // 2026-08-10T19:31+09:00
    }

    /** 극야·극주에서는 sunrise/sunset 이 없다. null 로 내려야 시계 폴백이 돈다. */
    @Test
    fun missingSunEventsBecomeNull() = runTest {
        val body = """{"properties":{"solarnoon":{"time":"2026-08-10T12:37+09:00"}}}"""
        val sun = client(body = body).fetchSunTimes(37.5665, 126.9780, "2026-08-10", "+09:00")
        assertNotNull(sun)
        assertNull(sun.sunriseEpochSeconds)
        assertNull(sun.sunsetEpochSeconds)
    }

    private companion object {
        const val NOW = 1_786_369_300L
    }
}
