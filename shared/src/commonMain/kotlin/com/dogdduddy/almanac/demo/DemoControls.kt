package com.dogdduddy.almanac.demo

import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.weather.Conditions

/**
 * 촬영용 조건 고정.
 *
 * 데모 영상은 날씨를 기다릴 수 없다. 눈·안개·뇌우 컷을 실제 날씨로만 찍으려면
 * 계절을 두 번 넘겨야 하고, 그 사이 대회는 끝난다.
 *
 * 세 가지를 고정한다.
 * - **날씨** — 비/눈/안개/바람/맑은 밤 몽타주를 한자리에서 찍는다
 * - **시간대** — 새벽 → 낮 → 해질녘 전환을 하루를 기다리지 않고 찍는다
 * - **시드(installId)** — 분할 화면에서 두 기기가 같은 문장을 내야 한다.
 *   시드에 installId 가 들어가므로 기기 두 대는 **기본적으로 다른 문장을 낸다**
 *
 * **알고리즘은 건드리지 않는다.** 여기서 바꾸는 것은 [Conditions] 와 installId,
 * 즉 결정론 엔진의 **입력**뿐이다. 그래서 영상에 찍히는 것은 연출된 화면이 아니라
 * "같은 입력이면 같은 문장" 이라는 실제 계약이다 — 증명하려는 것이 정확히 그것이다.
 *
 * 값은 **메모리에만 산다.** 앱을 다시 띄우면 초기화된다. 고정된 줄 모르고 스크린샷을
 * 찍는 사고를 막는 쪽이, 껐는지 확인하는 부담보다 싸다.
 *
 * 안드로이드 위젯은 앱과 같은 프로세스라 같은 인스턴스를 본다 — 앱에서 비를 고정하면
 * 홈 화면 위젯도 같은 문장으로 갱신된다. **iOS 위젯은 별도 프로세스라 못 본다.**
 * iOS 위젯 컷은 실제 날씨로 찍어야 한다.
 *
 * 진입점은 디버그 빌드에서만 만든다. 이 클래스는 릴리스에도 들어가지만
 * (플랫폼별 소스셋으로 가르면 shared 가 더러워진다) 아무도 값을 넣지 않으면
 * [isActive] 가 false 라서 경로 전체가 죽는다.
 */
class DemoControls {

    /** 고정할 날씨 그룹. null 이면 실제 날씨. */
    var weatherGroup: WeatherGroup? = null

    /** 고정할 시간대. null 이면 일출·일몰로 판정한 실제 시간대. */
    var timeOfDay: TimeOfDay? = null

    /**
     * 화면에 표시할 기온(℃). null 이면 실제 값.
     *
     * 날씨만 고정하면 눈 아이콘 옆에 24° 가 붙는다. 영상에서 바로 보이는 모순이라
     * 날씨와 함께 옮겨간다.
     */
    var temperatureC: Double? = null

    /**
     * 고정할 설치 ID. 두 기기에 같은 값을 넣으면 시드가 같아진다.
     *
     * user.db 의 진짜 installId 는 건드리지 않는다 — 고정을 풀면 원래 문장으로 돌아온다.
     */
    var installId: String? = null

    val isActive: Boolean
        get() = weatherGroup != null || timeOfDay != null ||
            temperatureC != null || installId != null

    fun clear() {
        weatherGroup = null
        timeOfDay = null
        temperatureC = null
        installId = null
    }

    /** 고정한 것만 덮어쓴다. 아무것도 안 고정했으면 받은 그대로 나간다. */
    fun override(conditions: Conditions): Conditions =
        if (!isActive) {
            conditions
        } else {
            conditions.copy(
                timeOfDay = timeOfDay ?: conditions.timeOfDay,
                weatherGroup = weatherGroup ?: conditions.weatherGroup,
                temperatureC = temperatureC ?: conditions.temperatureC,
            )
        }

    /** 시드에 들어갈 설치 ID. 고정하지 않았으면 실제 값 그대로. */
    fun installIdOr(actual: String): String = installId ?: actual

    companion object {
        /**
         * 분할 화면 촬영용 공용 시드.
         *
         * 형식은 실제 installId 와 같아야 한다 — 소문자 UUID (결정론 계약 1.2).
         * 값 자체에는 의미가 없고 **두 기기가 같은 문자열을 쓴다는 것**만이 요구사항이다.
         */
        const val SHARED_SEED_INSTALL_ID: String = "5b17a70e-0000-4000-8000-a1ma4ac0de70"

        /**
         * 날씨별 촬영용 기온(℃).
         *
         * 실제 관측값이 아니라 **그 날씨에 어울리는 값**이다. 화면의 주인공은 문장이고
         * 기온은 보조 정보이므로, 영상에서 모순으로 읽히지만 않으면 된다.
         */
        fun defaultTemperature(group: WeatherGroup): Double = when (group) {
            WeatherGroup.SNOW -> -3.0
            WeatherGroup.FOG -> 7.0
            WeatherGroup.DRIZZLE -> 12.0
            WeatherGroup.RAIN -> 14.0
            WeatherGroup.THUNDER -> 19.0
            WeatherGroup.WIND -> 9.0
            WeatherGroup.CLOUDY -> 16.0
            WeatherGroup.CLEAR -> 21.0
        }
    }
}
