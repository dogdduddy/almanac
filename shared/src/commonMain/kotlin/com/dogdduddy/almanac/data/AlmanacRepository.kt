package com.dogdduddy.almanac.data

import com.dogdduddy.almanac.core.Bucket
import com.dogdduddy.almanac.core.PageRequest
import com.dogdduddy.almanac.core.PageSelector
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.content.Entries
import com.dogdduddy.almanac.db.user.UserDatabase

/** 지난 기록 한 장. */
data class ArchivedPage(
    val dateKey: String,
    val timeOfDay: TimeOfDay,
    val weatherGroup: WeatherGroup,
    val windFlag: Boolean,
    val entry: Entries,
)

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
     * 무료로 자동 지급되는 팩을 부여한다. 설치 직후와 앱 시작 시 호출한다.
     *
     * `auto_grant` 로 고르지 `is_base` 로 고르지 않는다 — 스타터도 전체 팩도 둘 다
     * 전 그룹을 덮으므로, 커버리지만 보고 지급하면 유료 콘텐츠가 통째로 넘어간다.
     */
    fun ensureBaseEntitlement() {
        val owned = ownedPackIds().toSet()
        contentQueries.autoGrantPackIds().executeAsList()
            .filterNot { it in owned }
            .forEach { grantPack(it, PackSource.BUNDLED) }
    }

    /**
     * 보유 팩이 8개 날씨 그룹을 전부 덮는지.
     *
     * 테마 팩만 보유하는 상태가 되면 특정 날씨에만 화면이 비는, 재현하기 어려운
     * 버그가 된다. 앱 시작 시 이걸 검사해 거짓이면 자동 지급 팩을 다시 붙인다.
     */
    fun hasFullWeatherCoverage(language: String): Boolean =
        emptyBuckets(language).isEmpty()

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

    /**
     * 아카이브 한 페이지. 기록(user.db)과 문장(content.db)을 Kotlin 에서 합친다.
     *
     * 두 DB 는 파일이 다르므로 SQL 조인을 쓸 수 없다. 그건 설계 의도다 —
     * content.db 는 업데이트마다 통째로 교체되고 아카이브는 살아남아야 한다.
     *
     * 그 대가로 **기록은 있는데 문장이 사라진 경우**가 생길 수 있다.
     * 큐레이션에서 발췌를 뺐다면 그렇다. 그런 행은 조용히 건너뛴다 —
     * 빈 카드를 보여주느니 없는 편이 낫다.
     */
    fun archivedPages(limit: Int, offset: Int = 0): List<ArchivedPage> {
        val rows = userQueries.archive(limit.toLong(), offset.toLong()).executeAsList()
        if (rows.isEmpty()) return emptyList()

        val entries = contentQueries.entriesByIds(rows.map { it.entry_id }.distinct())
            .executeAsList()
            .associateBy { it.id }

        return rows.mapNotNull { row ->
            val entry = entries[row.entry_id] ?: return@mapNotNull null
            val group = WeatherGroup.fromKey(row.weather_group) ?: return@mapNotNull null
            val timeOfDay = TimeOfDay.fromKey(row.time_of_day) ?: return@mapNotNull null
            ArchivedPage(
                dateKey = row.date,
                timeOfDay = timeOfDay,
                weatherGroup = group,
                windFlag = row.wind_flag != 0L,
                entry = entry,
            )
        }
    }
}

enum class PackSource(val wire: String) {
    /** 앱에 기본 포함. 무료 스타터 및 베이스 팩. */
    BUNDLED("bundled"),
    PURCHASE("purchase"),
    RESTORE("restore"),
}
