package com.dogdduddy.almanac.demo

import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.weather.Conditions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 촬영용 고정.
 *
 * **여기서 지키는 것은 하나다 — 고정하지 않은 것은 건드리지 않는다.**
 * 촬영 도구가 평소 경로를 바꾸면 영상에 찍히는 것이 실제 앱이 아니게 된다.
 */
class DemoControlsTest {

    private val measured = Conditions(
        locationKey = "37.5665,126.9780",
        dateKey = "2026-08-28",
        timeOfDay = TimeOfDay.DAY,
        weatherGroup = WeatherGroup.CLEAR,
        temperatureC = 24.0,
        windFlag = false,
        servedFromCache = false,
    )

    @Test
    fun `아무것도 고정하지 않으면 받은 조건 그대로다`() {
        val controls = DemoControls()

        assertFalse(controls.isActive)
        assertSame(measured, controls.override(measured), "손대지 않았으면 객체까지 그대로여야 한다")
    }

    @Test
    fun `고정한 것만 덮어쓴다`() {
        val controls = DemoControls().apply { weatherGroup = WeatherGroup.SNOW }

        val result = controls.override(measured)

        assertEquals(WeatherGroup.SNOW, result.weatherGroup)
        assertEquals(TimeOfDay.DAY, result.timeOfDay, "시간대는 고정하지 않았다")
        assertEquals("2026-08-28", result.dateKey, "날짜는 고정 대상이 아니다")
        assertEquals("37.5665,126.9780", result.locationKey)
    }

    /**
     * 날씨만 바꾸면 눈 아이콘 옆에 24° 가 남는다. 영상에서 바로 보이는 모순이므로
     * 화면에 함께 나가는 값도 같이 옮길 수 있어야 한다.
     */
    @Test
    fun `기온도 따로 고정할 수 있다`() {
        val controls = DemoControls().apply {
            weatherGroup = WeatherGroup.SNOW
            temperatureC = DemoControls.defaultTemperature(WeatherGroup.SNOW)
        }

        assertEquals(-3.0, controls.override(measured).temperatureC)
    }

    @Test
    fun `시드를 고정하지 않으면 실제 설치 ID 를 쓴다`() {
        val controls = DemoControls()

        assertEquals("real-install-id", controls.installIdOr("real-install-id"))
    }

    @Test
    fun `시드를 고정하면 실제 설치 ID 를 가린다`() {
        val controls = DemoControls().apply { installId = DemoControls.SHARED_SEED_INSTALL_ID }

        assertEquals(
            DemoControls.SHARED_SEED_INSTALL_ID,
            controls.installIdOr("real-install-id"),
        )
    }

    /** 촬영이 끝나면 한 번에 풀 수 있어야 한다. 하나씩 되돌리게 두면 반드시 하나가 남는다. */
    @Test
    fun `한 번에 전부 푼다`() {
        val controls = DemoControls().apply {
            weatherGroup = WeatherGroup.RAIN
            timeOfDay = TimeOfDay.MORNING
            temperatureC = 14.0
            installId = DemoControls.SHARED_SEED_INSTALL_ID
        }
        assertTrue(controls.isActive)

        controls.clear()

        assertFalse(controls.isActive)
        assertSame(measured, controls.override(measured))
    }

    /** 고정용 설치 ID 도 결정론 계약 1.2 의 형식을 따라야 한다 — 소문자 UUID 모양. */
    @Test
    fun `공용 시드는 설치 ID 형식이다`() {
        val id = DemoControls.SHARED_SEED_INSTALL_ID

        assertEquals(id.lowercase(), id, "대소문자가 섞이면 시드가 갈린다")
        assertEquals(36, id.length)
        assertEquals(listOf(8, 4, 4, 4, 12), id.split("-").map { it.length })
    }
}
