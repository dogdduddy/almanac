package com.dogdduddy.almanac

import com.dogdduddy.almanac.billing.Billing
import com.dogdduddy.almanac.billing.EntitlementSync
import com.dogdduddy.almanac.billing.PreviewBilling
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
 * 같은 이유로 **이 객체 안에서도 DB 인스턴스는 하나씩만 만든다.**
 * 저장소마다 열면 한 프로세스 안에서 같은 파일에 연결이 여러 개 생긴다.
 *
 * Swift 에서 `IosAlmanacGraph.shared.service` 로 접근한다.
 */
object IosAlmanacGraph {

    /**
     * 결제 백엔드. 키가 없어 아직 표시 전용이다.
     * RevenueCat 키가 생기면 여기만 RevenueCatBilling 으로 바꾸면 된다.
     */
    val billing: Billing get() = PreviewBilling

    private val clock by lazy { systemDeviceClock() }
    private val factory by lazy { DatabaseFactory() }

    // 파일 하나당 연결 하나.
    private val userDb by lazy { UserDatabase(factory.createUserDriver()) }
    private val contentDb by lazy { ContentDatabase(factory.createContentDriver()) }

    private val repository by lazy {
        AlmanacRepository(contentDb, userDb) { clock.nowEpochSeconds() }
    }

    val entitlements: EntitlementSync by lazy { EntitlementSync(repository, billing) }

    val service: AlmanacService by lazy {
        AlmanacService(
            content = repository,
            weather = WeatherRepository(
                user = userDb,
                source = createMetNorwayClient { clock.nowEpochSeconds() },
                clock = clock,
            ),
            location = LocationRepository(
                user = userDb,
                source = systemLocationSource(),
                nowEpochSeconds = { clock.nowEpochSeconds() },
            ),
            clock = clock,
        )
    }
}
