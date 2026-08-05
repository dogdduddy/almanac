package com.dogdduddy.almanac.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dogdduddy.almanac.R

/**
 * # Glance 커스텀 폰트 스파이크
 *
 * 질문: 번들 .ttf 를 Glance 위젯 텍스트에 적용할 수 있는가?
 *
 * 같은 문장을 5가지 경로로 동시에 렌더해 육안으로 판정한다.
 * 결론이 문서화되면 이 파일과 관련 리소스는 통째로 삭제한다.
 *
 * 소스 레벨 근거(가설):
 * `androidx.glance.appwidget.translators.TextTranslator` 는
 * `style.fontFamily?.let { spans.add(TypefaceSpan(it.family)) }` 로 처리한다.
 * `TypefaceSpan(String)` 은 **시스템 폰트 패밀리 이름**만 해석하므로
 * 앱에 번들된 res/font 리소스는 이 경로로 도달할 수 없다.
 *
 * 변형 M(Monospace)은 대조군이다. M 이 제대로 등폭으로 나오면
 * "fontFamily 배관 자체는 동작한다" 가 증명되므로,
 * A 의 실패는 배관 문제가 아니라 **커스텀 폰트가 도달 불가**라는 뜻이 된다.
 */
class FontSpikeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { SpikeContent() }
    }
}

/**
 * 위젯 본체와 in-process 렌더링(SpikeActivity)이 **같은 컴포지션**을 쓰도록 최상위로 뺐다.
 * 두 경로가 다른 코드를 타면 비교의 의미가 없다.
 */
@Composable
fun SpikeContent() {
    val context = LocalContext.current
    val size = LocalSize.current
    val sample = context.getString(R.string.spike_sample_text)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color.White)
            .padding(8.dp)
    ) {
        SpikeLabel("A  Glance FontFamily(\"crimson_text\")  ← 커스텀 시도")
        SpikeText(sample, FontFamily("crimson_text"))

        SpikeLabel("B  Glance FontFamily.Serif  ← 시스템 serif")
        SpikeText(sample, FontFamily.Serif)

        SpikeLabel("M  Glance FontFamily.Monospace  ← 배관 대조군")
        SpikeText(sample, FontFamily.Monospace)

        SpikeLabel("C  AndroidRemoteViews + @font/crimson_text")
        AndroidRemoteViews(
            remoteViews = RemoteViews(context.packageName, R.layout.spike_remote_text).apply {
                setTextViewText(R.id.spike_text, sample)
            },
            modifier = GlanceModifier.fillMaxWidth(),
        )

        SpikeLabel("D  Bitmap + Typeface  ← Crimson 실물 기준")
        val widthPx = ((size.width.value - 16) * context.resources.displayMetrics.density).toInt()
        val bitmap = runCatching {
            renderTextToBitmap(
                context = context,
                text = sample,
                widthPx = widthPx,
                textSizePx = 17f * context.resources.displayMetrics.density,
                fontResId = R.font.crimson_text,
            )
        }
        // 실제 위젯 호스트에서 D 가 통째로 사라졌다. 원인을 위젯 표면에 그대로 띄운다.
        bitmap.fold(
            onSuccess = {
                Image(
                    provider = ImageProvider(it),
                    contentDescription = sample,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            },
            onFailure = {
                SpikeText("D FAILED: ${it::class.simpleName} ${it.message}", FontFamily.Monospace)
            },
        )
        SpikeLabel("D size=${size.width.value}x${size.height.value}dp widthPx=$widthPx ok=${bitmap.isSuccess}")
    }
}

@Composable
private fun SpikeText(text: String, family: FontFamily) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 17.sp,
            color = ColorProvider(Color.Black),
            fontFamily = family,
        ),
        maxLines = 1,
    )
}

@Composable
private fun SpikeLabel(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 9.sp, color = ColorProvider(Color(0xFF999999))),
        maxLines = 1,
        modifier = GlanceModifier.padding(top = 5.dp),
    )
}

/**
 * 변형 D 의 구현. 커스텀 Typeface 로 텍스트를 비트맵에 그려 넣는다.
 *
 * 주의: RemoteViews 는 비트맵을 바인더로 넘기므로 트랜잭션 크기 제한(~1MB)에 걸린다.
 * 실제 도입 시 문장 길이 × 위젯 폭 조합에서 비트맵 용량을 반드시 측정해야 한다.
 */
private fun renderTextToBitmap(
    context: Context,
    text: String,
    widthPx: Int,
    textSizePx: Float,
    fontResId: Int,
): Bitmap {
    val typeface = ResourcesCompat.getFont(context, fontResId) ?: Typeface.SERIF
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.textSize = textSizePx
        this.color = android.graphics.Color.BLACK
    }
    val metrics = paint.fontMetrics
    val height = (metrics.bottom - metrics.top).toInt().coerceAtLeast(1)
    val width = widthPx.coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawText(text, 0f, -metrics.top, paint)
    return bitmap
}

class FontSpikeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FontSpikeWidget()
}
