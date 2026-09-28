package com.dogdduddy.almanac.demo

import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 촬영 고정을 실행 옵션으로 받는다.
 *
 * **세 플랫폼이 같은 이름으로 같은 화면을 내야 한다.** 분할 화면(#7)은 기기마다 따로 띄워
 * 같은 문장이 나오는지를 찍는 컷이라, 한쪽만 옵션을 다르게 읽으면 그 컷이 통째로 무너진다.
 */
class DemoLaunchOptionsTest {

    private fun parse(vararg pairs: Pair<String, String>): DemoLaunchOptions {
        val map = pairs.toMap()
        return DemoLaunchOptions.parse { map[it] }
    }

    @Test
    fun `분할 화면 컷을 찍는 옵션을 그대로 읽는다`() {
        val options = parse(
            DemoLaunchOptions.WEATHER to "clear",
            DemoLaunchOptions.TIME to "day",
            DemoLaunchOptions.SEED to "shared",
            DemoLaunchOptions.ARCHIVE to "refill",
            DemoLaunchOptions.TURN to "6000",
        )

        assertEquals(
            DemoLaunchOptions(
                weather = WeatherGroup.CLEAR,
                timeOfDay = TimeOfDay.DAY,
                sharedSeed = true,
                refillArchive = true,
                turnAfterMillis = 6_000,
            ),
            options,
        )
    }

    @Test
    fun `옵션이 없으면 아무것도 고정하지 않는다`() {
        val controls = DemoControls()

        parse().applyTo(controls)

        assertFalse(controls.isActive, "옵션 없이 띄운 촬영 빌드는 평소 앱과 같아야 한다")
    }

    @Test
    fun `모르는 값은 조용히 넘긴다`() {
        val options = parse(
            DemoLaunchOptions.WEATHER to "hail",
            DemoLaunchOptions.TIME to "noon",
            DemoLaunchOptions.SEED to "mine",
            DemoLaunchOptions.TURN to "soon",
        )

        assertEquals(DemoLaunchOptions(), options, "촬영 중에 앱이 안 뜨는 것이 제일 나쁘다")
    }

    @Test
    fun `대소문자는 가리지 않는다`() {
        val options = parse(
            DemoLaunchOptions.WEATHER to "Snow",
            DemoLaunchOptions.TIME to "EVENING_NIGHT",
        )

        assertEquals(WeatherGroup.SNOW, options.weather)
        assertEquals(TimeOfDay.EVENING_NIGHT, options.timeOfDay)
    }

    @Test
    fun `음수 넘김은 넘기지 않는 것으로 본다`() {
        assertNull(parse(DemoLaunchOptions.TURN to "-1").turnAfterMillis)
    }

    @Test
    fun `촬영 메뉴와 같은 값을 넣는다`() {
        val controls = DemoControls()

        parse(
            DemoLaunchOptions.WEATHER to "snow",
            DemoLaunchOptions.SEED to "shared",
        ).applyTo(controls)

        assertEquals(WeatherGroup.SNOW, controls.weatherGroup)
        // 날씨만 고정하면 눈 옆에 실제 기온이 붙는다. 메뉴가 그러듯 기온도 같이 간다.
        assertEquals(DemoControls.defaultTemperature(WeatherGroup.SNOW), controls.temperatureC)
        assertEquals(DemoControls.SHARED_SEED_INSTALL_ID, controls.installId)
        assertNull(controls.timeOfDay, "시간대는 주지 않았다")
    }
}
