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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var state by mutableStateOf<AppState>(AppState.Loading)

    /**
     * 위치 권한은 **거부돼도 앱이 온전히 동작한다.** 그래서 결과와 무관하게
     * 같은 경로로 페이지를 다시 불러온다 — 거부하면 저장된 도시로 그려진다.
     */
    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App(
                state = state,
                actions = AppActions(
                    onAddWidget = ::requestPinWidget,
                    onSelectCity = { city ->
                        AlmanacGraph.service(this).selectCity(city)
                        // 위치가 바뀌면 날씨도 문장도 바뀐다. 즉시 다시 그린다.
                        load()
                    },
                    onUseGps = {
                        AlmanacGraph.service(this).useGps()
                        load()
                    },
                    onPurchase = { product ->
                        lifecycleScope.launch {
                            AlmanacGraph.entitlements(this@MainActivity).purchase(product)
                            // 성공이든 실패든 다시 그린다 — 보유 팩이 바뀌면 문장도 바뀐다.
                            load()
                        }
                    },
                    onRestore = {
                        lifecycleScope.launch {
                            AlmanacGraph.entitlements(this@MainActivity).restore()
                            load()
                        }
                    },
                ),
            )
        }

        val source = com.dogdduddy.almanac.location.AndroidLocationSource(this)
        if (!source.hasPermission()) {
            requestLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            load()
        }
    }

    private fun load() {
        lifecycleScope.launch {
            // 앱에서는 측위를 시도한다. 위젯과 달리 사용자를 잠시 기다리게 할 수 있다.
            val service = AlmanacGraph.service(this@MainActivity)
            // 결제 상태를 먼저 맞춘다. 보유 팩이 후보 집합을 바꾸므로 페이지보다 앞서야 한다.
            AlmanacGraph.entitlements(this@MainActivity).sync()
            val paywall = service.paywall(AlmanacGraph.billing)

            state = when (val result = service.pages(refreshLocation = true)) {
                is PagesState.Ready ->
                    AppState.Ready(
                        result.pages, result.locationLabel, result.locationMode, paywall.products,
                    )

                is PagesState.Empty ->
                    AppState.Empty(result.reason, result.locationLabel, result.locationMode)
            }
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
