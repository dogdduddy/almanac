package com.dogdduddy.almanac

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.dogdduddy.almanac.core.WeatherGroup
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 문장이 날씨처럼 등장한다.
 *
 * 이 앱의 주인공은 문장이다. 배경에 날씨 애니메이션을 깔고 글을 얹으면 글은 여전히
 * 정지해 있다 — 그래서 반대로 한다. **비는 단어가 떨어지고, 안개는 초점이 맞고, 바람은
 * 단어가 옆에서 불려 온다.** 배경은 종이 그대로다. 파티클도 소리도 없다.
 *
 * 한 페이지에 시계는 하나([t], 0→1)다. 히어로·단어·보조 정보·햅틱이 전부 같은
 * 시계를 읽으므로 서로 어긋나지 않고, 프레임마다 재구성되는 것도 없다 — 값은 전부
 * graphicsLayer 람다 안에서 읽는다.
 *
 * **결정론 계약과 무관하다.** 등장 방식은 어떤 문장을 고르느냐에 손대지 않는다.
 * 같은 조건이면 세 플랫폼이 같은 문장을 같은 방식으로 등장시킨다.
 */
@Stable
class WeatherEntrance internal constructor(
    val weatherGroup: WeatherGroup,
    private val progress: Animatable<Float, *>,
) {
    /** 0(아직 아무것도 없음) → 1(다 놓임). */
    val t: Float get() = progress.value

    /** 단어 하나의 자세. 단위는 dp 와 도(°). 호출자가 density 를 곱한다. */
    fun word(index: Int, count: Int): Pose = poseFor(weatherGroup, index, count, t)

    /**
     * "N years ago". 문장보다 먼저, 조금 더 크게 움직인다.
     * 심사 영상의 첫 프레임에 이것이 있어야 하므로 단어들보다 빨리 자리를 잡는다.
     */
    fun hero(): Pose = poseFor(weatherGroup, 0, 1, t).let {
        it.copy(dx = it.dx * 1.4f, dy = it.dy * 1.4f)
    }

    /** 날짜·날씨 줄·출처 같은 보조 정보. 단어들이 대체로 놓인 뒤 조용히 붙는다. */
    fun meta(): Float = easeInOutSine(local(t, 0.62f, 0.38f))

    /**
     * 문장 전체에 거는 흐림(dp). 안개만 쓴다. 단어마다 걸면 레이어 경계에서 잘리므로
     * 컨테이너 한 층에 건다.
     */
    fun blur(): Float = if (weatherGroup == WeatherGroup.FOG) 9f * (1f - easeInOutSine(t)) else 0f

    /**
     * 종이 위를 덮는 흰 섬광의 알파. 뇌우만 쓴다. 두 번 번쩍이고, 두 번째가 약하다.
     * 그 뒤에 단어들이 한꺼번에 놓인다 — 번개 뒤에 사물이 보이는 순서다.
     */
    fun flash(): Float {
        if (weatherGroup != WeatherGroup.THUNDER) return 0f
        val first = pulse(t, THUNDER_FLASH_1.first, THUNDER_FLASH_1.second) * 0.85f
        val second = pulse(t, THUNDER_FLASH_2.first, THUNDER_FLASH_2.second) * 0.45f
        return maxOf(first, second)
    }
}

/** 단어 하나의 자세. dp 단위. */
data class Pose(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val alpha: Float = 1f,
    val rotation: Float = 0f,
)

/** 전체 길이(ms). 날씨마다 다르다 — 눈은 느리고 뇌우는 빠르다. */
fun entranceDurationMs(group: WeatherGroup): Int = when (group) {
    WeatherGroup.CLEAR -> 850
    WeatherGroup.CLOUDY -> 1500
    WeatherGroup.FOG -> 1700
    WeatherGroup.DRIZZLE -> 950
    WeatherGroup.RAIN -> 1150
    WeatherGroup.SNOW -> 2300
    WeatherGroup.THUNDER -> 950
    WeatherGroup.WIND -> 1050
}

/**
 * 페이지 하나의 등장 시계.
 *
 * 시작하는 순간 같은 타임라인의 햅틱도 같이 튼다 — 비의 방울이 놓이는 프레임에 진동이 온다.
 *
 * @param key 문장의 정체. 바뀌면(날씨가 바뀌어 다른 문장이 되는 등) 처음부터 다시 등장한다
 * @param active 이 페이지가 실제로 보이는가. 페이저가 옆 페이지를 미리 그릴 때 시계가
 *   돌아버리면 넘겼을 때 이미 끝나 있다. 보일 때 시작한다
 * @param wordCount 문장의 단어 수. 햅틱이 단어의 착지 시각을 계산하는 데 쓴다
 */
@Composable
fun rememberWeatherEntrance(
    key: Any,
    weatherGroup: WeatherGroup,
    active: Boolean,
    wordCount: Int,
    haptics: Haptics = LocalHaptics.current,
): WeatherEntrance {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key, active) {
        if (active && progress.value < 1f) {
            // 중간부터 이어지는 경우(예: 화면 복귀)에는 햅틱을 다시 틀지 않는다. 처음 시작할 때만.
            if (progress.value == 0f) haptics.play(entranceHaptics(weatherGroup, wordCount))
            progress.animateTo(1f, tween(entranceDurationMs(weatherGroup), easing = LinearEasing))
        }
    }
    return remember(key) { WeatherEntrance(weatherGroup, progress) }
}

// ---------------------------------------------------------------------------
// 날씨별 움직임
// ---------------------------------------------------------------------------

/** 단어 [index] 가 움직이는 구간. 전체 시계(0..1) 위의 시작점과 길이. */
internal data class Window(val start: Float, val length: Float) {
    val end: Float get() = start + length
}

/**
 * 구조는 전부 같다 — 단어마다 **시작 시각**을 정하고(순서대로 훑거나, 무작위로 흩거나),
 * 그 구간이 곧 그 단어의 움직임이다. 궤적은 [poseFor] 가, 착지 시각은 햅틱이 읽는다.
 */
internal fun windowFor(group: WeatherGroup, index: Int, count: Int): Window {
    val sweep = if (count <= 1) 0f else index / (count - 1f)   // 왼쪽 위 → 오른쪽 아래
    val scatter = jitter(index)                                   // 무작위지만 결정적
    return when (group) {
        WeatherGroup.CLEAR -> Window(sweep * 0.55f, 0.45f)
        WeatherGroup.CLOUDY -> Window(sweep * 0.5f, 0.5f)
        WeatherGroup.FOG -> Window(0f, 1f)
        // 낙하 거리는 세 날씨 모두 **줄 높이(28dp) 안**이다. 더 멀리서 떨어지면 윗줄 단어와
        // 겹쳐 중간 프레임이 뒤죽박죽으로 읽힌다 — 프레임 시트에서 확인했다.
        WeatherGroup.DRIZZLE -> Window(sweep * 0.35f + scatter * 0.35f, 0.3f)
        WeatherGroup.RAIN -> Window(sweep * 0.45f + scatter * 0.25f, 0.3f)
        WeatherGroup.SNOW -> Window(sweep * 0.4f + scatter * 0.3f, 0.3f)
        // 뇌우: 세 무리가 차례로 놓인다.
        WeatherGroup.THUNDER -> {
            val burst = (scatter * 3f).toInt().coerceIn(0, 2)
            Window(THUNDER_BURSTS[burst], 0.04f)
        }
        // 바람: 이웃 단어는 **거의 같은 위상**이어야 한다. 무작위로 흩으면 앞 단어가 먼저 놓인
        // 자리로 뒷 단어가 들어오며 글자가 뭉친다. 돌풍의 불규칙함은 인덱스의 부드러운
        // 함수(사인, 주기 25단어)로만 준다 — 이웃끼리는 비슷하고 멀리서는 다르다.
        WeatherGroup.WIND -> Window(sweep * 0.5f + 0.04f * (1f + sin(index * 0.25f)), 0.4f)
    }
}

/** 단어 [index] 의 자세를 전체 시계 [t] 에서 계산한다. */
internal fun poseFor(group: WeatherGroup, index: Int, count: Int, t: Float): Pose {
    val w = windowFor(group, index, count)
    val raw = local(t, w.start, w.length)

    return when (group) {
        // 맑음: 빠르고 선명하게. 살짝 떠올라 놓인다. 읽기 순서대로.
        WeatherGroup.CLEAR -> {
            val l = easeOutCubic(raw)
            Pose(dy = 6f * (1f - l), alpha = l)
        }

        // 흐림: 움직임이 거의 없다. 천천히 배어 나온다.
        WeatherGroup.CLOUDY -> {
            val l = easeInOutSine(raw)
            Pose(dy = 2f * (1f - l), alpha = l)
        }

        // 안개: 단어는 제자리. 흐림은 컨테이너가, 알파만 여기서. 한 덩어리로 초점이 맞는다.
        WeatherGroup.FOG -> Pose(alpha = 0.12f + 0.88f * easeInOutSine(raw))

        // 이슬비: 작은 낙하, 짧은 구간.
        WeatherGroup.DRIZZLE -> {
            val l = easeOutCubic(raw)
            Pose(dy = -7f * (1f - l), alpha = l)
        }

        // 비: 떨어진다. 가속하고(easeIn) 바닥에서 멈춘다.
        WeatherGroup.RAIN -> {
            val fall = easeInQuad(raw)
            Pose(dy = -18f * (1f - fall), alpha = minOf(1f, raw * 2.5f))
        }

        // 눈: 천천히 내려앉으며 좌우로 한 번 흔들린다. 가장 느리다.
        WeatherGroup.SNOW -> {
            val l = easeOutCubic(raw)
            val side = if (jitter(index + 1000) < 0.5f) -1f else 1f
            Pose(dy = -14f * (1f - l), dx = side * 3f * sin(raw * PI).toFloat(), alpha = l)
        }

        // 뇌우: 이징 없음 — 번개는 부드럽지 않다. **툭** 놓인다.
        WeatherGroup.THUNDER -> Pose(alpha = raw)

        // 바람: 왼쪽에서 불려 와 살짝 지나쳤다가 제자리. 기울었다가 선다.
        // 거리는 단어 사이 간격보다 조금 큰 정도 — 옆 단어를 가로지르면 글자가 뭉친다.
        WeatherGroup.WIND -> {
            val l = easeOutBack(raw)
            Pose(dx = -24f * (1f - l), rotation = -2f * (1f - l), alpha = minOf(1f, raw * 2.5f))
        }
    }
}

/** 뇌우 섬광 구간(시계 0..1). 화면의 섬광과 햅틱의 천둥이 같은 값을 읽는다. */
internal val THUNDER_FLASH_1 = 0.02f to 0.09f
internal val THUNDER_FLASH_2 = 0.13f to 0.19f
internal val THUNDER_BURSTS = floatArrayOf(0.22f, 0.38f, 0.54f)

// ---------------------------------------------------------------------------
// 날씨별 햅틱 — 같은 타임라인
// ---------------------------------------------------------------------------

/**
 * 등장에 맞춘 진동. 시각은 등장 시작 기준 ms.
 *
 * 규칙: **화면에서 무언가 놓이는 순간에만 진동한다.** 단어의 착지([Window.end]),
 * 섬광의 정점, 돌풍의 지나감. 화면과 무관한 진동은 없다. 그래서 눈으로 보는 것과
 * 손으로 느끼는 것이 같은 사건이다.
 *
 * 세기는 날씨의 성격이다 — 이슬비는 스치고, 비는 두드리고, 눈은 눌러앉고, 천둥은 친다.
 * 흐림은 아무 진동도 없다. 조용한 날씨는 조용해야 한다.
 */
fun entranceHaptics(group: WeatherGroup, wordCount: Int): HapticPattern {
    val d = entranceDurationMs(group).toFloat()
    fun at(fraction: Float) = (fraction * d).toInt()
    fun landings(every: Int, cap: Int): List<Pair<Int, Float>> =
        (0 until wordCount).filter { it % every == 0 }.take(cap)
            .map { i -> at(windowFor(group, i, wordCount).end) to jitter(i + 500) }

    val events = when (group) {
        // 맑음: 히어로가 놓일 때 한 번, 선명하게.
        WeatherGroup.CLEAR -> listOf(HapticEvent.transient(at(0.45f), intensity = 0.5f, sharpness = 0.8f))

        WeatherGroup.CLOUDY -> emptyList()

        // 안개: 초점이 맞는 끝에 아주 부드럽게 한 번.
        WeatherGroup.FOG -> listOf(HapticEvent.transient(at(0.9f), intensity = 0.25f, sharpness = 0.15f))

        // 이슬비: 네 단어마다 가볍게 스친다.
        WeatherGroup.DRIZZLE -> landings(every = 4, cap = 10).map { (ms, j) ->
            HapticEvent.transient(ms, intensity = 0.22f + 0.12f * j, sharpness = 0.6f)
        }

        // 비: 세 단어마다 두드린다. 방울마다 세기가 다르다. 히어로는 조금 더 무겁게.
        WeatherGroup.RAIN -> listOf(HapticEvent.transient(at(0.3f), intensity = 0.55f, sharpness = 0.7f)) +
            landings(every = 3, cap = 16).map { (ms, j) ->
                HapticEvent.transient(ms, intensity = 0.3f + 0.3f * j, sharpness = 0.7f)
            }

        // 눈: 여섯 단어마다 눌러앉는다. 짧지 않고 부드러운 진동.
        WeatherGroup.SNOW -> landings(every = 6, cap = 8).map { (ms, j) ->
            HapticEvent.continuous(ms, durationMs = 45, intensity = 0.14f + 0.08f * j, sharpness = 0.1f)
        }

        // 뇌우: 섬광의 정점에 천둥 두 번(둘째가 약하다). 그 뒤 세 무리가 놓일 때 딱, 딱, 딱.
        WeatherGroup.THUNDER -> listOf(
            HapticEvent.continuous(at(THUNDER_FLASH_1.first), durationMs = 110, intensity = 1f, sharpness = 0.3f),
            HapticEvent.continuous(at(THUNDER_FLASH_2.first), durationMs = 70, intensity = 0.55f, sharpness = 0.3f),
        ) + THUNDER_BURSTS.map { HapticEvent.transient(at(it + 0.04f), intensity = 0.4f, sharpness = 0.9f) }

        // 바람: 훑고 지나가는 동안 낮게 울린다. 세졌다가 잦아든다.
        WeatherGroup.WIND -> {
            val start = 0.08f
            val end = 0.85f
            val steps = 6
            val step = (end - start) / steps
            val envelope = floatArrayOf(0.15f, 0.3f, 0.45f, 0.45f, 0.3f, 0.15f)
            (0 until steps).map { i ->
                HapticEvent.continuous(
                    at(start + step * i), durationMs = at(step), intensity = envelope[i], sharpness = 0.2f,
                )
            }
        }
    }
    return HapticPattern(events)
}

/**
 * 종이를 넘겼을 때. 짧고 마른 소리.
 *
 * 손가락이 만든 진동이지만 **터치 피드백으로 보내지 않는다.** 이 앱의 진동은 버튼을 눌렀다는
 * UI 신호가 아니라 등장 애니메이션과 한 몸인 콘텐츠다. 터치 피드백 설정(One UI 는 기본이 꺼짐)에
 * 묶으면 문장이 놓이는 것은 느껴지는데 종이 넘김만 조용해져 햅틱 언어가 반쪽이 된다.
 * 미디어 진동 설정은 그대로 따르므로 진동을 원치 않는 유저의 선택은 여전히 존중된다.
 */
val PAGE_TURN_HAPTIC = HapticPattern(listOf(HapticEvent.transient(0, intensity = 0.5f, sharpness = 0.9f)))

// ---------------------------------------------------------------------------

/** [start] 부터 [length] 동안의 국소 시간. 0..1 로 자른다. */
internal fun local(t: Float, start: Float, length: Float): Float =
    ((t - start) / length).coerceIn(0f, 1f)

/** [from]~[to] 사이에서 1 로 솟았다 내려오는 삼각 펄스. */
private fun pulse(t: Float, from: Float, to: Float): Float {
    if (t <= from || t >= to) return 0f
    val mid = (from + to) / 2f
    return if (t < mid) (t - from) / (mid - from) else (to - t) / (to - mid)
}

/** 인덱스에서 0..1 을 결정적으로 뽑는다. 무작위처럼 보이지만 세 플랫폼에서 같다. */
internal fun jitter(index: Int): Float {
    var h = index * -1640531535   // 0x9E3779B1 의 부호 있는 32비트 표현 (황금비 해시)
    h = h xor (h ushr 15)
    h *= -2048144789              // 0x85EBCA6B
    h = h xor (h ushr 13)
    return (h and 0xFFFF) / 65535f
}

internal fun easeOutCubic(x: Float): Float { val y = 1f - x; return 1f - y * y * y }
internal fun easeInQuad(x: Float): Float = x * x
internal fun easeInOutSine(x: Float): Float = (-(cos(PI * x) - 1) / 2).toFloat()
internal fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}
