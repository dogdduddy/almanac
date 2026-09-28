package com.dogdduddy.almanac.demo

import com.dogdduddy.almanac.AppLoader
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import kotlinx.coroutines.delay

/**
 * 촬영 고정을 **실행 옵션으로** 받는다. 세 플랫폼이 같은 이름을 쓴다.
 *
 *   데스크톱  ./gradlew :desktopApp:run -Palmanac.demo=true -Palmanac.demo.weather=clear …
 *   iOS      xcrun simctl launch booted com.dogdduddy.almanac -almanac.demo.weather clear …
 *   Android  adb shell am start -n com.dogdduddy.almanac/.MainActivity --es almanac.demo.weather clear …
 *
 * 화면을 누르지 않고 맞추는 길이 있어야 하는 이유는 플랫폼마다 다르다 — `simctl` 에는
 * 탭이 없고, 맥 창을 누르려면 손쉬운 사용 권한이 필요하다. 그리고 셋에 공통으로,
 * **Demo 화면을 아예 열지 않으므로** "한 프레임도 안 들어간다" 가 저절로 지켜진다.
 *
 * 값은 [DemoControls] 와 같이 메모리에만 산다. 옵션 없이 다시 띄우면 전부 풀린다.
 * 옵션을 읽는 것은 촬영 빌드뿐이다 — 스토어 빌드는 [parse] 를 부르지도 않는다.
 */
data class DemoLaunchOptions(
    val weather: WeatherGroup? = null,
    val timeOfDay: TimeOfDay? = null,
    /** 공용 시드. 분할 화면의 기기들이 같은 문장을 내려면 필요하다. */
    val sharedSeed: Boolean = false,
    /** 이력을 비우고 아카이브를 다시 채운다. 넘길 과거가 있어야 넘김 컷이 된다. */
    val refillArchive: Boolean = false,
    /**
     * 채우기가 끝나고 이만큼 뒤에 **한 장 넘긴다** (밀리초).
     *
     * 분할 화면(#7)은 기기마다 따로 녹화해 편집에서 넘김 순간을 맞춘다. 그런데 손으로
     * 넘기면 기기마다 밀어낸 속도가 달라 **넘김 자체의 빠르기가 다르다** — 나란히 놓으면
     * 그게 먼저 보인다. 페이저를 코드로 움직이면 셋이 같은 곡선으로 넘어간다.
     */
    val turnAfterMillis: Long? = null,
) {

    /** 고정값을 넣는다. [AppLoader.start] **전에** 불러야 첫 화면부터 고정된 조건으로 그린다. */
    fun applyTo(demo: DemoControls) {
        weather?.let {
            demo.weatherGroup = it
            // 날씨만 고정하면 눈 아이콘 옆에 24° 가 붙는다. 촬영 메뉴처럼 기온도 같이 옮긴다.
            demo.temperatureC = DemoControls.defaultTemperature(it)
        }
        timeOfDay?.let { demo.timeOfDay = it }
        if (sharedSeed) demo.installId = DemoControls.SHARED_SEED_INSTALL_ID
    }

    /**
     * 시작 뒤에 할 일. [AppLoader.start] **다음에** 부른다.
     *
     * 비우기와 채우기 사이를 기다리는 이유: 비우기는 오늘 페이지를 다시 그려 기록하는데,
     * 그게 끝나기 전에 채우면 오늘 슬롯이 히스토리에 없는 채로 과거를 고른다. 읽음 횟수와
     * 최근 목록이 선택의 입력이라(결정론 계약 2.7) 거기서 기기끼리 갈렸다.
     * 폰에서 사람이 두 줄을 몇 초 간격으로 누를 때는 저절로 지켜지던 것이다.
     */
    suspend fun stage(loader: AppLoader, turn: () -> Unit) {
        if (refillArchive) {
            loader.resetHistory()
            delay(1_500)
            loader.fillArchive()
            delay(2_000)        // 채우는 동안 화면이 자리를 잡게 둔다
        }
        turnAfterMillis?.let {
            delay(it)
            turn()
        }
    }

    companion object {
        const val WEATHER = "almanac.demo.weather"
        const val TIME = "almanac.demo.time"
        const val SEED = "almanac.demo.seed"
        const val ARCHIVE = "almanac.demo.archive"
        const val TURN = "almanac.demo.turn"

        /**
         * [read] 가 이름으로 값을 준다. 없으면 null.
         *
         * **모르는 값은 조용히 넘긴다** — 촬영 중에 앱이 안 뜨는 것이 제일 나쁘다.
         */
        fun parse(read: (String) -> String?): DemoLaunchOptions = DemoLaunchOptions(
            weather = read(WEATHER)?.lowercase()?.let { key ->
                WeatherGroup.entries.firstOrNull { it.key == key }
            },
            timeOfDay = read(TIME)?.lowercase()?.let { key ->
                TimeOfDay.entries.firstOrNull { it.key == key }
            },
            sharedSeed = read(SEED) == "shared",
            refillArchive = read(ARCHIVE) == "refill",
            turnAfterMillis = read(TURN)?.toLongOrNull()?.takeIf { it >= 0 },
        )
    }
}
