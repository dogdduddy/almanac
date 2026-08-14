package com.dogdduddy.almanac

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dogdduddy.almanac.location.Cities
import com.dogdduddy.almanac.location.City
import com.dogdduddy.almanac.location.LocationMode
import com.dogdduddy.almanac.weather.MetNorwayClient
import kotlin.math.absoluteValue

/** 화면이 그릴 상태. 플랫폼이 조립해서 넣어준다. */
sealed interface AppState {
    data object Loading : AppState

    /** `pages[0]` 이 오늘. 뒤로 갈수록 과거다. */
    data class Ready(
        val pages: List<TodaysPage>,
        val locationLabel: String,
        val locationMode: LocationMode,
    ) : AppState

    data class Empty(
        val reason: PageUnavailable,
        val locationLabel: String = "",
        val locationMode: LocationMode = LocationMode.DEFAULT,
    ) : AppState
}

/** 플랫폼이 처리하는 동작. */
data class AppActions(
    val onAddWidget: (() -> Unit)? = null,
    val onSelectCity: (City) -> Unit = {},
    val onUseGps: () -> Unit = {},
)

private enum class Screen { PAGES, CITIES, ABOUT }

@Composable
fun App(state: AppState, actions: AppActions = AppActions()) {
    var screen by remember { mutableStateOf(Screen.PAGES) }

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Paper)) {
            when (screen) {
                Screen.CITIES -> CityScreen(
                    currentMode = (state as? AppState.Ready)?.locationMode ?: LocationMode.DEFAULT,
                    onSelect = { actions.onSelectCity(it); screen = Screen.PAGES },
                    onUseGps = { actions.onUseGps(); screen = Screen.PAGES },
                    onBack = { screen = Screen.PAGES },
                )

                Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.PAGES })

                Screen.PAGES -> when (state) {
                    is AppState.Loading -> Centered("…")

                    is AppState.Empty -> EmptyScreen(
                        reason = state.reason,
                        locationLabel = state.locationLabel,
                        onOpenCities = { screen = Screen.CITIES },
                        onOpenAbout = { screen = Screen.ABOUT },
                    )

                    is AppState.Ready -> Pages(
                        pages = state.pages,
                        locationLabel = state.locationLabel,
                        actions = actions,
                        onOpenCities = { screen = Screen.CITIES },
                        onOpenAbout = { screen = Screen.ABOUT },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 오늘 + 지난 기록
// ---------------------------------------------------------------------------

@Composable
private fun Pages(
    pages: List<TodaysPage>,
    locationLabel: String,
    actions: AppActions,
    onOpenCities: () -> Unit,
    onOpenAbout: () -> Unit,
) {
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
                locationLabel = locationLabel,
                actions = if (index == 0) actions else AppActions(),
                onOpenCities = onOpenCities,
                onOpenAbout = onOpenAbout,
            )
        }
    }
}

/**
 * 페이지 넘김 변형.
 *
 * 넘어가는 페이지는 **왼쪽 가장자리를 축으로 들린다** — 종이를 집어 넘기는 동작.
 * 아래에서 드러나는 과거 페이지는 살짝 작고 흐린 상태에서 제자리로 온다.
 *
 * 위젯에서는 절대 흉내내지 말 것 (RemoteViews 제약).
 */
private fun Modifier.pageTurn(pagerState: PagerState, page: Int): Modifier = graphicsLayer {
    val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction

    // 원근을 실제로 보이게 하려면 카메라를 가까이 둬야 한다.
    // 크게 잡으면 회전이 단순한 가로 압축처럼 보인다 — 실기에서 확인했다.
    cameraDistance = 9f * density

    // 실측: 오른쪽으로 드래그하면 인덱스가 증가하고(과거로 간다),
    // **넘어가는 페이지의 offset 이 0 → +1** 로 움직인다.
    if (offset > 0f) {
        val t = offset.coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(0f, 0.5f)
        rotationY = 58f * t
        alpha = 1f - 0.5f * t
    } else {
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
    locationLabel: String,
    actions: AppActions,
    onOpenCities: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 64.dp),
    ) {
        if (!isToday) {
            Text(page.dateKey, fontSize = 12.sp, fontFamily = Serif, color = Muted,
                modifier = Modifier.padding(bottom = 12.dp))
        }

        // 주인공.
        Text(
            text = "${page.yearsAgo} years ago",
            fontSize = 44.sp,
            lineHeight = 50.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = Serif,
            color = Ink,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(page.weatherPhrase, fontSize = 15.sp, fontFamily = Serif, color = Muted,
            modifier = Modifier.padding(top = 6.dp))

        Text(page.text, fontSize = 17.sp, lineHeight = 28.sp, fontFamily = Serif, color = Ink,
            modifier = Modifier.padding(top = 28.dp))

        Text(page.attribution, fontSize = 13.sp, fontFamily = Serif, color = Muted,
            modifier = Modifier.padding(top = 20.dp))

        if (isToday) {
            Footer(
                locationLabel = locationLabel,
                actions = actions,
                onOpenCities = onOpenCities,
                onOpenAbout = onOpenAbout,
            )
        }
    }
}

@Composable
private fun Footer(
    locationLabel: String,
    actions: AppActions,
    onOpenCities: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 위치는 곧 "어디의 하늘인가" 라서 화면에 남기고, 누르면 바꿀 수 있게 한다.
        FooterLink(locationLabel.ifBlank { "Choose a place" }, onOpenCities)
        actions.onAddWidget?.let { FooterLink("Widget", it) }
        FooterLink("About", onOpenAbout)
    }
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontFamily = Serif,
        color = Muted,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun EmptyScreen(
    reason: PageUnavailable,
    locationLabel: String,
    onOpenCities: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = when (reason) {
                PageUnavailable.NO_WEATHER -> "Waiting for the sky…"
                PageUnavailable.NO_CONTENT -> "No passage for this weather yet."
            },
            fontSize = 18.sp,
            fontFamily = Serif,
            color = Muted,
        )
        // 날씨를 못 받는 이유가 위치일 수 있으므로 여기서 바로 고를 수 있어야 한다.
        Row(
            modifier = Modifier.padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FooterLink(locationLabel.ifBlank { "Choose a place" }, onOpenCities)
            FooterLink("About", onOpenAbout)
        }
    }
}

// ---------------------------------------------------------------------------
// 도시 선택
// ---------------------------------------------------------------------------

@Composable
private fun CityScreen(
    currentMode: LocationMode,
    onSelect: (City) -> Unit,
    onUseGps: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { Cities.search(query) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        ScreenHeader("Where", onBack)

        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, fontFamily = Serif, color = Ink),
            cursorBrush = SolidColor(Ink),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("Search a city", fontSize = 17.sp, fontFamily = Serif, color = Muted)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )

        Text(
            text = if (currentMode == LocationMode.GPS) "Using my location ·" else "Use my location",
            fontSize = 14.sp,
            fontFamily = Serif,
            color = if (currentMode == LocationMode.GPS) Ink else Muted,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onUseGps).padding(vertical = 14.dp),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { city ->
                Text(
                    text = "${city.name}, ${city.country}",
                    fontSize = 16.sp,
                    fontFamily = Serif,
                    color = Ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(city) }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 출처 표기 — 라이선스 의무
// ---------------------------------------------------------------------------

/**
 * 출처 표기 화면.
 *
 * **없으면 안 된다.** MET Norway 는 CC BY 4.0 이라 출처 표기가 라이선스 조건이고,
 * 번들 폰트도 SIL OFL 이라 고지가 필요하다.
 *
 * 큐레이션 원칙을 함께 적는다. 이 앱의 차별점은 "생성이 아니라 선별" 이므로,
 * 그걸 주장이 아니라 **문서로** 보여주는 자리가 필요하다.
 */
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        ScreenHeader("About", onBack)

        Section(
            "The passages",
            "Every passage is taken from a work in the public domain and chosen by hand. " +
                "Nothing here is generated. Each one names its author, its book and the year " +
                "it was written, so you can go and read the rest.",
        )
        Section(
            "Sources",
            "English passages come from Project Gutenberg. Japanese passages come from " +
                "Aozora Bunko. Only works written originally in those languages are used — " +
                "translations carry their own copyright.",
        )
        Section("Weather", MetNorwayClient.ATTRIBUTION)
        Section(
            "Typefaces",
            "Crimson Text and EB Garamond, both licensed under the SIL Open Font License 1.1.",
        )
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Text(title, fontSize = 13.sp, fontFamily = Serif, color = Muted)
        Text(body, fontSize = 15.sp, lineHeight = 24.sp, fontFamily = Serif, color = Ink,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, fontSize = 26.sp, fontFamily = Serif, color = Ink)
        Text(
            text = "Close",
            fontSize = 13.sp,
            fontFamily = Serif,
            color = Muted,
            modifier = Modifier.clickable(onClick = onBack).padding(8.dp),
        )
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 18.sp, fontFamily = Serif, color = Muted)
    }
}

private val Serif = FontFamily.Serif
private val Paper = Color(0xFFFBF9F4)
private val Ink = Color(0xFF1A1A1A)
private val Muted = Color(0xFF8A8378)
