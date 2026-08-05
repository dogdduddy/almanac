package com.dogdduddy.almanac.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 스파이크 검증용. 두 가지를 한다.
 *
 * 1. `GlanceRemoteViews` 로 위젯과 **동일한 컴포지션**을 앱 프로세스 안에서 렌더 → 스크린샷 비교
 * 2. 버튼으로 실제 런처 홈 화면에 위젯 고정(pin) → 프로세스 경계를 넘은 진짜 조건에서 재확인
 *
 * 1번만으로도 폰트 해석 결과는 같지만(TypefaceSpan 은 문자열만 나름), 스펙이 이걸
 * "일정 리스크"로 규정했으므로 실제 런처에서도 확인한다.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class SpikeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(16, 48, 16, 16)
        }

        root.addView(
            TextView(this).apply {
                text = "Glance font spike — in-process render"
                textSize = 13f
                setTextColor(Color.DKGRAY)
            }
        )

        root.addView(
            Button(this).apply {
                text = "Pin GLANCE widget"
                setOnClickListener { requestPin(FontSpikeWidgetReceiver::class.java) }
            }
        )

        root.addView(
            Button(this).apply {
                text = "Pin CLASSIC widget"
                setOnClickListener { requestPin(ClassicFontWidgetProvider::class.java) }
            }
        )

        val host = FrameLayout(this).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(host)
        setContentView(root)

        scope.launch {
            val result = GlanceRemoteViews().compose(
                context = this@SpikeActivity,
                size = DpSize(330.dp, 300.dp),
            ) { SpikeContent() }
            host.removeAllViews()
            host.addView(result.remoteViews.apply(applicationContext, host))
        }
    }

    private fun requestPin(receiver: Class<*>) {
        val manager = getSystemService(AppWidgetManager::class.java) ?: return
        val provider = ComponentName(this, receiver)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(provider, null, null)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
