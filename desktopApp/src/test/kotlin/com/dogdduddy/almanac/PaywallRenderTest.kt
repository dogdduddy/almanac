package com.dogdduddy.almanac

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.dogdduddy.almanac.billing.BillingProduct
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.location.LocationMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 페이월을 **실제 내비게이션을 타고** 열어서 상태별로 렌더한다.
 *
 * 화면을 직접 부르지 않고 "The shelf" 를 눌러 들어가는 이유가 있다 — 고쳐야 했던 것
 * 중 하나가 바로 그 진입·이탈이었다. 예전에는 구매를 누르는 즉시 페이월이 닫혀서,
 * 성공·취소·실패가 화면에서 전부 같은 일이 됐다.
 *
 * 결과 PNG 는 build/reports/render 에 남는다.
 */
class PaywallRenderTest {

    @Test
    fun paywallStatesRender() {
        val product = BillingProduct(
            packId = "core-2026",
            productId = "com.dogdduddy.almanac.core2026",
            title = "The 2026 Collection",
            description = "Every passage collected so far, in every weather. " +
                "One purchase, no subscription.",
            displayPrice = "₩5,900",
        )

        fun state(purchase: PurchaseState) = AppState.Ready(
            pages = listOf(page),
            locationLabel = "Seoul",
            locationMode = LocationMode.DEFAULT,
            products = listOf(product),
            purchase = purchase,
        )

        ImageComposeScene(width = 880, height = 1720, density = Density(2f)).use { scene ->
            var current by mutableStateOf(state(PurchaseState.Idle))
            scene.setContent { App(current) }

            // 등장 애니메이션이 끝난 정지 화면.
            settle(scene)
            val pages = dump(scene, "paywall-0-pages")

            scene.sendPointerEvent(PointerEventType.Press, SHELF_LINK)
            scene.sendPointerEvent(PointerEventType.Release, SHELF_LINK)
            settle(scene)
            val idle = dump(scene, "paywall-1-idle")

            // 눌러야 할 것이 **면으로** 보이는가. 예전에는 13sp 회색 가격 문자열이
            // 전부여서 이 화면에 solid band 가 하나도 없었다.
            assertTrue(hasSolidButton(idle), "페이월에 구매 버튼이 없다")

            // 읽기 화면에는 그런 면이 없다. 이게 없으면 위 단언은 아무것도 재지 못한다.
            assertFalse(hasSolidButton(pages), "읽기 화면에 버튼 모양의 면이 있으면 판정이 무의미하다")

            // **구매를 눌러도 페이월은 닫히지 않는다.**
            //
            // 여기가 이 테스트의 핵심이다. 예전에는 이 탭 한 번에 `screen = PAGES` 가
            // 같이 실행돼서, 스토어 시트가 뜨기도 전에 페이월이 사라졌다. 결과가
            // 도착할 무렵 화면은 이미 읽기 페이지였고, 그래서 성공·취소·실패가
            // 유저에게 전부 같은 일로 보였다.
            scene.sendPointerEvent(PointerEventType.Press, BUY_BUTTON)
            scene.sendPointerEvent(PointerEventType.Release, BUY_BUTTON)
            settle(scene)
            assertTrue(
                hasSolidButton(dump(scene, "paywall-1b-after-tap")),
                "구매를 누르자마자 페이월이 닫혔다 — 결과를 말할 화면이 남지 않는다",
            )

            for ((name, purchase) in listOf(
                "2-working" to PurchaseState.Working,
                "3-cancelled" to PurchaseState.Cancelled,
                "4-failed" to PurchaseState.Failed("card declined"),
                "5-unlocked" to PurchaseState.Unlocked(342),
                "6-restored" to PurchaseState.Unlocked(342, restored = true),
                "7-nothing" to PurchaseState.NothingToRestore,
            )) {
                current = state(purchase)
                settle(scene)
                val rendered = dump(scene, "paywall-$name")

                // 결과가 와도 페이월은 그대로 있다. 성공 화면은 "Start reading",
                // 나머지는 "Buy" 로 면이 남는다.
                assertTrue(hasSolidButton(rendered), "$name 에서 페이월이 사라졌다")
            }
        }
    }

    /**
     * 화면 어딘가에 **면으로 칠한 버튼**이 있는가.
     *
     * 좌표로 재지 않는 이유는 상태마다 버튼이 다른 높이에 오기 때문이다
     * (성공 화면은 상품 설명이 빠져서 위로 붙는다). 대신 "가로로 꽉 찬 어두운 줄이
     * 연달아 여러 줄" 을 찾는다 — 글자는 이 조건을 만들지 못한다. 페이월을 떠났는지,
     * 누를 것이 텍스트로 남아 있는지를 동시에 잡는다.
     */
    private fun hasSolidButton(png: ByteArray): Boolean {
        val bitmap = ImageIO.read(ByteArrayInputStream(png))
        var run = 0
        for (y in 0 until bitmap.height) {
            var filled = 0
            var total = 0
            for (x in BAND_LEFT until BAND_RIGHT step 4) {
                total++
                val rgb = bitmap.getRGB(x, y)
                val luminance = ((rgb shr 16 and 0xff) + (rgb shr 8 and 0xff) + (rgb and 0xff)) / 3
                if (luminance < FILLED) filled++
            }
            run = if (filled > total * 9 / 10) run + 1 else 0
            if (run >= 30) return true
        }
        return false
    }

    private fun settle(scene: ImageComposeScene) {
        var nanos = 0L
        while (nanos < 3_000_000_000L) {
            nanos += 16_000_000L
            scene.render(nanos)
        }
    }

    private fun dump(scene: ImageComposeScene, name: String): ByteArray {
        val png = checkNotNull(
            scene.render(3_100_000_000L).encodeToData(EncodedImageFormat.PNG)
        ).bytes
        val out = File("build/reports/render/$name.png")
        out.parentFile.mkdirs()
        out.writeBytes(png)
        return png
    }

    private companion object {
        /** 푸터의 "The shelf" 링크. 렌더된 화면에서 잰 값이다. */
        val SHELF_LINK = Offset(202f, 711f)

        /** 버튼을 찾을 가로 구간. 좌우 여백과 둥근 모서리를 피해 잡는다. */
        const val BAND_LEFT = 150
        const val BAND_RIGHT = 730

        /**
         * 종이색이 아니면 칠해진 것으로 본다.
         *
         * 어둡기가 아니라 **칠해졌는가**를 재는 값이다. 활성 버튼은 Ink(≈26) 지만
         * 잠긴 버튼은 Muted(≈130) 이라, 어둡기로 재면 잠긴 버튼을 놓친다.
         * 종이는 ≈249, 결과 알림 블록은 ≈238 이라 둘 다 걸리지 않는다.
         */
        const val FILLED = 200

        /** 구매 버튼의 한가운데. 렌더된 화면에서 잰 값이다. */
        val BUY_BUTTON = Offset(440f, 751f)

        val page = TodaysPage(
            yearsAgo = 172,
            text = "In the midst of a gentle rain while these thoughts prevailed, I was " +
                "suddenly sensible of such sweet and beneficent society in Nature.",
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
    }
}
