package com.dogdduddy.almanac

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dogdduddy.almanac.billing.BillingProduct
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.location.Cities
import com.dogdduddy.almanac.location.City
import com.dogdduddy.almanac.location.LocationMode
import com.dogdduddy.almanac.resources.Res
import com.dogdduddy.almanac.resources.crimson_text
import com.dogdduddy.almanac.weather.MetNorwayClient
import org.jetbrains.compose.resources.Font
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/** 화면이 그릴 상태. 플랫폼이 조립해서 넣어준다. */
sealed interface AppState {
    data object Loading : AppState

    /** `pages[0]` 이 오늘. 뒤로 갈수록 과거다. */
    data class Ready(
        val pages: List<TodaysPage>,
        val locationLabel: String,
        val locationMode: LocationMode,
        /** 살 수 있는 상품. 비어 있으면 페이월 진입점을 감춘다. */
        val products: List<BillingProduct> = emptyList(),
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
    val onPurchase: (BillingProduct) -> Unit = {},
    /** 스토어 정책상 복원은 반드시 제공해야 한다. */
    val onRestore: () -> Unit = {},
    /**
     * 개인정보처리방침을 브라우저로 연다.
     *
     * Apple 과 Google 모두 **앱 안에서 접근 가능한 링크**를 요구한다.
     * 스토어 등록 페이지에만 URL 을 적어두는 것으로는 부족하다.
     */
    val onOpenPrivacy: (() -> Unit)? = null,
)

private enum class Screen { PAGES, CITIES, ABOUT, PAYWALL }

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

                Screen.ABOUT -> AboutScreen(
                    onBack = { screen = Screen.PAGES },
                    onOpenPrivacy = actions.onOpenPrivacy,
                )

                Screen.PAYWALL -> PaywallScreen(
                    products = (state as? AppState.Ready)?.products.orEmpty(),
                    onPurchase = { actions.onPurchase(it); screen = Screen.PAGES },
                    onRestore = { actions.onRestore(); screen = Screen.PAGES },
                    onBack = { screen = Screen.PAGES },
                )

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
                        canUpgrade = state.products.isNotEmpty(),
                        actions = actions,
                        onOpenCities = { screen = Screen.CITIES },
                        onOpenAbout = { screen = Screen.ABOUT },
                        onOpenPaywall = { screen = Screen.PAYWALL },
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
    canUpgrade: Boolean,
    actions: AppActions,
    onOpenCities: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPaywall: () -> Unit,
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
                // 푸터는 '맨 앞 페이지'의 것이다. 오늘 페이지가 없어도 도시 선택·구매
                // 진입점은 있어야 하므로 page.isToday 가 아니라 위치로 판단한다.
                isCurrent = index == 0,
                locationLabel = locationLabel,
                canUpgrade = canUpgrade,
                actions = if (index == 0) actions else AppActions(),
                onOpenCities = onOpenCities,
                onOpenAbout = onOpenAbout,
                onOpenPaywall = onOpenPaywall,
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
    isCurrent: Boolean,
    locationLabel: String,
    canUpgrade: Boolean,
    actions: AppActions,
    onOpenCities: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPaywall: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 64.dp),
    ) {
        // 오늘 것만 날짜를 감춘다. 첫 페이지라도 오늘이 아니면 날짜를 보여줘야 한다.
        if (!page.isToday) {
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

        WeatherMetaRow(page)

        Text(page.text, fontSize = 17.sp, lineHeight = 28.sp, fontFamily = Serif, color = Ink,
            modifier = Modifier.padding(top = 28.dp))

        Text(page.attribution, fontSize = 13.sp, fontFamily = Serif, color = Muted,
            modifier = Modifier.padding(top = 20.dp))

        if (isCurrent) {
            Footer(
                locationLabel = locationLabel,
                canUpgrade = canUpgrade,
                actions = actions,
                onOpenCities = onOpenCities,
                onOpenAbout = onOpenAbout,
                onOpenPaywall = onOpenPaywall,
            )
        }
    }
}

/**
 * 문학적 한 줄을 먼저 읽고, 현재 날씨를 작은 보조 정보로 뒤에 붙인다.
 *
 * 과거 페이지에는 당시 기온을 저장하지 않으므로 아이콘까지만 그린다. 지금 기온을
 * 과거 기록에 붙이면 잘못된 정보가 되기 때문이다.
 */
@Composable
private fun WeatherMetaRow(page: TodaysPage) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(page.weatherPhrase, fontSize = 15.sp, fontFamily = Serif, color = Muted)
        Text(
            text = "·",
            fontSize = 15.sp,
            fontFamily = Serif,
            color = Muted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        WeatherIcon(
            weatherGroup = page.weatherGroup,
            modifier = Modifier.size(width = 20.dp, height = 16.dp),
        )
        page.temperatureC.temperatureLabel()?.let { temperature ->
            Text(
                text = temperature,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = Serif,
                color = Ink,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}

private fun Double?.temperatureLabel(): String? =
    this?.takeIf { it.isFinite() }?.roundToInt()?.let { "$it°" }

/** 플랫폼 이모지 대신 직접 그려 iOS와 Android의 모양·색을 동일하게 유지한다. */
@Composable
private fun WeatherIcon(weatherGroup: WeatherGroup, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val lineWidth = 1.3.dp.toPx()
        val stroke = Stroke(
            width = lineWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (weatherGroup) {
            WeatherGroup.CLEAR -> drawSunGlyph(Muted, stroke)
            WeatherGroup.CLOUDY -> drawCloudGlyph(Muted, stroke, baseFraction = 0.76f)
            WeatherGroup.FOG -> drawFogGlyph(Muted, lineWidth)
            WeatherGroup.DRIZZLE -> {
                drawCloudGlyph(Muted, stroke, baseFraction = 0.56f)
                drawLine(Muted, Offset(size.width * 0.35f, size.height * 0.72f),
                    Offset(size.width * 0.33f, size.height * 0.82f), lineWidth, StrokeCap.Round)
                drawLine(Muted, Offset(size.width * 0.66f, size.height * 0.72f),
                    Offset(size.width * 0.64f, size.height * 0.82f), lineWidth, StrokeCap.Round)
            }
            WeatherGroup.RAIN -> {
                drawCloudGlyph(Muted, stroke, baseFraction = 0.56f)
                listOf(0.29f, 0.50f, 0.71f).forEach { x ->
                    drawLine(Muted, Offset(size.width * x, size.height * 0.70f),
                        Offset(size.width * (x - 0.04f), size.height * 0.92f),
                        lineWidth, StrokeCap.Round)
                }
            }
            WeatherGroup.SNOW -> {
                drawCloudGlyph(Muted, stroke, baseFraction = 0.54f)
                drawSnowflakeGlyph(Offset(size.width * 0.34f, size.height * 0.82f), Muted, lineWidth)
                drawSnowflakeGlyph(Offset(size.width * 0.68f, size.height * 0.82f), Muted, lineWidth)
            }
            WeatherGroup.THUNDER -> {
                drawCloudGlyph(Muted, stroke, baseFraction = 0.54f)
                val bolt = Path().apply {
                    moveTo(size.width * 0.56f, size.height * 0.62f)
                    lineTo(size.width * 0.43f, size.height * 0.80f)
                    lineTo(size.width * 0.54f, size.height * 0.80f)
                    lineTo(size.width * 0.45f, size.height * 0.98f)
                    lineTo(size.width * 0.69f, size.height * 0.73f)
                    lineTo(size.width * 0.57f, size.height * 0.73f)
                }
                drawPath(bolt, Muted, style = stroke)
            }
            WeatherGroup.WIND -> drawWindGlyph(Muted, stroke)
        }
    }
}

private fun DrawScope.drawCloudGlyph(color: Color, stroke: Stroke, baseFraction: Float) {
    val w = size.width
    val base = size.height * baseFraction
    val cloud = Path().apply {
        moveTo(w * 0.16f, base)
        cubicTo(w * 0.05f, base, w * 0.03f, base * 0.67f, w * 0.18f, base * 0.62f)
        cubicTo(w * 0.20f, base * 0.28f, w * 0.41f, base * 0.16f, w * 0.51f, base * 0.42f)
        cubicTo(w * 0.62f, base * 0.27f, w * 0.78f, base * 0.37f, w * 0.80f, base * 0.58f)
        cubicTo(w * 0.96f, base * 0.60f, w * 0.97f, base, w * 0.82f, base)
        lineTo(w * 0.16f, base)
        close()
    }
    drawPath(cloud, color, style = stroke)
}

private fun DrawScope.drawSunGlyph(color: Color, stroke: Stroke) {
    val center = Offset(size.width * 0.50f, size.height * 0.50f)
    val radius = size.minDimension * 0.20f
    drawCircle(color, radius, center, style = stroke)
    val rays = listOf(
        floatArrayOf(0f, -1f), floatArrayOf(0.71f, -0.71f),
        floatArrayOf(1f, 0f), floatArrayOf(0.71f, 0.71f),
        floatArrayOf(0f, 1f), floatArrayOf(-0.71f, 0.71f),
        floatArrayOf(-1f, 0f), floatArrayOf(-0.71f, -0.71f),
    )
    rays.forEach { direction ->
        val inner = radius * 1.45f
        val outer = radius * 1.95f
        drawLine(
            color = color,
            start = Offset(center.x + direction[0] * inner, center.y + direction[1] * inner),
            end = Offset(center.x + direction[0] * outer, center.y + direction[1] * outer),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawFogGlyph(color: Color, lineWidth: Float) {
    val lines = listOf(
        Triple(0.18f, 0.82f, 0.28f),
        Triple(0.08f, 0.70f, 0.52f),
        Triple(0.28f, 0.92f, 0.76f),
    )
    lines.forEach { (startX, endX, y) ->
        drawLine(
            color,
            Offset(size.width * startX, size.height * y),
            Offset(size.width * endX, size.height * y),
            lineWidth,
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawWindGlyph(color: Color, stroke: Stroke) {
    val paths = listOf(
        Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.28f)
            cubicTo(size.width * 0.30f, size.height * 0.18f, size.width * 0.56f, size.height * 0.42f,
                size.width * 0.88f, size.height * 0.24f)
        },
        Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.52f)
            cubicTo(size.width * 0.38f, size.height * 0.42f, size.width * 0.62f, size.height * 0.66f,
                size.width * 0.94f, size.height * 0.48f)
        },
        Path().apply {
            moveTo(size.width * 0.06f, size.height * 0.76f)
            cubicTo(size.width * 0.28f, size.height * 0.67f, size.width * 0.48f, size.height * 0.85f,
                size.width * 0.72f, size.height * 0.72f)
        },
    )
    paths.forEach { drawPath(it, color, style = stroke) }
}

private fun DrawScope.drawSnowflakeGlyph(center: Offset, color: Color, lineWidth: Float) {
    val radius = size.minDimension * 0.09f
    listOf(
        Offset(0f, radius),
        Offset(radius * 0.87f, radius * 0.50f),
        Offset(radius * 0.87f, -radius * 0.50f),
    ).forEach { delta ->
        drawLine(
            color,
            Offset(center.x - delta.x, center.y - delta.y),
            Offset(center.x + delta.x, center.y + delta.y),
            lineWidth,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun Footer(
    locationLabel: String,
    canUpgrade: Boolean,
    actions: AppActions,
    onOpenCities: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenPaywall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 위치는 곧 "어디의 하늘인가" 라서 화면에 남기고, 누르면 바꿀 수 있게 한다.
        FooterLink(locationLabel.ifBlank { "Choose a place" }, onOpenCities)
        actions.onAddWidget?.let { FooterLink("Widget", it) }
        // 살 수 있을 때만 보인다. 이미 다 가진 유저에게 파는 화면을 띄우지 않는다.
        if (canUpgrade) FooterLink("The shelf", onOpenPaywall)
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
private fun AboutScreen(onBack: () -> Unit, onOpenPrivacy: (() -> Unit)?) {
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
            "Crimson Text, licensed under the SIL Open Font License 1.1.",
        )
        onOpenPrivacy?.let {
            Text(
                "Privacy policy",
                fontSize = 15.sp,
                fontFamily = Serif,
                color = Muted,
                modifier = Modifier.padding(bottom = 48.dp).clickable(onClick = it),
            )
        }
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

/**
 * 본문 서체. Crimson Text (SIL OFL).
 *
 * 위젯과 달리 앱 화면은 폰트 제약이 없어 그냥 쓰면 된다.
 * Android 위젯 히어로가 같은 폰트를 비트맵으로 굽고 있어 네 표면이 통일된다.
 */
private val Serif: FontFamily
    @Composable get() = FontFamily(Font(Res.font.crimson_text))
private val Paper = Color(0xFFFBF9F4)
private val Ink = Color(0xFF1A1A1A)
private val Muted = Color(0xFF8A8378)

// ---------------------------------------------------------------------------
// 페이월
// ---------------------------------------------------------------------------

/**
 * 유료 전환 화면.
 *
 * 파는 것은 **기능이 아니라 서가의 크기**다. 무료 유저도 위젯을 포함해 앱 전체를
 * 쓰고, 다만 문장이 적어 같은 것을 다시 만나게 된다. 그 반복이 전환 동기이므로
 * 여기서 다시 설득할 필요가 없다 — 조용히 무엇을 얻는지만 적는다.
 *
 * **기간을 약속하지 않는다.** "한 달 분량" 같은 문구는 날씨가 단조로운 지역
 * 유저에게 배신이 된다 (같은 값을 내고 2주 만에 소진한다).
 * 검증 가능한 것(컬렉션의 크기)만 말한다.
 */
@Composable
private fun PaywallScreen(
    products: List<BillingProduct>,
    onPurchase: (BillingProduct) -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        ScreenHeader("The shelf", onBack)

        Section(
            "What you have",
            "A starter shelf. Every weather is covered, so the app always has " +
                "something to show you — but the shelf is small, and you will " +
                "start meeting the same passages again.",
        )

        if (products.isEmpty()) {
            // 결제 미설정·오프라인. 깨진 화면 대신 사실만 적는다.
            Section(
                "Not available right now",
                "The store could not be reached. Your free passages are unaffected.",
            )
        } else {
            products.forEach { product ->
                Column(modifier = Modifier.padding(bottom = 28.dp)) {
                    Text(product.title, fontSize = 20.sp, fontFamily = Serif, color = Ink)
                    Text(
                        product.description,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        fontFamily = Serif,
                        color = Ink,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = product.displayPrice?.let { "$it · one time" } ?: "one time",
                        fontSize = 13.sp,
                        fontFamily = Serif,
                        color = Muted,
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .clickable { onPurchase(product) },
                    )
                }
            }
        }

        // 스토어 정책상 반드시 있어야 한다. 기기를 바꾼 유저의 유일한 출구이기도 하다.
        Text(
            text = "Restore a previous purchase",
            fontSize = 13.sp,
            fontFamily = Serif,
            color = Muted,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 48.dp)
                .clickable(onClick = onRestore),
        )
    }
}
