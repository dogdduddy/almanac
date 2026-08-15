package com.dogdduddy.almanac

import android.content.Context
import com.dogdduddy.almanac.billing.Billing
import com.dogdduddy.almanac.billing.EntitlementSync
import com.dogdduddy.almanac.billing.PreviewBilling
import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.data.DatabaseFactory
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase
import com.dogdduddy.almanac.location.AndroidLocationSource
import com.dogdduddy.almanac.location.LocationRepository
import com.dogdduddy.almanac.weather.WeatherRepository
import com.dogdduddy.almanac.weather.createMetNorwayClient
import com.dogdduddy.almanac.weather.systemDeviceClock

/**
 * 조립 지점.
 *
 * **Activity 와 Glance 위젯이 같은 인스턴스를 공유해야 한다.**
 * 각자 DB 드라이버를 열면 SQLite 락이 충돌하고, 날씨 캐시도 두 벌이 되어
 * 1시간 규칙이 사실상 30분이 된다.
 *
 * DI 프레임워크를 쓰지 않는다 — 의존성이 다섯 개뿐이고, 위젯 프로세스에서의
 * 초기화 순서를 눈으로 확인할 수 있는 편이 낫다.
 */
object AlmanacGraph {

    @Volatile
    private var service: AlmanacService? = null

    @Volatile
    private var syncRef: EntitlementSync? = null

    /**
     * 결제 백엔드. 키가 없어 아직 표시 전용이다.
     * RevenueCat 키가 생기면 여기만 RevenueCatBilling 으로 바꾸면 된다.
     */
    val billing: Billing get() = PreviewBilling

    fun entitlements(context: Context): EntitlementSync {
        service(context)
        return checkNotNull(syncRef)
    }

    fun service(context: Context): AlmanacService =
        service ?: synchronized(this) {
            service ?: build(context.applicationContext).also { service = it }
        }

    private fun build(context: Context): AlmanacService {
        val factory = DatabaseFactory(context)
        val clock = systemDeviceClock()

        val user = UserDatabase(factory.createUserDriver())
        val content = ContentDatabase(factory.createContentDriver())

        val repository = AlmanacRepository(content, user) { clock.nowEpochSeconds() }
        syncRef = EntitlementSync(repository, billing)

        return AlmanacService(
            content = repository,
            weather = WeatherRepository(
                user = user,
                source = createMetNorwayClient { clock.nowEpochSeconds() },
                clock = clock,
            ),
            location = LocationRepository(
                user = user,
                // systemLocationSource() 는 Context 가 없어 no-op 을 준다. 여기서 진짜를 넣는다.
                source = AndroidLocationSource(context),
                nowEpochSeconds = { clock.nowEpochSeconds() },
            ),
            clock = clock,
        )
    }
}
