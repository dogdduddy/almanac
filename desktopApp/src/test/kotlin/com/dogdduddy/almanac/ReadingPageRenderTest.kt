package com.dogdduddy.almanac

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.location.LocationMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 데스크톱이 읽기 화면을 **실제로 그리는지** 본다. 창 없이 오프스크린으로 렌더한다.
 *
 * 지키려는 것은 Android 의 verifyComposeFont 와 같다 — 공유 폰트 리소스가 이 타깃에서
 * 조용히 빠지거나, 화면이 빈 종이로 남는 것. 결과 PNG 는 build/reports/render 에 남겨
 * 눈으로도 볼 수 있게 한다.
 */
class ReadingPageRenderTest {

    @Test
    fun readingPageIsNotBlankPaper() {
        val page = TodaysPage(
            yearsAgo = 172,
            text = "In the midst of a gentle rain while these thoughts prevailed, I was suddenly " +
                "sensible of such sweet and beneficent society in Nature.",
            author = "Henry David Thoreau",
            title = "Walden",
            section = null,
            year = 1854,
            weatherGroup = WeatherGroup.RAIN,
            temperatureC = 24.0,
            timeOfDay = TimeOfDay.DAY,
            locationLabel = "Seoul",
            dateKey = "2026-09-16",
            isToday = true,
        )
        val state = AppState.Ready(
            pages = listOf(page),
            locationLabel = "Seoul",
            locationMode = LocationMode.DEFAULT,
        )

        val png = ImageComposeScene(width = 880, height = 1720, density = Density(2f)).use { scene ->
            scene.setContent { App(state) }
            // 문장이 날씨처럼 등장하는 데 최대 2.3초가 걸린다. 그 뒤의 정지 화면을 본다.
            var image = scene.render(0L)
            var nanos = 0L
            while (nanos < 3_000_000_000L) {
                nanos += 16_000_000L
                image = scene.render(nanos)
            }
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        }

        val out = File("build/reports/render/reading.png")
        out.parentFile.mkdirs()
        out.writeBytes(png)

        val bitmap = ImageIO.read(ByteArrayInputStream(png))
        var ink = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val rgb = bitmap.getRGB(x, y)
                val luminance = ((rgb shr 16 and 0xff) + (rgb shr 8 and 0xff) + (rgb and 0xff)) / 3
                if (luminance < 96) ink++
            }
        }
        // 종이색만 있으면 0 이다. 44sp 히어로와 본문이 그려졌다면 표본의 0.5% 는 잉크다.
        val sampled = (bitmap.height / 4) * (bitmap.width / 4)
        assertTrue(ink > sampled / 200, "잉크 픽셀이 너무 적다: $ink / $sampled — 화면이 비었거나 폰트가 빠졌다 (${out.absolutePath})")
    }
}
