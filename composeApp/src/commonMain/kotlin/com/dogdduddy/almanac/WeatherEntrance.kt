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
import kotlin.math.sin

/**
 * 문장이 날씨처럼 등장한다.
 *
 * 이 앱의 주인공은 문장이다. 배경에 날씨 애니메이션을 깔고 글을 얹으면 글은 여전히
 * 정지해 있다 — 그래서 반대로 한다. **비는 단어가 떨어지고, 안개는 초점이 맞고, 바람은
 * 단어가 옆에서 불려 온다.** 배경은 종이 그대로다. 파티클도 소리도 없다.
 *
 * 한 페이지에 시계는 하나([progress], 0→1)다. 히어로·단어·보조 정보가 전부 같은
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
        val first = pulse(t, 0.02f, 0.09f) * 0.85f
        val second = pulse(t, 0.13f, 0.19f) * 0.45f
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
 * @param key 문장의 정체. 바뀌면(날씨가 바뀌어 다른 문장이 되는 등) 처음부터 다시 등장한다
 * @param active 이 페이지가 실제로 보이는가. 페이저가 옆 페이지를 미리 그릴 때 시계가
 *   돌아버리면 넘겼을 때 이미 끝나 있다. 보일 때 시작한다
 */
@Composable
fun rememberWeatherEntrance(key: Any, weatherGroup: WeatherGroup, active: Boolean): WeatherEntrance {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key, active) {
        if (active && progress.value < 1f) {
            progress.animateTo(1f, tween(entranceDurationMs(weatherGroup), easing = LinearEasing))
        }
    }
    return remember(key) { WeatherEntrance(weatherGroup, progress) }
}

// ---------------------------------------------------------------------------
// 날씨별 움직임
// ---------------------------------------------------------------------------

/**
 * 단어 [index] 의 자세를 전체 시계 [t] 에서 계산한다.
 *
 * 구조는 전부 같다 — 단어마다 **시작 시각**을 정하고(순서대로 훑거나, 무작위로 흩거나),
 * 자기 구간 안의 국소 시간에 이징을 건 뒤, 날씨가 정한 궤적에 넣는다.
 */
internal fun poseFor(group: WeatherGroup, index: Int, count: Int, t: Float): Pose {
    val sweep = if (count <= 1) 0f else index / (count - 1f)   // 왼쪽 위 → 오른쪽 아래
    val scatter = jitter(index)                                   // 무작위지만 결정적

    return when (group) {
        // 맑음: 빠르고 선명하게. 살짝 떠올라 놓인다. 읽기 순서대로.
        WeatherGroup.CLEAR -> {
            val l = easeOutCubic(local(t, sweep * 0.55f, 0.45f))
            Pose(dy = 6f * (1f - l), alpha = l)
        }

        // 흐림: 움직임이 거의 없다. 천천히 배어 나온다.
        WeatherGroup.CLOUDY -> {
            val l = easeInOutSine(local(t, sweep * 0.5f, 0.5f))
            Pose(dy = 2f * (1f - l), alpha = l)
        }

        // 안개: 단어는 제자리. 흐림은 컨테이너가, 알파만 여기서. 한 덩어리로 초점이 맞는다.
        WeatherGroup.FOG -> {
            val l = easeInOutSine(t)
            Pose(alpha = 0.12f + 0.88f * l)
        }

        // 이슬비: 작은 낙하, 짧은 구간. 위에서 아래로 오되 순서가 조금 흩어진다.
        //
        // 낙하 거리는 세 날씨 모두 **줄 높이(28dp) 안**이다. 더 멀리서 떨어지면 윗줄 단어와
        // 겹쳐 중간 프레임이 뒤죽박죽으로 읽힌다 — 프레임 시트에서 확인했다.
        WeatherGroup.DRIZZLE -> {
            val l = easeOutCubic(local(t, sweep * 0.35f + scatter * 0.35f, 0.3f))
            Pose(dy = -7f * (1f - l), alpha = l)
        }

        // 비: 떨어진다. 가속하고(easeIn) 바닥에서 멈춘다. 대체로 위에서 아래로, 방울마다 조금씩 다르게.
        WeatherGroup.RAIN -> {
            val raw = local(t, sweep * 0.45f + scatter * 0.25f, 0.3f)
            val fall = easeInQuad(raw)
            Pose(dy = -18f * (1f - fall), alpha = minOf(1f, raw * 2.5f))
        }

        // 눈: 천천히 내려앉으며 좌우로 한 번 흔들린다. 가장 느리다.
        WeatherGroup.SNOW -> {
            val raw = local(t, sweep * 0.4f + scatter * 0.3f, 0.3f)
            val l = easeOutCubic(raw)
            val side = if (jitter(index + 1000) < 0.5f) -1f else 1f
            Pose(
                dy = -14f * (1f - l),
                dx = side * 3f * sin(raw * PI).toFloat(),
                alpha = l,
            )
        }

        // 뇌우: 섬광 뒤에 세 무리가 차례로 **툭** 놓인다. 이징 없음 — 번개는 부드럽지 않다.
        WeatherGroup.THUNDER -> {
            val burst = (scatter * 3f).toInt().coerceIn(0, 2)
            val at = 0.22f + burst * 0.16f
            val l = local(t, at, 0.04f)
            Pose(alpha = l)
        }

        // 바람: 왼쪽에서 불려 와 살짝 지나쳤다가 제자리. 기울었다가 선다.
        //
        // 이웃 단어는 **거의 같은 위상**이어야 한다. 무작위로 흩으면 앞 단어가 먼저 놓인 자리로
        // 뒷 단어가 들어오며 글자가 뭉친다. 그래서 흩지 않고, 돌풍의 불규칙함은 인덱스의
        // 부드러운 함수(사인)로만 준다 — 이웃끼리는 비슷하고 멀리서는 다르다.
        WeatherGroup.WIND -> {
            val gust = 0.04f * (1f + sin(index * 0.25f))
            val raw = local(t, sweep * 0.5f + gust, 0.4f)
            val l = easeOutBack(raw)
            Pose(dx = -24f * (1f - l), rotation = -2f * (1f - l), alpha = minOf(1f, raw * 2.5f))
        }
    }
}

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
internal fun easeInOutSine(x: Float): Float = (-(kotlin.math.cos(PI * x) - 1) / 2).toFloat()
internal fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}
