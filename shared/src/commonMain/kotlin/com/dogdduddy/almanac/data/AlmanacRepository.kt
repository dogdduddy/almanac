package com.dogdduddy.almanac.data

import com.dogdduddy.almanac.core.Bucket
import com.dogdduddy.almanac.core.PageRequest
import com.dogdduddy.almanac.core.PageSelector
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.content.Entries
import com.dogdduddy.almanac.db.user.UserDatabase

/** 화면에 필요한 전부. 엔트리 + 어떻게 골랐는지. */
data class ResolvedPage(
    val entry: Entries,
    val bucket: Bucket,
    val seed: ULong,
) {
    /** 화면의 주인공 — 시간적 거리. */
    fun yearsAgo(currentYear: Int): Int = currentYear - entry.year.toInt()
}

/**
 * content.db 와 user.db 를 묶어 결정론 엔진에 물린다.
 *
 * **보유 팩이 후보 집합을 바꾸므로 결정론 계약의 입력이다.**
 * 두 기기가 같은 문장을 내려면 (날짜, 시간대, 날씨, installId) 뿐 아니라
 * **보유 팩도 같아야 한다**. 나중에 계정 동기화를 붙이면 엔티틀먼트 동기화가
 * 페이지 계산보다 먼저 끝나야 한다.
 */
class AlmanacRepository(
    private val content: ContentDatabase,
    private val user: UserDatabase,
    private val nowEpochSeconds: () -> Long,
) {
    private val contentQueries get() = content.contentQueries
    private val userQueries get() = user.userQueries

    // ---- 설치 ID ------------------------------------------------------------

    /**
     * 없으면 만들고, 있으면 그대로 돌려준다.
     *
     * 시드 입력이라 형식이 흔들리면 문장이 갈라진다. 항상 소문자로 정규화해 저장한다.
     */
    fun installId(generate: () -> String): String {
        userQueries.selectInstall().executeAsOneOrNull()?.let { return it.install_id }
        val created = generate().lowercase()
        userQueries.insertInstall(created, nowEpochSeconds())
        return userQueries.selectInstall().executeAsOne().install_id
    }

    // ---- 팩 ----------------------------------------------------------------

    fun ownedPackIds(): List<String> = userQueries.ownedPackIds().executeAsList()

    fun grantPack(packId: String, source: PackSource) {
        userQueries.grantPack(packId, nowEpochSeconds(), source.wire)
    }

    /**
     * 베이스 팩을 보유하고 있지 않으면 부여한다.
     *
     * 테마 팩은 24버킷을 다 덮지 못한다 (바다 문학 팩에 눈 문장이 없듯이).
     * 베이스 팩이 없으면 특정 날씨에만 화면이 비는, 재현하기 어려운 버그가 된다.
     * 그래서 **모든 유저는 항상 베이스 팩을 보유한다**를 불변식으로 강제한다.
     */
    fun ensureBaseEntitlement() {
        val owned = ownedPackIds().toSet()
        contentQueries.basePackIds().executeAsList()
            .filterNot { it in owned }
            .forEach { grantPack(it, PackSource.BUNDLED) }
    }

    // ---- 페이지 선택 ---------------------------------------------------------

    /**
     * 오늘(혹은 지정한 슬롯)의 페이지를 고른다.
     *
     * 표시 시점 랜덤이 아니다. 같은 입력이면 몇 번을 불러도 같은 결과이며,
     * 앱과 위젯이 각각 호출해도 같은 문장이 나온다.
     *
     * @param record true 면 daily_page 에 기록한다. 위젯 미리보기 등에서는 false.
     */
    fun resolvePage(
        dateKey: String,
        timeOfDay: TimeOfDay,
        weatherGroup: WeatherGroup,
        language: String,
        locationKey: String,
        windFlag: Boolean,
        installId: String,
        historyWindow: Int = PageSelector.DEFAULT_HISTORY_WINDOW,
        record: Boolean = true,
    ): ResolvedPage? {
        val owned = ownedPackIds()
        if (owned.isEmpty()) return null

        val candidates = contentQueries
            .candidateIdsForBucket(language, weatherGroup.key, timeOfDay.key, owned)
            .executeAsList()

        val selection = PageSelector.select(
            request = PageRequest(dateKey, timeOfDay, weatherGroup, installId),
            candidateIds = candidates,
            recentlyShownIds = userQueries.recentEntryIds(historyWindow.toLong()).executeAsList(),
            historyWindow = historyWindow,
        ) ?: return null

        val entry = contentQueries.entryById(selection.entryId).executeAsOneOrNull() ?: return null

        if (record) {
            userQueries.recordPage(
                date = dateKey,
                time_of_day = timeOfDay.key,
                location_key = locationKey,
                entry_id = entry.id,
                weather_group = weatherGroup.key,
                wind_flag = if (windFlag) 1L else 0L,
                shown_at = nowEpochSeconds(),
            )
        }
        return ResolvedPage(entry, selection.bucket, selection.seed)
    }

    // ---- 진단 --------------------------------------------------------------

    /**
     * 보유 팩 기준으로 문장이 하나도 없는 버킷.
     *
     * 비어 있지 않으면 그 날씨가 오는 날 화면이 빈다.
     * 큐레이션 진행 상황 점검과 팩 구성 검증에 쓴다.
     */
    fun emptyBuckets(language: String): List<Bucket> {
        val owned = ownedPackIds()
        if (owned.isEmpty()) return Bucket.ALL

        val covered = mutableSetOf<Bucket>()
        for (row in contentQueries.coverageByGroupAndTime(language, owned).executeAsList()) {
            if (row.entry_count <= 0) continue
            val group = WeatherGroup.fromKey(row.weather_group) ?: continue

            // time_of_day 가 NULL 인 행은 시간대 무관이므로 세 슬롯을 모두 덮는다.
            val slots = row.time_of_day
                ?.let { key -> TimeOfDay.fromKey(key)?.let(::listOf) ?: emptyList() }
                ?: TimeOfDay.entries

            slots.forEach { covered += Bucket(group, it) }
        }
        return Bucket.ALL.filterNot { it in covered }
    }

    fun archive(limit: Int, offset: Int = 0) =
        userQueries.archive(limit.toLong(), offset.toLong()).executeAsList()
}

enum class PackSource(val wire: String) {
    /** 앱에 기본 포함. 무료 스타터 및 베이스 팩. */
    BUNDLED("bundled"),
    PURCHASE("purchase"),
    RESTORE("restore"),
}
