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
     * 결제 백엔드.
     *
     * 기본값은 [PreviewBilling] 이고, **앱이 시작할 때 실제 구현을 주입한다.**
     * RevenueCat 구현이 shared 가 아니라 앱 모듈에 있기 때문이다 —
     * 그 SDK 의 iOS cinterop 이 Kotlin/Native 테스트 링크를 깨뜨려서 분리했다.
     *
     * **위젯은 이 값을 바꾸지 않는다.** 위젯은 아무것도 팔지 않으므로 기본값이면 충분하고,
     * 익스텐션이 결제 SDK 를 링크할 이유도 없다.
     */
    var billing: Billing = PreviewBilling

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
