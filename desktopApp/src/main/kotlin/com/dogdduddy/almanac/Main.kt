package com.dogdduddy.almanac

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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

    LaunchedEffect(Unit) { loader.start() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Almanac",
        state = rememberWindowState(width = 440.dp, height = 860.dp),
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
                shelfNote = "On this computer the shelf is the free starter. " +
                    "The full collection is bought and read on iPhone and Android.",
            ),
        )
    }
}

private fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }
}
