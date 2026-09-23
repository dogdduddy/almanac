package com.dogdduddy.almanac

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.dogdduddy.almanac.billing.createIosBilling
import platform.UIKit.UIViewController

/**
 * iOS 진입점. SwiftUI 가 이걸 감싸서 띄운다.
 *
 * 화면 자체(App)도, 시작 절차([AppLoader])도 Android 와 **완전히 같은 코드**다.
 * 플랫폼이 다른 건 여기 조립 부분뿐이다 — 심사 기준의 '일관성'이 여기서 나온다.
 *
 * 위치 권한 프롬프트는 첫 측위(IosLocationSource)가 직접 띄운다 —
 * iOS 는 매니저를 만들었다고 알아서 물어보지 않는다. 그 프롬프트가 뜰 때쯤이면
 * [AppLoader] 의 첫 단계가 이미 화면을 채워둔 상태다.
 *
 * @param demoTools 촬영용 조건 고정 메뉴를 띄울지. **Swift 의 `#if DEBUG` 가 넘긴다** —
 *   Kotlin 쪽에서 판단하지 않는 이유는, 여기서 볼 수 있는 것이 Kotlin 프레임워크의
 *   빌드 구성이지 앱 타깃의 구성이 아니기 때문이다. 릴리스 앱에는 false 로 들어온다.
 */
fun MainViewController(demoTools: Boolean = false): UIViewController = ComposeUIViewController {
    var state by remember { mutableStateOf<AppState>(AppState.Loading) }

    // 메인 디스패처여야 한다. CLLocationManager 를 만드는 스레드가 여기로 정해지고,
    // 런루프 없는 스레드에서 만들면 델리게이트가 영영 안 불린다.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    val loader = remember {
        AppLoader(
            scope = scope,
            // 셋 다 함수로 넘긴다. 여기서 값을 꺼내면 DB 복사와 SQLite 열기가
            // 첫 컴포지션 중에 메인 스레드에서 일어난다.
            service = { IosAlmanacGraph.service },
            entitlements = { IosAlmanacGraph.entitlements },
            billing = { IosAlmanacGraph.billing },
            onState = { state = it },
        )
    }

    LaunchedEffect(Unit) {
        // 결제 구현 주입. 앱에서만 하고 위젯은 하지 않는다.
        // **시작보다 반드시 앞서야 한다** — EntitlementSync 의 lazy 가 처음 만들어질 때
        // 이 값을 붙들기 때문에, 늦으면 결제가 PreviewBilling 으로 조용히 죽는다.
        IosAlmanacGraph.billing = createIosBilling()
        loader.start()
    }

    App(
        state = state,
        actions = AppActions(
            // iOS 위젯 추가는 홈 화면에서 직접 한다 — 앱이 띄울 수 있는 창구가 없다.
            onAddWidget = null,
            onOpenPrivacy = {
                platform.UIKit.UIApplication.sharedApplication.openURL(
                    platform.Foundation.NSURL(string = Legal.PRIVACY_POLICY_URL)
                )
            },
            onSelectCity = loader::selectCity,
            onUseGps = loader::useGps,
            onPurchase = loader::purchase,
            onRestore = loader::restore,
            onPurchaseAcknowledged = loader::acknowledgePurchase,
            onRedeemCode = loader::redeem,
            demo = if (!demoTools) null else DemoActions(
                controls = IosAlmanacGraph.demo,
                onApply = loader::refresh,
                onResetHistory = loader::resetHistory,
            ),
        ),
    )
}
