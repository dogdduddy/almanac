package com.dogdduddy.almanac

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.location.LocationMode
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 날씨별 등장 인터랙션의 프레임 시트.
 *
 * 창을 띄우지 않고 등장 시계를 정해진 시각으로 밀어 프레임을 뽑는다. 튜닝할 때
 * 폰 빌드 없이 여덟 날씨의 궤적을 한 장에서 비교하기 위한 것이다.
 * 결과: build/reports/render/entrance-sheet.png (전체), entrance-<날씨>.png (날씨별).
 *
 * 검증하는 것은 하나뿐이다 — **끝 프레임(t=1)은 정지 화면과 같아야 한다.**
 * 등장이 끝난 뒤 단어가 어긋나 있거나 흐려 있으면 안 된다.
 */
class EntranceFramesRenderTest {

    private val moments = listOf(0.08f, 0.2f, 0.35f, 0.55f, 0.8f, 1f)
    private val width = 440
    private val height = 470

    @Test
    fun renderEntranceSheet() {
        val outDir = File("build/reports/render").apply { mkdirs() }
        val rendered = WeatherGroup.entries.map { group -> group to renderFrames(group) }
        val rows = rendered.map { (group, frames) -> group to frames.dropLast(1) }

        val sheet = BufferedImage(width * moments.size, height * rows.size, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        rows.forEachIndexed { row, (group, frames) ->
            val strip = BufferedImage(width * frames.size, height, BufferedImage.TYPE_INT_RGB)
            val sg = strip.createGraphics()
            frames.forEachIndexed { col, frame ->
                g.drawImage(frame, col * width, row * height, null)
                sg.drawImage(frame, col * width, 0, null)
            }
            sg.dispose()
            ImageIO.write(strip, "png", File(outDir, "entrance-${group.key}.png"))
        }
        g.dispose()
        ImageIO.write(sheet, "png", File(outDir, "entrance-sheet.png"))

        // t=1 프레임은 한참 뒤(3배 길이)의 정지 화면과 같아야 한다. 다르면 시계가 밀렸거나
        // 궤적이 1 에서 제자리로 돌아오지 않는 것이다.
        rendered.forEach { (group, frames) ->
            val atOne = inkPixels(frames[frames.size - 2])
            val settled = inkPixels(frames.last())
            val ratio = atOne.toFloat() / settled
            assertTrue(ratio in 0.98f..1.02f, "${group.key} 의 t=1 프레임이 정지 화면과 다르다: 잉크 $atOne vs $settled")
        }
    }

    private fun renderFrames(group: WeatherGroup): List<BufferedImage> =
        ImageComposeScene(width = width, height = height, density = Density(1f)).use { scene ->
            scene.setContent { App(readyState(group)) }
            // 프레임 0 에서 컴포지션, 1 에서 LaunchedEffect 가 animateTo 를 부르고, 2 가 시작 시각이 된다.
            val base = 2L
            repeat(3) { scene.render(it.toLong()) }
            val duration = entranceDurationMs(group) * 1_000_000L
            val frames = moments.map { t -> scene.frameAt(base + (t * duration).toLong()) }
            // 마지막 원소는 검사용 정지 화면. 시트에는 싣지 않는다.
            frames + scene.frameAt(base + 3 * duration)
        }

    private fun readyState(group: WeatherGroup) = AppState.Ready(
        pages = listOf(
            TodaysPage(
                yearsAgo = 172,
                text = "In the midst of a gentle rain while these thoughts prevailed, I was suddenly " +
                    "sensible of such sweet and beneficent society in Nature, in the very pattering " +
                    "of the drops, and in every sound and sight around my house.",
                author = "Henry David Thoreau",
                title = "Walden",
                section = null,
                year = 1854,
                weatherGroup = group,
                temperatureC = 18.0,
                timeOfDay = TimeOfDay.DAY,
                locationLabel = "Seoul",
                dateKey = "2026-09-16",
                isToday = true,
            )
        ),
        locationLabel = "Seoul",
        locationMode = LocationMode.DEFAULT,
    )

    /**
     * 시각 [nanos] 의 그림. **두 번 그린다.** 첫 render 가 시계를 밀어 상태를 바꾸면
     * graphicsLayer 람다는 무효화만 되고, 실제 픽셀은 다음 render 에서 나온다.
     * 한 번만 그리면 모든 프레임이 한 순간씩 늦게 보여 t=1 이 아직 움직이는 중으로 찍힌다.
     */
    private fun ImageComposeScene.frameAt(nanos: Long): BufferedImage {
        render(nanos)
        return render(nanos + 1).toBuffered()
    }

    private fun org.jetbrains.skia.Image.toBuffered(): BufferedImage =
        ImageIO.read(ByteArrayInputStream(checkNotNull(encodeToData(EncodedImageFormat.PNG)).bytes))

    private fun inkPixels(image: BufferedImage): Int {
        var ink = 0
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
            val rgb = image.getRGB(x, y)
            val luminance = ((rgb shr 16 and 0xff) + (rgb shr 8 and 0xff) + (rgb and 0xff)) / 3
            if (luminance < 96) ink++
        }
        return ink
    }
}
