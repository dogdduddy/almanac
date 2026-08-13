package com.dogdduddy.almanac

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 화면이 그릴 상태. 플랫폼이 조립해서 넣어준다. */
sealed interface AppState {
    data object Loading : AppState
    data class Ready(val page: TodaysPage) : AppState
    data class Empty(val reason: PageUnavailable) : AppState
}

/**
 * 공유 화면.
 *
 * 위계는 스펙이 정한 대로다 — **주인공은 문장이 아니라 시간적 거리**.
 * "178 years ago" 가 가장 크고, 인용문은 그 아래에 놓인다.
 *
 * 아직 커스텀 폰트를 붙이지 않았다(시스템 serif). 앱 화면은 위젯과 달리
 * 폰트 제약이 없으므로, 폰트 확정 후 여기만 바꾸면 된다.
 */
@Composable
fun App(
    state: AppState,
    onAddWidget: (() -> Unit)? = null,
) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Paper)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
        ) {
            when (state) {
                is AppState.Loading -> Text(
                    text = "…",
                    fontSize = 20.sp,
                    color = Muted,
                    fontFamily = FontFamily.Serif,
                )

                is AppState.Empty -> Text(
                    text = when (state.reason) {
                        PageUnavailable.NO_WEATHER -> "Waiting for the sky…"
                        PageUnavailable.NO_CONTENT -> "No passage for this weather yet."
                    },
                    fontSize = 18.sp,
                    color = Muted,
                    fontFamily = FontFamily.Serif,
                )

                is AppState.Ready -> PageBody(state.page)
            }

            onAddWidget?.let {
                TextButton(onClick = it, modifier = Modifier.padding(top = 32.dp)) {
                    Text("Add to home screen", fontFamily = FontFamily.Serif, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun PageBody(page: TodaysPage) {
    // 주인공.
    Text(
        text = "${page.yearsAgo} years ago",
        fontSize = 44.sp,
        lineHeight = 50.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Serif,
        color = Ink,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text = page.weatherPhrase,
        fontSize = 15.sp,
        fontFamily = FontFamily.Serif,
        color = Muted,
        modifier = Modifier.padding(top = 6.dp),
    )

    Text(
        text = page.text,
        fontSize = 17.sp,
        lineHeight = 28.sp,
        fontFamily = FontFamily.Serif,
        color = Ink,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(top = 28.dp),
    )

    Text(
        text = page.attribution,
        fontSize = 13.sp,
        fontFamily = FontFamily.Serif,
        color = Muted,
        modifier = Modifier.padding(top = 20.dp),
    )

    Text(
        text = page.locationLabel,
        fontSize = 12.sp,
        fontFamily = FontFamily.Serif,
        color = Muted,
        modifier = Modifier.padding(top = 40.dp),
    )
}

private val Paper = Color(0xFFFBF9F4)
private val Ink = Color(0xFF1A1A1A)
private val Muted = Color(0xFF8A8378)
