package com.dogdduddy.almanac.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.TypefaceSpan
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import com.dogdduddy.almanac.R

/**
 * 스파이크 3단계 — 외부 검토에서 지적된 **미검증 경로**를 실제 런처에서 확인한다.
 *
 * 1·2단계에서 실패한 것: Glance `FontFamily`, `AndroidRemoteViews`+`@font/`,
 * 클래식 RemoteViews 레이아웃의 `android:fontFamily="@font/..."`.
 * 성공한 것: Canvas 비트맵.
 *
 * 아직 안 해본 것이 두 개 남아 있었다.
 *
 * - **S**: API 28+ 의 `TypefaceSpan(Typeface)` 를 `SpannableString` 에 담아 `setTextViewText` 로 전달.
 *   쟁점은 `TypefaceSpan` 이 `ParcelableSpan` 이라는 점이다. 파셀에 무엇이 실려 건너가는지에 따라
 *   런처 프로세스에서 Typeface 가 살아남을 수도, 문자열 family 만 남을 수도 있다.
 * - **T**: `setTextViewTextAppearance` 로 `@font` 를 품은 style 을 지정.
 *
 * Q(기본)와 R(비트맵)이 각각 실패/성공의 시각적 기준선이다.
 */
class ClassicFontWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val sample = context.getString(R.string.spike_sample_text)
        val typeface = ResourcesCompat.getFont(context, R.font.crimson_text)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.classic_font_widget)
            val report = StringBuilder()
            report.append("SDK ${Build.VERSION.SDK_INT} · typeface=${if (typeface != null) "loaded" else "NULL"}\n")

            // ---- S. SpannableString + TypefaceSpan(Typeface) ----
            views.setTextViewText(R.id.label_s, "S  SpannableString + TypefaceSpan(Typeface)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && typeface != null) {
                val spanned = SpannableString(sample).apply {
                    setSpan(TypefaceSpan(typeface), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                views.setTextViewText(R.id.text_span, spanned)
                report.append("S  span 적용됨 (파셀 통과 여부는 화면으로 판정)\n")
            } else {
                views.setTextViewText(R.id.text_span, "$sample (SKIPPED)")
                report.append("S  건너뜀\n")
            }

            // ---- T. setTextAppearance 경로 — 검증 완료: 불가능 ----
            // views.setInt(id, "setTextAppearance", styleRes) 는 런처에서 다음으로 터진다:
            //   RemoteViews$ActionException: view: android.widget.TextView
            //     can't use method with RemoteViews: setTextAppearance(int)
            // 게다가 예외가 위젯 전체를 죽여 "Can't load widget" 이 뜬다. 되돌릴 수 없으니 호출하지 않는다.
            views.setTextViewText(R.id.label_t, "T  setTextAppearance → ActionException (위젯 전체 사망)")
            views.setTextViewText(R.id.text_appearance, "$sample  ← 스타일 미적용")

            // ---- Q. 기준선 ----
            views.setTextViewText(R.id.label_q, "Q  기본 폰트  ← 실패가 어떻게 보이는지")
            views.setTextViewText(R.id.text_default_font, sample)

            // ---- R. 비트맵 (성공 기준선) ----
            views.setTextViewText(R.id.label_r, "R  Bitmap + Typeface  ← 성공이 어떻게 보이는지")
            runCatching {
                val bitmap = renderCropped(context, sample, 17f * context.resources.displayMetrics.density)
                views.setImageViewBitmap(R.id.image_bitmap, bitmap)
                report.append("R  ${bitmap.width}x${bitmap.height} ${bitmap.byteCount / 1024}KB")
            }.onFailure { report.append("R  FAILED: ${it.message}") }

            views.setTextViewText(R.id.label_diag, report.toString())
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

private fun renderCropped(context: Context, text: String, textSizePx: Float): Bitmap {
    val typeface = ResourcesCompat.getFont(context, R.font.crimson_text) ?: Typeface.SERIF
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.textSize = textSizePx
        this.color = android.graphics.Color.BLACK
    }
    val layout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, 2000)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()

    val width = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }.toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    layout.draw(Canvas(bitmap))
    return bitmap
}
