package com.dogdduddy.almanac.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dogdduddy.almanac.AlmanacGraph
import com.dogdduddy.almanac.MainActivity
import com.dogdduddy.almanac.PageResult
import com.dogdduddy.almanac.PageUnavailable
import com.dogdduddy.almanac.R
import com.dogdduddy.almanac.TodaysPage
import com.dogdduddy.almanac.core.WeatherGroup

/**
 * 홈 화면 위젯.
 *
 * 설계 제약 두 가지가 스파이크에서 나왔다 (docs/spikes/glance-custom-font.md).
 *
 * 1. **커스텀 폰트는 비트맵으로만 들어간다.** 그래서 히어로("178 years ago")만
 *    비트맵이고 나머지는 시스템 serif 다. 본문까지 이미지로 만들면 1MB 를 넘고
 *    접근성 폰트 스케일도 잃는다.
 * 2. **Column 은 자식 10개를 넘으면 조용히 잘라낸다.** 크래시도 표시도 없이
 *    요소가 사라진다. 아래 구성은 6개다.
 *
 * 위젯은 측위를 기다릴 수 없으므로 저장된 위치만 쓴다 (`refreshLocation = false`).
 */
class AlmanacWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val result = runCatching {
            AlmanacGraph.service(context).todaysPage(refreshLocation = false)
        }.getOrElse {
            // 위젯은 예외로 죽으면 안 된다. 홈 화면에 "Can't load widget" 이 박힌다.
            PageResult.Unavailable(PageUnavailable.NO_WEATHER)
        }

        provideContent {
            when (result) {
                is PageResult.Ready -> PageContent(result.page)
                is PageResult.Unavailable -> EmptyContent(result.reason)
            }
        }
    }

    @Composable
    private fun PageContent(page: TodaysPage) {
        val context = LocalContext.current
        val size = LocalSize.current
        val widthPx = ((size.width.value - 2 * PADDING_DP) * context.resources.displayMetrics.density)
            .toInt()

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(PAPER)
                .padding(PADDING_DP.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            // 주인공은 문장이 아니라 시간적 거리다.
            Image(
                provider = ImageProvider(
                    renderHeroBitmap(
                        context = context,
                        text = context.getString(R.string.years_ago, page.yearsAgo),
                        maxWidthPx = widthPx,
                        textSizePx = HERO_SP * context.resources.displayMetrics.density,
                        color = INK.toArgb(),
                    )
                ),
                contentDescription = context.getString(R.string.years_ago, page.yearsAgo),
            )

            Text(
                text = context.getString(page.weatherGroup.phraseRes()),
                style = TextStyle(
                    fontSize = 12.sp,
                    color = ColorProvider(MUTED),
                    fontFamily = FontFamily.Serif,
                ),
                maxLines = 1,
            )

            Spacer(GlanceModifier.height(10.dp))

            Text(
                text = page.text,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = ColorProvider(INK),
                    fontFamily = FontFamily.Serif,
                ),
                maxLines = 6,
            )

            Spacer(GlanceModifier.height(8.dp))

            Text(
                text = page.attribution,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = ColorProvider(MUTED),
                    fontFamily = FontFamily.Serif,
                ),
                maxLines = 2,
            )
        }
    }

    @Composable
    private fun EmptyContent(reason: PageUnavailable) {
        val context = LocalContext.current
        val message = when (reason) {
            PageUnavailable.NO_WEATHER -> R.string.widget_no_weather
            PageUnavailable.NO_CONTENT -> R.string.widget_no_content
        }
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(PAPER)
                .padding(PADDING_DP.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = context.getString(message),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = ColorProvider(MUTED),
                    fontFamily = FontFamily.Serif,
                ),
                modifier = GlanceModifier.fillMaxWidth(),
            )
        }
    }

    private companion object {
        const val PADDING_DP = 14
        const val HERO_SP = 30f

        /** 종이 느낌의 크림색. 위젯은 배경이 불투명해야 글자가 읽힌다. */
        val PAPER = Color(0xFFFBF9F4)
        val INK = Color(0xFF1A1A1A)
        val MUTED = Color(0xFF8A8378)
    }
}

private fun WeatherGroup.phraseRes(): Int = when (this) {
    WeatherGroup.CLEAR -> R.string.phrase_clear
    WeatherGroup.CLOUDY -> R.string.phrase_cloudy
    WeatherGroup.FOG -> R.string.phrase_fog
    WeatherGroup.DRIZZLE -> R.string.phrase_drizzle
    WeatherGroup.RAIN -> R.string.phrase_rain
    WeatherGroup.SNOW -> R.string.phrase_snow
    WeatherGroup.THUNDER -> R.string.phrase_thunder
    WeatherGroup.WIND -> R.string.phrase_wind
}

class AlmanacWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AlmanacWidget()
}
