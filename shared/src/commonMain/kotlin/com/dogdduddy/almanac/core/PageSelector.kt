package com.dogdduddy.almanac.core

/** 날씨 8그룹 × 시간대 3 = 24버킷 중 하나. */
data class Bucket(
    val weatherGroup: WeatherGroup,
    val timeOfDay: TimeOfDay,
) {
    val key: String get() = "${weatherGroup.key}:${timeOfDay.key}"

    companion object {
        /** 24개 전부. 커버리지 검사용. */
        val ALL: List<Bucket> =
            WeatherGroup.entries.flatMap { w -> TimeOfDay.entries.map { t -> Bucket(w, t) } }
    }
}

/**
 * 선택 엔진의 입력. 이 4개가 같으면 결과는 **언제 호출하든, 어느 플랫폼이든** 같다.
 *
 * @param dateKey 로컬 달력 날짜. ISO-8601 `yyyy-MM-dd`
 * @param installId 설치 고유 ID. user.db 에 저장되며 재설치 전까지 불변
 */
data class PageRequest(
    val dateKey: String,
    val timeOfDay: TimeOfDay,
    val weatherGroup: WeatherGroup,
    val installId: String,
) {
    val bucket: Bucket get() = Bucket(weatherGroup, timeOfDay)
}

data class PageSelection(
    val entryId: Long,
    val bucket: Bucket,
    val seed: ULong,
)

/**
 * 결정론적 선택 엔진.
 *
 * 표시 시점에 난수를 쓰지 않는다. 앱과 위젯이, iOS와 Android가 같은 입력에 같은 문장을 낸다.
 *
 * 후보는 항상 **정렬된 뒤** 인덱싱되므로 DB 조회 순서가 흔들려도 결과는 흔들리지 않는다.
 */
object PageSelector {

    /** 히스토리 회피 기본 창. 이 안에 이미 나온 문장은 가능한 한 피한다. */
    const val DEFAULT_HISTORY_WINDOW: Int = 30

    fun seedFor(request: PageRequest): ULong = fnv1a64(
        buildString {
            append(request.dateKey); append('|')
            append(request.timeOfDay.key); append('|')
            append(request.weatherGroup.key); append('|')
            append(request.installId)
        }
    )

    /**
     * 한 버킷에서 문장 하나를 고른다.
     *
     * @param candidateIds content.db 에서 가져온 해당 버킷의 entry id 들
     * @param recentlyShownIds user.db 히스토리. **최신순**으로 정렬되어 있어야 한다
     * @return 후보가 비어 있으면 null
     */
    fun select(
        request: PageRequest,
        candidateIds: List<Long>,
        recentlyShownIds: List<Long> = emptyList(),
        historyWindow: Int = DEFAULT_HISTORY_WINDOW,
    ): PageSelection? {
        val ordered = candidateIds.distinct().sorted()
        if (ordered.isEmpty()) return null

        val seed = seedFor(request)
        if (ordered.size == 1) return PageSelection(ordered[0], request.bucket, seed)

        // 후보를 전부 소진시키면 안 되므로, 제외는 최대 size-1 개까지만 한다.
        // 덕분에 pool 은 절대 비지 않고, "히스토리가 꽉 차면 아무것도 안 뜬다" 는 사고가 원천 차단된다.
        val candidateSet = ordered.toHashSet()
        val excluded = recentlyShownIds.asSequence()
            .filter { it in candidateSet }
            .distinct()
            .take(minOf(historyWindow, ordered.size - 1))
            .toHashSet()

        val pool = ordered.filterNot { it in excluded }
        val index = (seed % pool.size.toULong()).toInt()
        return PageSelection(pool[index], request.bucket, seed)
    }

    /**
     * 하루치(시간대 3개)를 한 번에 계산한다.
     *
     * WidgetKit `TimelineProvider` 가 하루 엔트리를 미리 만들어야 하므로 필요하다.
     * 아침에 고른 문장은 낮·저녁 후보에서 빠지도록 체이닝해, 하루 안에서 중복이 안 난다.
     *
     * @param candidatesByBucket 버킷별 후보 id. 시간대별로 날씨 그룹이 다를 수 있으므로 버킷 단위로 받는다
     */
    fun selectDay(
        dateKey: String,
        installId: String,
        weatherByTimeOfDay: Map<TimeOfDay, WeatherGroup>,
        candidatesByBucket: Map<Bucket, List<Long>>,
        recentlyShownIds: List<Long> = emptyList(),
        historyWindow: Int = DEFAULT_HISTORY_WINDOW,
    ): Map<TimeOfDay, PageSelection> {
        val result = LinkedHashMap<TimeOfDay, PageSelection>()
        val history = ArrayDeque(recentlyShownIds)

        for (timeOfDay in TimeOfDay.entries) {
            val group = weatherByTimeOfDay[timeOfDay] ?: continue
            val request = PageRequest(dateKey, timeOfDay, group, installId)
            val candidates = candidatesByBucket[request.bucket].orEmpty()
            val selection = select(request, candidates, history.toList(), historyWindow) ?: continue
            result[timeOfDay] = selection
            history.addFirst(selection.entryId)
        }
        return result
    }
}
