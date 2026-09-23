package com.dogdduddy.almanac

import com.dogdduddy.almanac.billing.Billing
import com.dogdduddy.almanac.billing.EntitlementSync
import com.dogdduddy.almanac.billing.NoBilling
import com.dogdduddy.almanac.demo.DemoControls
import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.data.DatabaseFactory
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase
import com.dogdduddy.almanac.location.LocationRepository
import com.dogdduddy.almanac.location.systemLocationSource
import com.dogdduddy.almanac.weather.WeatherRepository
import com.dogdduddy.almanac.weather.createMetNorwayClient
import com.dogdduddy.almanac.weather.systemDeviceClock
import java.io.File

/**
 * 데스크톱 조립 지점. IosAlmanacGraph 와 같은 모양이다.
 *
 * **결제는 [NoBilling] 이다.** RevenueCat 은 데스크톱 SDK 가 없고, 계정이 없어 모바일
 * 구매를 여기로 가져올 길도 없다. 상품이 비면 화면이 서가 진입점을 감추므로 유저에게
 * 못 사는 버튼이 보이지 않는다. 데스크톱은 무료 스타터 서가를 읽는 창이다.
 *
 * 프로세스 안에서 DB 파일 하나당 연결 하나. 위젯이 없어 공유할 상대는 없지만
 * 규칙은 모바일과 같이 둔다.
 */
object DesktopAlmanacGraph {

    val billing: Billing = NoBilling

    /**
     * 촬영용 조건 고정. 값을 넣는 창구는 `-Palmanac.demo=true` 로 띄웠을 때만 생긴다
     * (Main.kt). 데스크톱에는 BuildConfig 도 `#if DEBUG` 도 없다.
     */
    val demo = DemoControls()

    private val clock by lazy { systemDeviceClock() }

    private val factory by lazy {
        DatabaseFactory(
            dataDir = dataDirectory(),
            bundledContent = {
                checkNotNull(DesktopAlmanacGraph::class.java.getResourceAsStream("/content.db")) {
                    "content.db 가 클래스패스에 없다 — ./gradlew syncContent 로 복사할 것"
                }
            },
        )
    }

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
            demo = demo,
        )
    }

    /**
     * 유저 데이터 디렉터리. OS 관례를 따른다.
     *
     * - macOS: `~/Library/Application Support/Almanac`
     * - Windows: `%APPDATA%\Almanac`
     * - Linux: `$XDG_DATA_HOME/almanac` (없으면 `~/.local/share/almanac`)
     */
    private fun dataDirectory(): File {
        val home = File(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> File(home, "Library/Application Support/Almanac")
            os.contains("win") -> File(System.getenv("APPDATA") ?: home.path, "Almanac")
            else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "almanac")
        }
    }
}
