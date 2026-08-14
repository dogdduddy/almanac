package com.dogdduddy.almanac

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

/** 화면이 그릴 상태. 플랫폼이 조립해서 넣어준다. */
sealed interface AppState {
    data object Loading : AppState

    /** `pages[0]` 이 오늘. 뒤로 갈수록 과거다. */
    data class Ready(val pages: List<TodaysPage>) : AppState
    data class Empty(val reason: PageUnavailable) : AppState
}

/**
 * 공유 화면.
 *
 * 위계는 스펙이 정한 대로다 — **주인공은 문장이 아니라 시간적 거리**.
 * "178 years ago" 가 가장 크고 인용문은 그 아래에 놓인다.
 *
 * 지난 기록으로는 **역방향으로 넘긴다**. 오른쪽으로 밀면 과거가 나온다 —
 * 책을 앞쪽으로 되넘기는 방향이다. 이 감각이 "이미 오래전에 써놓았다"는
 * 컨셉과 맞물리므로 방향을 반대로 두지 말 것.
 */
@Composable
fun App(
    state: AppState,
    onAddWidget: (() -> Unit)? = null,
) {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Paper)) {
            when (state) {
                is AppState.Loading -> Centered("…")

                is AppState.Empty -> Centered(
                    when (state.reason) {
                        PageUnavailable.NO_WEATHER -> "Waiting for the sky…"
                        PageUnavailable.NO_CONTENT -> "No passage for this weather yet."
                    }
                )

                is AppState.Ready -> Pages(state.pages, onAddWidget)
            }
        }
    }
}

@Composable
private fun Pages(pages: List<TodaysPage>, onAddWidget: (() -> Unit)?) {
    val pagerState = rememberPagerState(pageCount = { pages.size })

    HorizontalPager(
        state = pagerState,
        // 오른쪽으로 밀면 인덱스가 증가한다 = 과거로 되넘긴다.
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pageTurn(pagerState, index)
                .background(Paper),
        ) {
            PageBody(
                page = pages[index],
                isToday = index == 0,
                onAddWidget = onAddWidget.takeIf { index == 0 },
            )
        }
    }
}

/**
 * 페이지 넘김 변형.
 *
 * 넘어가는 쪽은 **왼쪽 가장자리를 축으로 들린다** — 종이를 집어 넘기는 동작.
 * 아래에 드러나는 과거 페이지는 살짝 작고 흐린 상태에서 제자리로 돌아온다.
 *
 * 위젯에서는 절대 흉내내지 말 것 (RemoteViews 제약).
 */
private fun Modifier.pageTurn(pagerState: PagerState, page: Int): Modifier = graphicsLayer {
    val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction

    // 원근을 실제로 보이게 하려면 카메라를 가까이 둬야 한다.
    // 기본값(8)보다 크게 잡으면 회전이 단순한 가로 압축처럼 보인다 — 실기에서 확인했다.
    cameraDistance = 9f * density

    // 실측: 오른쪽으로 드래그하면 인덱스가 증가하고(과거로 간다),
    // **넘어가는 페이지의 offset 이 0 → +1** 로 움직인다.
    if (offset > 0f) {
        val t = offset.coerceIn(0f, 1f)
        // 책등이 왼쪽에 있다고 보고 왼쪽 가장자리를 축으로 들어올린다.
        transformOrigin = TransformOrigin(0f, 0.5f)
        rotationY = 58f * t
        alpha = 1f - 0.5f * t
    } else {
        // 아래에서 드러나는 과거 페이지. 살짝 작고 흐린 상태에서 제자리로 온다.
        val t = offset.absoluteValue.coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(0.5f, 0.5f)
        scaleX = 1f - 0.05f * t
        scaleY = 1f - 0.05f * t
        alpha = 1f - 0.25f * t
    }
}

@Composable
private fun PageBody(
    page: TodaysPage,
    isToday: Boolean,
    onAddWidget: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 64.dp),
    ) {
        if (!isToday) {
            Text(
                text = page.dateKey,
                fontSize = 12.sp,
                fontFamily = FontFamily.Serif,
                color = Muted,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

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
            modifier = Modifier.padding(top = 28.dp),
        )

        Text(
            text = page.attribution,
            fontSize = 13.sp,
            fontFamily = FontFamily.Serif,
            color = Muted,
            modifier = Modifier.padding(top = 20.dp),
        )

        if (page.locationLabel.isNotBlank()) {
            Text(
                text = page.locationLabel,
                fontSize = 12.sp,
                fontFamily = FontFamily.Serif,
                color = Muted,
                modifier = Modifier.padding(top = 36.dp),
            )
        }

        onAddWidget?.let {
            TextButton(onClick = it, modifier = Modifier.padding(top = 12.dp)) {
                Text("Add to home screen", fontFamily = FontFamily.Serif, color = Muted)
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 18.sp, fontFamily = FontFamily.Serif, color = Muted)
    }
}

private val Paper = Color(0xFFFBF9F4)
private val Ink = Color(0xFF1A1A1A)
private val Muted = Color(0xFF8A8378)
