package com.dogdduddy.almanac

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.demo.DemoControls
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Desktop
import java.net.URI

/**
 * 데스크톱 진입점.
 *
 * 화면(App)도 시작 절차(AppLoader)도 Android·iOS 와 **완전히 같은 코드**다.
 * 플랫폼이 다른 건 이 파일과 DesktopAlmanacGraph 뿐이다.
 *
 * 창은 폰 비율로 연다. 이 앱의 화면은 한 단(column) 조판이라 넓은 창에서는
 * 행이 길어져 읽기 나쁘다. 유저가 늘리는 것은 막지 않는다.
 */
fun main() = application {
    var state by remember { mutableStateOf<AppState>(AppState.Loading) }

    // 메인 디스패처(Swing EDT). AppLoader 가 요구한다 — 상태를 컴포지션에 바로 넣기 위해서다.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    val loader = remember {
        AppLoader(
            scope = scope,
            // 함수로 넘긴다. 값으로 꺼내면 DB 복사와 SQLite 열기가 첫 컴포지션 중에 일어난다.
            service = { DesktopAlmanacGraph.service },
            entitlements = { DesktopAlmanacGraph.entitlements },
            billing = { DesktopAlmanacGraph.billing },
            onState = { state = it },
        )
    }

    LaunchedEffect(Unit) {
        applyDemoProperties()
        loader.start()
        if (System.getProperty("almanac.demo.archive") == "refill") {
            // 비우고 채운다. 비우기만 하면 넘길 것이 없다 — 넘기기는 아카이브를
            // 탐색할 뿐 만들지 않는다.
            //
            // **사이를 기다려야 폰과 같은 아카이브가 나온다.** 비우기는 오늘 페이지를
            // 다시 그려 기록하는데, 그게 끝나기 전에 채우기 시작하면 오늘 슬롯이
            // 히스토리에 없는 상태로 과거를 고르게 된다. 읽음 횟수와 최근 목록이
            // 선택의 입력이라(결정론 계약 2.7) 거기서 폰과 갈렸다.
            // 폰에서는 사람이 두 줄을 몇 초 간격으로 누르므로 저절로 지켜진다.
            loader.resetHistory()
            delay(1_500)
            loader.fillArchive()
            delay(2_000)        // 채우는 동안 화면이 자리를 잡게 둔다
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Almanac",
        // 촬영 중에는 위에 띄운다. 녹화는 화면의 그 자리를 찍는 것이라
        // 다른 창이 앞에 오면 그 창이 찍힌다 — 실제로 한 번 그렇게 찍혔다.
        alwaysOnTop = demoTools,
        // **촬영 모드에서는 창 자리를 못 박는다.** 분할 화면(#7)은 창 영역을 좌표로
        // 녹화하는데(`screencapture -R`), 뜰 때마다 자리가 달라지면 컷마다 영역을
        // 다시 찾아야 하고 그러면 결과가 매번 달라진다. 평소에는 OS 가 정하게 둔다.
        state = if (demoTools) rememberWindowState(
            width = 440.dp, height = 860.dp,
            position = WindowPosition(DEMO_WINDOW_X.dp, DEMO_WINDOW_Y.dp),
        ) else rememberWindowState(width = 440.dp, height = 860.dp),
    ) {
        App(
            state = state,
            actions = AppActions(
                // 홈 위젯은 모바일 것이다.
                onAddWidget = null,
                onOpenPrivacy = { openInBrowser(Legal.PRIVACY_POLICY_URL) },
                onSelectCity = loader::selectCity,
                // 측위가 없어 저장된 도시로 되돌아간다. 모바일에서 권한을 거부한 것과 같은 경로.
                onUseGps = loader::useGps,
                onPurchase = loader::purchase,
                onRestore = loader::restore,
                onPurchaseAcknowledged = loader::acknowledgePurchase,
                // **코드는 받는다.** 결제가 없다는 것과 열 방법이 없다는 것은 다르다.
                // 제출물은 심사위원에게 코드로 전부 열라고 안내하는데, 데스크톱에만
                // 그 길이 없으면 여기서 앱이 반쪽으로 보인다 (promo-code.md).
                onRedeemCode = loader::redeem,
                shelfNote = "This computer has no store. The full collection is " +
                    "bought on iPhone and Android, or opened here with a code.",
                demo = demoActions(loader),
            ),
        )
    }
}

/**
 * 촬영용 조건 고정 창구. **시스템 프로퍼티로 켠다.**
 *
 *   ./gradlew :desktopApp:run -Palmanac.demo=true
 *
 * 데스크톱에는 `BuildConfig.DEBUG` 도 Swift 의 `#if DEBUG` 도 없다. 환경 변수를 쓰지
 * 않는 이유는 Gradle 이 앱을 **데몬의 환경**으로 띄우기 때문이다 — 셸에서 앞에 붙인
 * 변수가 들어가지 않는다. 배포용 distributable 에는 이 프로퍼티가 없으므로 진입점도 없다.
 *
 * 데스크톱에 이 창구가 필요한 이유는 하나다. 분할 화면 컷에서 **세 화면이 같은 문장을
 * 내려면 시드를 맞춰야** 하는데, 데스크톱은 자기 user.db 와 자기 installId 를 갖는다.
 */
/** 촬영 모드인가. `-Palmanac.demo=true` 로 켠다. */
private val demoTools: Boolean get() = System.getProperty("almanac.demo") == "true"

/** 촬영에서 창이 뜨는 자리 (논리 좌표). `screencapture -R` 이 같은 값을 쓴다. */
const val DEMO_WINDOW_X = 60
const val DEMO_WINDOW_Y = 40

/**
 * 촬영 고정을 **실행 옵션으로** 받는다.
 *
 * 데스크톱에만 있는 창구다. 폰은 화면을 눌러 맞추면 되지만 맥 창을 누르려면
 * 손쉬운 사용 권한이 필요하고, 그 권한 없이도 분할 화면(#7)을 찍을 수 있어야 한다.
 * 덤으로 **Demo 화면을 아예 열지 않아도 된다** — 한 프레임도 안 들어가는 것이 요구다.
 *
 *   ./gradlew :desktopApp:run -Palmanac.demo=true \
 *       -Palmanac.demo.weather=clear -Palmanac.demo.time=day \
 *       -Palmanac.demo.seed=shared -Palmanac.demo.archive=refill
 *
 * 모르는 값이 오면 조용히 넘긴다 — 촬영 중에 앱이 안 뜨는 것이 제일 나쁘다.
 */
private fun applyDemoProperties() {
    if (!demoTools) return
    val demo: DemoControls = DesktopAlmanacGraph.demo

    System.getProperty("almanac.demo.weather")?.let { key ->
        demo.weatherGroup = WeatherGroup.entries.firstOrNull { it.key == key.lowercase() }
        demo.temperatureC = demo.weatherGroup?.let { DemoControls.defaultTemperature(it) }
    }
    System.getProperty("almanac.demo.time")?.let { key ->
        demo.timeOfDay = TimeOfDay.entries.firstOrNull { it.key == key.lowercase() }
    }
    if (System.getProperty("almanac.demo.seed") == "shared") {
        demo.installId = DemoControls.SHARED_SEED_INSTALL_ID
    }
}

private fun demoActions(loader: AppLoader): DemoActions? =
    if (!demoTools) null
    else DemoActions(
        controls = DesktopAlmanacGraph.demo,
        onApply = loader::refresh,
        onResetHistory = loader::resetHistory,
        onFillArchive = loader::fillArchive,
    )

private fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }
}
