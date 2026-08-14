package com.dogdduddy.almanac

import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.data.DatabaseFactory
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase
import com.dogdduddy.almanac.location.LocationRepository
import com.dogdduddy.almanac.location.systemLocationSource
import com.dogdduddy.almanac.weather.WeatherRepository
import com.dogdduddy.almanac.weather.createMetNorwayClient
import com.dogdduddy.almanac.weather.systemDeviceClock

/**
 * iOS 조립 지점.
 *
 * **앱과 WidgetKit 익스텐션이 같은 인스턴스를 쓴다.** 각자 DB 를 열면 날씨 캐시가
 * 두 벌이 되어 1시간 규칙이 사실상 30분이 되고, SQLite 락도 충돌한다.
 * (Android 의 AlmanacGraph 와 같은 이유다)
 *
 * Swift 에서 `IosAlmanacGraph.shared.service` 로 접근한다.
 */
object IosAlmanacGraph {

    val service: AlmanacService by lazy {
        val factory = DatabaseFactory()
        val clock = systemDeviceClock()

        val user = UserDatabase(factory.createUserDriver())
        val content = ContentDatabase(factory.createContentDriver())

        AlmanacService(
            content = AlmanacRepository(content, user) { clock.nowEpochSeconds() },
            weather = WeatherRepository(
                user = user,
                source = createMetNorwayClient { clock.nowEpochSeconds() },
                clock = clock,
            ),
            location = LocationRepository(
                user = user,
                source = systemLocationSource(),
                nowEpochSeconds = { clock.nowEpochSeconds() },
            ),
            clock = clock,
        )
    }
}
