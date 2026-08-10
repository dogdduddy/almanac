package com.dogdduddy.almanac.weather

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MET Norway 클라이언트.
 *
 * 라이선스 의무가 두 가지 붙는다. 둘 다 지키지 않으면 차단당한다.
 * 1. **고유 User-Agent + 연락처.** 일반적인 UA 는 403 이다
 * 2. **최소 1시간 캐싱.** 정책은 [WeatherCachePolicy] 가 갖고 있고,
 *    이 클래스는 캐시가 만료됐을 때만 호출된다는 전제로 동작한다
 *
 * 좌표는 [LocationKey] 로 소수점 4자리로 잘라 보낸다 (MET 요구사항).
 */
class MetNorwayClient(
    private val httpClient: HttpClient,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val nowEpochSeconds: () -> Long,
) {

    /**
     * 현재 시각 기준 날씨. 캐시가 만료됐을 때만 부를 것.
     *
     * @param lastModified 직전 응답의 `Last-Modified`. 있으면 `If-Modified-Since` 로 보낸다
     */
    suspend fun fetchWeather(
        latitude: Double,
        longitude: Double,
        lastModified: String? = null,
    ): WeatherFetch = runCatchingFetch {
        val response = httpClient.get("$BASE_URL/locationforecast/2.0/compact") {
            header(HttpHeaders.UserAgent, userAgent)
            lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            parameter("lat", LocationKey.round(latitude))
            parameter("lon", LocationKey.round(longitude))
        }

        val meta = response.toMeta()
        if (response.status == HttpStatusCode.NotModified) return@runCatchingFetch WeatherFetch.NotModified(meta)
        if (!response.status.isSuccess()) {
            return@runCatchingFetch WeatherFetch.Failed("HTTP ${response.status.value}")
        }

        val payload: ForecastResponse = response.body()
        val entry = payload.properties.timeseries.firstOrNull()
            ?: return@runCatchingFetch WeatherFetch.Failed("timeseries 가 비어 있다")

        val instant = entry.data.instant.details
        val symbol = entry.data.next1Hours?.summary?.symbolCode
            ?: entry.data.next6Hours?.summary?.symbolCode
            ?: return@runCatchingFetch WeatherFetch.Failed("symbol_code 가 없다")

        val wmo = MetSymbol.toWmoCode(symbol)
            ?: return@runCatchingFetch WeatherFetch.Failed("모르는 symbol_code: $symbol")

        WeatherFetch.Updated(
            snapshot = WeatherSnapshot(
                wmoCode = wmo,
                windSpeedMs = instant.windSpeed ?: 0.0,
                temperatureC = instant.airTemperature,
                observedAtEpochSeconds = parseIsoToEpochSeconds(entry.time) ?: nowEpochSeconds(),
            ),
            meta = meta,
        )
    }

    /**
     * 그날의 일출·일몰.
     *
     * 시간대 경계를 시계가 아니라 해로 잡기 때문에 필요하다.
     * 극야·극주에서는 sunrise/sunset 이 없을 수 있고, 그때는 null 로 돌려준다 —
     * 호출자는 시계 기준 폴백을 쓴다.
     *
     * @param date 로컬 달력 날짜 `yyyy-MM-dd`
     * @param utcOffset `+09:00` 형식
     */
    suspend fun fetchSunTimes(
        latitude: Double,
        longitude: Double,
        date: String,
        utcOffset: String,
    ): SunTimes? = runCatching {
        val response = httpClient.get("$BASE_URL/sunrise/3.0/sun") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("lat", LocationKey.round(latitude))
            parameter("lon", LocationKey.round(longitude))
            parameter("date", date)
            parameter("offset", utcOffset)
        }
        if (!response.status.isSuccess()) return null

        val payload: SunResponse = response.body()
        SunTimes(
            sunriseEpochSeconds = payload.properties.sunrise?.time?.let(::parseIsoToEpochSeconds),
            sunsetEpochSeconds = payload.properties.sunset?.time?.let(::parseIsoToEpochSeconds),
        )
    }.getOrNull()

    private fun HttpResponse.toMeta() = FetchMeta(
        fetchedAtEpochSeconds = nowEpochSeconds(),
        expiresAtEpochSeconds = headers[HttpHeaders.Expires]?.let(::parseHttpDateToEpochSeconds),
        lastModified = headers[HttpHeaders.LastModified],
    )

    private inline fun runCatchingFetch(block: () -> WeatherFetch): WeatherFetch =
        try {
            block()
        } catch (e: Throwable) {
            WeatherFetch.Failed(e.message ?: e::class.simpleName ?: "unknown", e)
        }

    companion object {
        const val BASE_URL = "https://api.met.no/weatherapi"

        /**
         * MET Norway 는 앱을 식별할 수 있고 연락 가능한 User-Agent 를 요구한다.
         * 일반적인 값이면 403 으로 막힌다.
         */
        const val DEFAULT_USER_AGENT = "Almanac/0.1 (github.com/dogdduddy; dogdduddy@gmail.com)"

        /** 앱 내 출처 표기 화면에 반드시 넣어야 하는 문구. */
        const val ATTRIBUTION = "Weather data from MET Norway (api.met.no), licensed under CC BY 4.0"
    }
}

private fun HttpStatusCode.isSuccess() = value in 200..299

// --- 응답 스키마. 실제 응답을 조회해 확인한 필드만 담는다 -------------------------

@Serializable
private data class ForecastResponse(val properties: ForecastProperties)

@Serializable
private data class ForecastProperties(val timeseries: List<TimeseriesEntry>)

@Serializable
private data class TimeseriesEntry(val time: String, val data: TimeseriesData)

@Serializable
private data class TimeseriesData(
    val instant: InstantBlock,
    @SerialName("next_1_hours") val next1Hours: PeriodBlock? = null,
    @SerialName("next_6_hours") val next6Hours: PeriodBlock? = null,
)

@Serializable
private data class InstantBlock(val details: InstantDetails)

@Serializable
private data class InstantDetails(
    @SerialName("air_temperature") val airTemperature: Double? = null,
    /** 단위는 m/s — WindPolicy.STRONG_WIND_MS 와 같은 단위다. */
    @SerialName("wind_speed") val windSpeed: Double? = null,
)

@Serializable
private data class PeriodBlock(val summary: PeriodSummary)

@Serializable
private data class PeriodSummary(@SerialName("symbol_code") val symbolCode: String)

@Serializable
private data class SunResponse(val properties: SunProperties)

@Serializable
private data class SunProperties(val sunrise: SunEvent? = null, val sunset: SunEvent? = null)

@Serializable
private data class SunEvent(val time: String? = null)
