package com.dogdduddy.almanac.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.dogdduddy.almanac.R

/**
 * 히어로 문구를 커스텀 폰트로 그린 비트맵.
 *
 * 스파이크 결론(docs/spikes/glance-custom-font.md): **위젯 텍스트에 번들 폰트를
 * 적용하는 방법은 비트맵뿐이다.** Glance 의 FontFamily, RemoteViews 레이아웃의
 * android:fontFamily, TypefaceSpan(Typeface) 모두 런처에서 기본 폰트로 떨어진다.
 *
 * 그래서 **히어로 한 줄만** 비트맵으로 만든다. 본문까지 이미지로 만들면
 * 1MB 를 넘고 접근성 폰트 스케일도 잃는다. 히어로만이면 200KB 안쪽이다.
 *
 * 폭은 위젯 폭이 아니라 **실제 글자 폭**으로 자른다 — 실측에서 이것만으로 절반이 줄었다.
 */
internal fun renderHeroBitmap(
    context: Context,
    text: String,
    maxWidthPx: Int,
    textSizePx: Float,
    color: Int,
): Bitmap {
    val typeface = ResourcesCompat.getFont(context, R.font.crimson_text) ?: Typeface.SERIF
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.textSize = textSizePx
        this.color = color
    }

    val bounded = maxWidthPx.coerceAtLeast(1)
    val layout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, bounded)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setMaxLines(2)
        .build()

    val width = (0 until layout.lineCount)
        .maxOf { layout.getLineWidth(it) }
        .toInt()
        .coerceIn(1, bounded)

    val bitmap = Bitmap.createBitmap(width, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    layout.draw(Canvas(bitmap))
    return bitmap
}
