package com.dogdduddy.almanac.weather

import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.core.resolveTimeOfDay
import com.dogdduddy.almanac.db.user.UserDatabase

/**
 * 문장 하나를 고르는 데 필요한 모든 조건. 여기까지 나오면 PageSelector 로 넘어갈 수 있다.
 */
data class Conditions(
    val locationKey: String,
    val dateKey: String,
    val timeOfDay: TimeOfDay,
    val weatherGroup: WeatherGroup,
    /** 현재 화면에 보여줄 섭씨 기온. 제공처가 값을 주지 않으면 null 이다. */
    val temperatureC: Double?,
    val windFlag: Boolean,
    /** 캐시만 쓰고 네트워크를 건드리지 않았는지. 진단·테스트용. */
    val servedFromCache: Boolean,
)

/**
 * 날씨 조회 + 캐시 오케스트레이션.
 *
 * **네트워크 호출 여부를 결정하는 유일한 지점이다.** 화면·위젯은 여기만 부르고
 * 스스로 API 를 때리지 않는다. 그래야 1시간 규칙이 실제로 1시간으로 유지된다.
 *
 * 실패해도 예외를 던지지 않는다 — 캐시가 있으면 캐시로 답하고, 없으면 null 이다.
 * 위젯은 예외로 죽으면 안 된다.
 */
class WeatherRepository(
    private val user: UserDatabase,
    private val source: WeatherSource,
    private val clock: DeviceClock,
) {
    private val queries get() = user.userQueries

    suspend fun currentConditions(latitude: Double, longitude: Double): Conditions? {
        val now = clock.nowEpochSeconds()
        val locationKey = LocationKey.format(latitude, longitude)
        val dateKey = clock.localDateKey(now)

        val snapshot = resolveWeather(latitude, longitude, locationKey, now)
            ?: return null
        val sun = resolveSun(latitude, longitude, locationKey, dateKey, now)

        val group = snapshot.first.weatherGroup ?: return null
        return Conditions(
            locationKey = locationKey,
            dateKey = dateKey,
            timeOfDay = resolveTimeOfDay(
                nowEpochSeconds = now,
                sunriseEpochSeconds = sun.sunriseEpochSeconds,
                sunsetEpochSeconds = sun.sunsetEpochSeconds,
                localHour = clock.localHour(now),
            ),
            weatherGroup = group,
            temperatureC = snapshot.first.temperatureC,
            windFlag = snapshot.first.windFlag,
            servedFromCache = snapshot.second,
        )
    }

    /** @return (스냅샷, 캐시로만 처리했는지) */
    private suspend fun resolveWeather(
        latitude: Double,
        longitude: Double,
        locationKey: String,
        now: Long,
    ): Pair<WeatherSnapshot, Boolean>? {
        val cached = queries.weatherFor(locationKey).executeAsOneOrNull()

        if (cached != null && !shouldRequest(cached, now)) {
            return cached.toSnapshot() to true
        }

        return when (val result = source.fetchWeather(latitude, longitude, cached?.last_modified)) {
            is WeatherFetch.Updated -> {
                queries.upsertWeather(
                    location_key = locationKey,
                    wmo_code = result.snapshot.wmoCode.toLong(),
                    wind_speed_ms = result.snapshot.windSpeedMs,
                    temperature_c = result.snapshot.temperatureC,
                    observed_at = result.snapshot.observedAtEpochSeconds,
                    fetched_at = result.meta.fetchedAtEpochSeconds,
                    expires_at = result.meta.expiresAtEpochSeconds,
                    last_modified = result.meta.lastModified,
                )
                result.snapshot to false
            }

            is WeatherFetch.NotModified -> {
                // 본문이 없다. 캐시가 없는데 304 가 오면 쓸 값이 없다.
                cached ?: return null
                queries.touchWeatherFreshness(
                    fetchedAt = result.meta.fetchedAtEpochSeconds,
                    expiresAt = result.meta.expiresAtEpochSeconds,
                    lastModified = result.meta.lastModified ?: cached.last_modified,
                    locationKey = locationKey,
                )
                cached.toSnapshot() to false
            }

            is WeatherFetch.Failed -> {
                if (cached != null) {
                    queries.recordWeatherFailure(failedAt = now, locationKey = locationKey)
                    cached.toSnapshot() to true
                } else {
                    null
                }
            }
        }
    }

    /**
     * 캐시가 만료됐고 백오프 중도 아닐 때만 참.
     *
     * 실패 백오프를 따로 두는 이유: 실패를 1시간 캐시로 취급하면 장애 복구가 너무 늦고,
     * 반대로 백오프가 없으면 서버가 죽었을 때 앱이 계속 두드린다.
     */
    private fun shouldRequest(
        cached: com.dogdduddy.almanac.db.user.Weather_cache,
        now: Long,
    ): Boolean {
        if (WeatherCachePolicy.isFresh(cached.fetched_at, cached.expires_at, now)) return false

        val failedAt = cached.failed_at
        if (cached.failure_count > 0 && failedAt != null) {
            val retryAt = WeatherCachePolicy.retryAfterFailureAt(failedAt, cached.failure_count.toInt())
            if (now < retryAt) return false
        }
        return true
    }

    private suspend fun resolveSun(
        latitude: Double,
        longitude: Double,
        locationKey: String,
        dateKey: String,
        now: Long,
    ): SunTimes {
        queries.sunFor(locationKey, dateKey).executeAsOneOrNull()?.let {
            return SunTimes(it.sunrise_at, it.sunset_at)
        }

        val fetched = source.fetchSunTimes(latitude, longitude, dateKey, clock.utcOffsetString(now))
        // 실패하면 캐시하지 않는다 — 다음 호출에서 다시 시도한다.
        // (극야로 sunrise 가 없는 것과 통신 실패를 구분하기 위해 null 반환을 신호로 쓴다)
            ?: return SunTimes(null, null)

        queries.upsertSun(
            location_key = locationKey,
            date = dateKey,
            sunrise_at = fetched.sunriseEpochSeconds,
            sunset_at = fetched.sunsetEpochSeconds,
            fetched_at = now,
        )
        return fetched
    }

    /** 오래된 일출 캐시 정리. 앱 시작 시 한 번 부르면 된다. */
    fun pruneSunCache(beforeDateKey: String) = queries.pruneSunCache(beforeDateKey)
}

private fun com.dogdduddy.almanac.db.user.Weather_cache.toSnapshot() = WeatherSnapshot(
    wmoCode = wmo_code.toInt(),
    windSpeedMs = wind_speed_ms,
    temperatureC = temperature_c,
    observedAtEpochSeconds = observed_at,
)
