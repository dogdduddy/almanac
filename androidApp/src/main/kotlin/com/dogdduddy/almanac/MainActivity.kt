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
import androidx.lifecycle.lifecycleScope
import com.dogdduddy.almanac.widget.AlmanacWidgetReceiver

class MainActivity : ComponentActivity() {

    private var state by mutableStateOf<AppState>(AppState.Loading)

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
            onState = { state = it },
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
                ),
            )
        }

        // **화면부터 세우고 권한을 묻는다.** 순서가 반대면 프롬프트 뒤가 빈 화면이고,
        // 유저가 답할 때까지 앱은 아무것도 아닌 것처럼 보인다.
        loader.start()

        val source = com.dogdduddy.almanac.location.AndroidLocationSource(this)
        if (!source.hasPermission()) {
            requestLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
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
