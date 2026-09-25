package com.dogdduddy.almanac

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.dogdduddy.almanac.widget.AlmanacWidget
import com.dogdduddy.almanac.widget.AlmanacWidgetReceiver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var state by mutableStateOf<AppState>(AppState.Loading)
    private var hasResumedOnce = false

    /**
     * 앱이 새 페이지를 그리면 홈 화면 위젯도 같은 저장 위치와 슬롯으로 다시 그린다.
     *
     * 상태가 빠르게 연달아 나올 수 있다(저장 위치 -> GPS 위치 -> 상품 목록). 실행 중인
     * 갱신을 취소하면 여러 위젯 중 일부만 바뀔 수 있으므로, 요청을 하나로 합쳐 직렬로
     * 처리한다. 그러면 마지막 앱 상태 뒤에는 반드시 마지막 위젯 갱신이 남는다.
     */
    private val widgetUpdateRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * 위치 권한은 **거부돼도 앱이 온전히 동작한다.** 그래서 결과와 무관하게
     * 같은 경로로 페이지를 다시 불러온다 — 거부하면 저장된 도시로 그려진다.
     */
    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            loader.locationPermissionSettled()
        }

    /**
     * 시작 절차는 iOS 와 공유한다 (shared 의 [AppLoader]).
     *
     * 의존성을 함수로 넘기는 이유는 [AlmanacGraph.service] 가 첫 호출에서 DB 를 여는데,
     * 그 일이 onCreate 의 메인 스레드에서 벌어지면 안 되기 때문이다 —
     * 로더가 코루틴 안에서 처음 만진다.
     */
    private val loader by lazy {
        AppLoader(
            scope = lifecycleScope,
            service = { AlmanacGraph.service(this) },
            entitlements = { AlmanacGraph.entitlements(this) },
            billing = { AlmanacGraph.billing },
            onState = ::publishState,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App(
                state = state,
                actions = AppActions(
                    onAddWidget = ::requestPinWidget,
                    onOpenPrivacy = ::openPrivacyPolicy,
                    // 위치가 바뀌면 날씨도 문장도 바뀐다. 로더가 즉시 다시 그린다.
                    onSelectCity = loader::selectCity,
                    onUseGps = loader::useGps,
                    // 성공이든 실패든 다시 그린다 — 보유 팩이 바뀌면 문장도 바뀐다.
                    onPurchase = loader::purchase,
                    onRestore = loader::restore,
                    onPurchaseAcknowledged = loader::acknowledgePurchase,
                    onRedeemCode = loader::redeem,
                    demo = demoActions(),
                ),
            )
        }

        // 위젯 갱신은 앱의 첫 화면을 막지 않는다. App Review 반려 대응의 핵심인
        // "아무것도 기다리지 않고 화면부터 표시" 순서를 그대로 지킨다.
        lifecycleScope.launch {
            for (ignored in widgetUpdateRequests) {
                runCatching { AlmanacWidget().updateAll(this@MainActivity) }
            }
        }

        // **화면부터 세우고 권한을 묻는다.** 순서가 반대면 프롬프트 뒤가 빈 화면이고,
        // 유저가 답할 때까지 앱은 아무것도 아닌 것처럼 보인다.
        loader.start()

        val source = com.dogdduddy.almanac.location.AndroidLocationSource(this)
        if (!source.hasPermission()) {
            requestLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    override fun onResume() {
        super.onResume()
        // 첫 onResume 은 start() 의 초기 동기화가 처리한다. 그 뒤의 복귀는 Play Store
        // 에서 프로모션 코드를 사용했거나 구매 상태가 바뀐 경우일 수 있다.
        if (hasResumedOnce) loader.storeMayHaveChanged()
        else hasResumedOnce = true
    }

    /**
     * 촬영용 조건 고정 창구. **debug 와 demo 빌드에만 만든다.**
     *
     * `BuildConfig.DEBUG` 가 아니라 전용 플래그를 보는 이유는 촬영 빌드 때문이다 —
     * demo 는 release 의 최적화를 쓰면서 이 메뉴가 있어야 한다 (build.gradle.kts 참고).
     *
     * 스토어로 나가는 release 에서는 null 이라 About 의 진입 줄 자체가 없다.
     */
    private fun demoActions(): DemoActions? =
        if (!BuildConfig.DEMO_TOOLS) null
        else DemoActions(
            controls = AlmanacGraph.demo,
            onApply = loader::refresh,
            onResetHistory = loader::resetHistory,
        )

    private fun publishState(next: AppState) {
        // Compose 상태를 먼저 바꾼다. 위젯 갱신이 느리거나 실패해도 앱 첫 화면에는
        // 영향을 주지 않아야 한다.
        state = next
        if (next !is AppState.Loading) widgetUpdateRequests.trySend(Unit)
    }

    /** 스토어 정책상 앱 안에서 개인정보처리방침에 닿을 수 있어야 한다. */
    private fun openPrivacyPolicy() {
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(com.dogdduddy.almanac.Legal.PRIVACY_POLICY_URL),
                )
            )
        }
    }

    private fun requestPinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java) ?: return
        if (!manager.isRequestPinAppWidgetSupported) return
        manager.requestPinAppWidget(
            ComponentName(this, AlmanacWidgetReceiver::class.java),
            null,
            null,
        )
    }
}
