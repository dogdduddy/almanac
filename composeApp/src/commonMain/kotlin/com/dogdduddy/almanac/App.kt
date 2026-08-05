package com.dogdduddy.almanac

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dogdduddy.almanac.core.PageRequest
import com.dogdduddy.almanac.core.PageSelector
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup

/**
 * 임시 화면. 현재 목적은 단 하나 — `shared` 의 결정론 엔진이 CMP 에서 호출되고,
 * Android/iOS 양쪽에서 **같은 값**이 찍히는지 눈으로 확인하는 것.
 *
 * 실제 UI 위계(시간적 거리를 주인공으로)는 위젯 스파이크 결론 후에 만든다.
 */
@Composable
fun App() {
    MaterialTheme {
        val request = PageRequest(
            dateKey = "2026-08-04",
            timeOfDay = TimeOfDay.MORNING,
            weatherGroup = WeatherGroup.RAIN,
            installId = "dev-install",
        )
        val selection = PageSelector.select(request, candidateIds = (1L..5L).toList())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFBF9F4))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "seed  ${selection?.seed}",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "entryId  ${selection?.entryId}",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "bucket  ${selection?.bucket?.key}",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
