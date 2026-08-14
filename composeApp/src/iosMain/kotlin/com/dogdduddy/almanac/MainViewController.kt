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
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

/**
 * iOS 진입점. SwiftUI 가 이걸 감싸서 띄운다.
 *
 * 화면 자체(App)는 Android 와 **완전히 같은 코드**다. 플랫폼이 다른 건
 * 여기 조립 부분뿐이다 — 심사 기준의 '일관성'이 여기서 나온다.
 *
 * 위치 권한 요청은 iOS 가 CLLocationManager 접근 시점에 알아서 띄운다.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    var state by remember { mutableStateOf<AppState>(AppState.Loading) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    fun load() {
        scope.launch {
            state = when (val result = IosAlmanacGraph.service.pages(refreshLocation = true)) {
                is PagesState.Ready ->
                    AppState.Ready(result.pages, result.locationLabel, result.locationMode)

                is PagesState.Empty ->
                    AppState.Empty(result.reason, result.locationLabel, result.locationMode)
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    App(
        state = state,
        actions = AppActions(
            // iOS 위젯 추가는 홈 화면에서 직접 한다 — 앱이 띄울 수 있는 창구가 없다.
            onAddWidget = null,
            onSelectCity = { city ->
                IosAlmanacGraph.service.selectCity(city)
                load()
            },
            onUseGps = {
                IosAlmanacGraph.service.useGps()
                load()
            },
        ),
    )
}
