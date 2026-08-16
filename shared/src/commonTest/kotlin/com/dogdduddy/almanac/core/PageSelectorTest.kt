package com.dogdduddy.almanac.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageSelectorTest {

    private val request = PageRequest(
        dateKey = "2026-08-04",
        timeOfDay = TimeOfDay.MORNING,
        weatherGroup = WeatherGroup.RAIN,
        installId = "test-install",
    )
    private val candidates = listOf(101L, 102L, 103L, 104L, 105L)

    @Test
    fun sameInputAlwaysYieldsSameOutput() {
        val first = PageSelector.select(request, candidates)
        repeat(1_000) {
            assertEquals(first, PageSelector.select(request, candidates))
        }
    }

    /** DB가 후보를 어떤 순서로 주든 결과가 흔들리면 안 된다. */
    @Test
    fun candidateOrderDoesNotAffectResult() {
        val expected = PageSelector.select(request, candidates)
        assertEquals(expected, PageSelector.select(request, candidates.reversed()))
        assertEquals(expected, PageSelector.select(request, candidates.shuffled()))
        assertEquals(expected, PageSelector.select(request, candidates + candidates))
    }

    @Test
    fun differentInstallIdsDiverge() {
        val a = PageSelector.select(request, candidates)
        val b = PageSelector.select(request.copy(installId = "other-install"), candidates)
        assertNotNull(a); assertNotNull(b)
        assertTrue(a.seed != b.seed, "설치 ID가 다르면 시드도 달라야 한다")
    }

    @Test
    fun emptyCandidatesReturnNull() {
        assertNull(PageSelector.select(request, emptyList()))
    }

    @Test
    fun singleCandidateIsAlwaysReturnedEvenIfRecentlyShown() {
        val selection = PageSelector.select(request, listOf(42L), recentlyShownIds = listOf(42L))
        assertEquals(42L, selection?.entryId)
    }

    @Test
    fun recentlyShownEntriesAreAvoided() {
        val avoid = listOf(101L, 102L, 103L)
        val selection = PageSelector.select(request, candidates, recentlyShownIds = avoid)
        assertNotNull(selection)
        assertTrue(selection.entryId !in avoid, "히스토리에 있는 문장이 다시 뽑혔다")
    }

    /**
     * 버킷 후보 전부가 히스토리에 있어도 화면은 비면 안 된다.
     * 제외는 size-1 개까지만 하므로 pool 이 절대 비지 않는다.
     */
    @Test
    fun poolNeverEmptiesWhenHistoryCoversEverything() {
        val selection = PageSelector.select(request, candidates, recentlyShownIds = candidates)
        assertNotNull(selection)
        assertTrue(selection.entryId in candidates)
        // 가장 오래전에 보여준 것(= 히스토리 최신순의 꼬리)이 남아야 한다.
        assertEquals(105L, selection.entryId)
    }

    @Test
    fun selectionIsUniformEnoughAcrossDates() {
        val counts = mutableMapOf<Long, Int>()
        for (day in 1..28) {
            val key = "2026-02-" + day.toString().padStart(2, '0')
            val selection = PageSelector.select(request.copy(dateKey = key), candidates)
            assertNotNull(selection)
            counts[selection.entryId] = (counts[selection.entryId] ?: 0) + 1
        }
        // 5개 후보가 28일 동안 전부 최소 한 번은 등장해야 한다 (히스토리 미적용 상태).
        assertEquals(candidates.toSet(), counts.keys)
    }

    // ---- 균등 노출 -------------------------------------------------------------

    /** 카운트가 없으면 예전과 똑같이 동작해야 한다. 회귀 방지. */
    @Test
    fun emptyReadCountsChangeNothing() {
        assertEquals(
            PageSelector.select(request, candidates),
            PageSelector.select(request, candidates, readCounts = emptyMap()),
        )
    }

    /**
     * 적게 읽은 것만 후보로 남는다.
     *
     * 시드만으로 고르면 무작위 인덱싱이라 장기적으로 편중된다 —
     * 실제 콘텐츠 1년 시뮬레이션에서 428편 중 111편이 한 번도 안 나왔다.
     */
    @Test
    fun onlyTheLeastReadEntriesStayInThePool() {
        val counts = mapOf(101L to 3, 102L to 0, 103L to 3, 104L to 0, 105L to 3)
        for (day in 1..28) {
            val selection = PageSelector.select(
                request.copy(dateKey = "2026-09-" + day.toString().padStart(2, '0')),
                candidates,
                readCounts = counts,
            )
            assertNotNull(selection)
            assertTrue(selection.entryId in setOf(102L, 104L), "많이 읽은 문장이 뽑혔다: $selection")
        }
    }

    /** 최소 집합 안에서는 여전히 시드로 고른다 — 결정론이 유지되어야 한다. */
    @Test
    fun readCountsDoNotBreakDeterminism() {
        val counts = mapOf(101L to 1, 102L to 0, 103L to 0, 104L to 2, 105L to 0)
        val first = PageSelector.select(request, candidates, readCounts = counts)
        assertNotNull(first)
        repeat(20) {
            assertEquals(first, PageSelector.select(request, candidates.shuffled(), readCounts = counts))
        }
    }

    /** 히스토리 회피가 먼저다. 최근에 나온 것은 덜 읽었더라도 바로 다시 나오지 않는다. */
    @Test
    fun recentAvoidanceIsAppliedBeforeReadCounts() {
        val counts = mapOf(101L to 0, 102L to 5, 103L to 5, 104L to 5, 105L to 5)
        val selection = PageSelector.select(
            request,
            candidates,
            recentlyShownIds = listOf(101L),
            readCounts = counts,
        )
        assertNotNull(selection)
        assertTrue(selection.entryId != 101L, "직전에 나온 문장이 '덜 읽음'을 이유로 다시 나왔다")
    }

    @Test
    fun selectDayHasNoDuplicatesWithinTheDay() {
        val group = WeatherGroup.RAIN
        val buckets = TimeOfDay.entries.associate { Bucket(group, it) to candidates }
        val day = PageSelector.selectDay(
            dateKey = "2026-08-04",
            installId = "test-install",
            weatherByTimeOfDay = TimeOfDay.entries.associateWith { group },
            candidatesByBucket = buckets,
        )
        assertEquals(3, day.size)
        val ids = day.values.map { it.entryId }
        assertEquals(ids.size, ids.toSet().size, "하루 안에서 같은 문장이 두 번 나왔다")
    }

    /** 위젯(TimelineProvider)과 앱이 각각 계산해도 결과가 같아야 한다. */
    @Test
    fun selectDayAgreesWithIndividualSelects() {
        val group = WeatherGroup.CLEAR
        val buckets = TimeOfDay.entries.associate { Bucket(group, it) to candidates }
        val day = PageSelector.selectDay(
            dateKey = "2026-08-04",
            installId = "test-install",
            weatherByTimeOfDay = TimeOfDay.entries.associateWith { group },
            candidatesByBucket = buckets,
        )
        val morning = PageSelector.select(
            PageRequest("2026-08-04", TimeOfDay.MORNING, group, "test-install"),
            candidates,
        )
        assertEquals(morning?.entryId, day[TimeOfDay.MORNING]?.entryId)
    }
}

class TimeOfDayTest {

    private val sunrise = 1_754_000_000L
    private val sunset = sunrise + 13 * 3600

    @Test
    fun beforeSunriseIsNight() {
        assertEquals(TimeOfDay.EVENING_NIGHT, resolveTimeOfDay(sunrise - 1, sunrise, sunset, 4))
    }

    @Test
    fun sunriseStartsMorning() {
        assertEquals(TimeOfDay.MORNING, resolveTimeOfDay(sunrise, sunrise, sunset, 5))
        assertEquals(TimeOfDay.MORNING, resolveTimeOfDay(sunrise + 3600, sunrise, sunset, 6))
    }

    @Test
    fun morningEndsAfterFixedSpan() {
        val boundary = sunrise + DaylightPolicy.MORNING_SPAN_SECONDS
        assertEquals(TimeOfDay.MORNING, resolveTimeOfDay(boundary - 1, sunrise, sunset, 9))
        assertEquals(TimeOfDay.DAY, resolveTimeOfDay(boundary, sunrise, sunset, 10))
    }

    @Test
    fun sunsetStartsEvening() {
        assertEquals(TimeOfDay.DAY, resolveTimeOfDay(sunset - 1, sunrise, sunset, 17))
        assertEquals(TimeOfDay.EVENING_NIGHT, resolveTimeOfDay(sunset, sunrise, sunset, 18))
    }

    /** 극야·극주 또는 API 실패. 앱이 죽으면 안 되고 시계 기준으로 떨어져야 한다. */
    @Test
    fun missingSunDataFallsBackToClock() {
        assertEquals(TimeOfDay.MORNING, resolveTimeOfDay(0, null, null, 7))
        assertEquals(TimeOfDay.DAY, resolveTimeOfDay(0, null, null, 13))
        assertEquals(TimeOfDay.EVENING_NIGHT, resolveTimeOfDay(0, null, null, 23))
        assertEquals(TimeOfDay.EVENING_NIGHT, resolveTimeOfDay(0, null, null, 3))
    }

    @Test
    fun inconsistentSunDataFallsBackToClock() {
        assertEquals(TimeOfDay.DAY, resolveTimeOfDay(sunrise, sunset, sunrise, 13))
    }

    /** 백야: 일출 직후 4시간 밖에 없는 '아침'이 일몰을 넘어가면 안 된다. */
    @Test
    fun veryShortDayDoesNotLetMorningOutlastSunset() {
        val shortSunset = sunrise + 2 * 3600
        assertEquals(TimeOfDay.MORNING, resolveTimeOfDay(sunrise + 3600, sunrise, shortSunset, 8))
        assertEquals(TimeOfDay.EVENING_NIGHT, resolveTimeOfDay(shortSunset + 1, sunrise, shortSunset, 11))
    }
}
