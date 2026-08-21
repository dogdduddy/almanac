package com.dogdduddy.almanac.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.dogdduddy.almanac.core.Bucket
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlmanacRepositoryTest {

    private lateinit var contentDriver: JdbcSqliteDriver
    private lateinit var userDriver: JdbcSqliteDriver
    private lateinit var content: ContentDatabase
    private lateinit var user: UserDatabase
    private lateinit var repo: AlmanacRepository

    private var now = 1_800_000_000L
    private val installId = "0f8b2c1e-4a5d-4e2f-9c31-7b6a8d0e1f42"

    @BeforeTest
    fun setUp() {
        contentDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ContentDatabase.Schema.create(contentDriver)
        content = ContentDatabase(contentDriver)

        userDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserDatabase.Schema.create(userDriver)
        user = UserDatabase(userDriver)

        repo = AlmanacRepository(content, user) { now }
        seedContent()
    }

    @AfterTest
    fun tearDown() {
        contentDriver.close()
        userDriver.close()
    }

    /**
     * 베이스 팩(24버킷 전부 덮음) + 테마 팩(비 버킷만).
     * 테마 팩이 버킷 구멍을 갖는 현실을 그대로 재현한다.
     */
    private fun seedContent() {
        content.contentQueries.transaction {
            insertPack("base-2026", "The Almanac", isBase = true, autoGrant = true, order = 0)
            insertPack("sea-1851", "Sea & Storm", isBase = false, autoGrant = false, order = 1)

            var id = 1L
            for (bucket in Bucket.ALL) {
                repeat(3) {
                    insertEntry(id++, "base-2026", listOf(bucket.weatherGroup), bucket.timeOfDay)
                }
            }
            // 테마 팩은 비 그룹에만 존재한다.
            for (timeOfDay in TimeOfDay.entries) {
                repeat(4) {
                    insertEntry(id++, "sea-1851", listOf(WeatherGroup.RAIN), timeOfDay)
                }
            }
        }
    }

    /** 다중 버킷 + 시간대 무관을 쓰는 엔트리. id 는 기존 대역과 겹치지 않게 잡는다. */
    private fun insertSpecial(id: Long, groups: List<WeatherGroup>, timeOfDay: TimeOfDay?) {
        content.contentQueries.transaction { insertEntry(id, "base-2026", groups, timeOfDay) }
    }

    private fun insertPack(
        id: String,
        title: String,
        isBase: Boolean,
        autoGrant: Boolean,
        order: Int,
    ) {
        contentDriver.execute(
            null,
            "INSERT INTO packs (id, title, subtitle, is_base, auto_grant, sort_order) " +
                "VALUES (?, ?, NULL, ?, ?, ?)",
            5,
        ) {
            bindString(0, id); bindString(1, title)
            bindLong(2, if (isBase) 1L else 0L)
            bindLong(3, if (autoGrant) 1L else 0L)
            bindLong(4, order.toLong())
        }
    }

    private fun insertEntry(
        id: Long,
        packId: String,
        groups: List<WeatherGroup>,
        timeOfDay: TimeOfDay?,
    ) {
        contentDriver.execute(
            null,
            """
            INSERT INTO entries (
                id, pack_id, text, author, title, section, year, source_id, language,
                time_of_day, tier, season_weight, temp_weight, word_count, license_note
            ) VALUES (?, ?, ?, 'PLACEHOLDER', 'PLACEHOLDER', NULL, 1847, 'gutenberg-0', 'en',
                      ?, 'A', NULL, NULL, 50, 'public domain')
            """.trimIndent(),
            4,
        ) {
            bindLong(0, id); bindString(1, packId)
            bindString(2, "placeholder entry $id")
            bindString(3, timeOfDay?.key)
        }
        for (group in groups) {
            contentDriver.execute(
                null,
                "INSERT INTO entry_weather_groups (entry_id, weather_group) VALUES (?, ?)",
                2,
            ) {
                bindLong(0, id); bindString(1, group.key)
            }
        }
    }

    private fun resolve(
        dateKey: String = "2026-08-05",
        timeOfDay: TimeOfDay = TimeOfDay.MORNING,
        group: WeatherGroup = WeatherGroup.RAIN,
        record: Boolean = true,
        countAsRead: Boolean = false,
    ) = repo.resolvePage(
        dateKey = dateKey,
        timeOfDay = timeOfDay,
        weatherGroup = group,
        language = "en",
        locationKey = "37.5665,126.9780",
        windFlag = false,
        installId = installId,
        record = record,
        countAsRead = countAsRead,
    )

    // ---- 설치 ID -----------------------------------------------------------

    @Test
    fun installIdIsGeneratedOnceAndNormalized() {
        val first = repo.installId { "0F8B2C1E-4A5D-4E2F-9C31-7B6A8D0E1F42" }
        assertEquals(first, first.lowercase(), "installId 는 소문자로 정규화되어야 한다")
        // 두 번째 호출은 새로 만들지 않는다 — 만들면 유저의 문장이 통째로 갈린다.
        assertEquals(first, repo.installId { "완전히-다른-값" })
    }

    // ---- 엔티틀먼트 ---------------------------------------------------------

    @Test
    fun noPacksOwnedYieldsNoPage() {
        assertNull(resolve(), "팩이 하나도 없으면 후보가 없다")
    }

    @Test
    fun ensureBaseEntitlementGrantsAutoGrantPacksOnly() {
        repo.ensureBaseEntitlement()
        // sea-1851 은 is_base=0 이자 auto_grant=0 이므로 지급되면 안 된다.
        assertEquals(listOf("base-2026"), repo.ownedPackIds())
    }

    /**
     * 유료 팩이 전 그룹을 덮더라도(=is_base) 자동 지급되면 안 된다.
     * is_base 로 지급을 판단하면 유료 콘텐츠가 통째로 넘어간다.
     */
    @Test
    fun basePackThatIsNotAutoGrantIsNeverGrantedForFree() {
        contentDriver.execute(
            null,
            "INSERT INTO packs (id, title, subtitle, is_base, auto_grant, sort_order) " +
                "VALUES ('full-2026', 'Full', NULL, 1, 0, 2)",
            0,
        )
        repo.ensureBaseEntitlement()
        assertTrue("full-2026" !in repo.ownedPackIds(), "유료 팩이 무료로 지급됐다")
    }

    @Test
    fun autoGrantPackCoversEveryWeatherGroup() {
        repo.ensureBaseEntitlement()
        assertTrue(repo.hasFullWeatherCoverage("en"), "자동 지급 팩만으로 전 그룹이 덮여야 한다")
    }

    @Test
    fun ensureBaseEntitlementIsIdempotent() {
        repo.ensureBaseEntitlement()
        repo.ensureBaseEntitlement()
        assertEquals(listOf("base-2026"), repo.ownedPackIds())
    }

    // ---- 커버리지 불변식 -----------------------------------------------------

    @Test
    fun basePackCoversEveryBucket() {
        repo.ensureBaseEntitlement()
        assertEquals(emptyList(), repo.emptyBuckets("en"))
    }

    /**
     * 테마 팩만 가지고 있으면 버킷에 구멍이 난다.
     * 그래서 베이스 팩 보유가 불변식이어야 한다는 것을 이 테스트가 못박는다.
     */
    @Test
    fun themePackAloneLeavesBucketHoles() {
        repo.grantPack("sea-1851", PackSource.PURCHASE)
        val holes = repo.emptyBuckets("en")
        assertEquals(21, holes.size, "비(3개 시간대)만 덮이고 나머지 21버킷은 비어야 한다")
        assertTrue(holes.none { it.weatherGroup == WeatherGroup.RAIN })
    }

    // ---- 결정론 -------------------------------------------------------------

    @Test
    fun sameInputYieldsSameEntry() {
        repo.ensureBaseEntitlement()
        val first = resolve(record = false)
        assertNotNull(first)
        repeat(50) { assertEquals(first.entry.id, resolve(record = false)?.entry.let { it?.id }) }
    }

    /**
     * 보유 팩이 후보 집합을 바꾸므로 결정론 계약의 입력이다.
     * 이 테스트가 실패하면 계약 문서(determinism-contract.md)가 거짓이 된 것이다.
     */
    @Test
    fun owningMorePacksChangesTheCandidatePool() {
        repo.ensureBaseEntitlement()
        val baseOnly = resolve(record = false)
        assertNotNull(baseOnly)

        repo.grantPack("sea-1851", PackSource.PURCHASE)
        val withTheme = resolve(record = false)
        assertNotNull(withTheme)

        // 같은 날 같은 날씨인데도 후보가 늘어 결과가 달라질 수 있다 — 의도된 동작이다.
        // 최소한 새 팩의 엔트리가 후보에 들어왔는지는 확인한다.
        val seaIds = (73L..84L).toSet()
        val drawnFromSea = (1..40).any { day ->
            resolve(dateKey = "2026-09-%02d".format(day.coerceAtMost(30)), record = false)
                ?.entry?.id in seaIds
        }
        assertTrue(drawnFromSea, "구매한 팩의 문장이 한 번도 안 뽑히면 팩 필터가 동작하지 않는 것")
    }

    // ---- 수집 데이터의 실제 형태 ---------------------------------------------

    /**
     * 수집된 후보의 22% 는 여러 날씨 그룹에 속한다 (cloud|wind, cloud|snow, 최대 5개).
     * 하나의 엔트리가 각 그룹의 후보에 모두 잡혀야 이 자산이 살아난다.
     */
    @Test
    fun multiGroupEntryIsCandidateInEveryGroup() {
        repo.ensureBaseEntitlement()
        val id = 9001L
        insertSpecial(id, listOf(WeatherGroup.CLOUDY, WeatherGroup.WIND, WeatherGroup.SNOW), TimeOfDay.MORNING)

        for (group in listOf(WeatherGroup.CLOUDY, WeatherGroup.WIND, WeatherGroup.SNOW)) {
            val ids = candidateIds(group, TimeOfDay.MORNING)
            assertTrue(id in ids, "$group 후보에 다중 그룹 엔트리가 없다")
        }
        assertTrue(id !in candidateIds(WeatherGroup.RAIN, TimeOfDay.MORNING), "속하지 않은 그룹에 새면 안 된다")
    }

    /**
     * 수집된 후보의 74% 는 시간 단서가 없다. NULL 은 "시간대 무관" 이며 세 슬롯 전부에 잡혀야 한다.
     * 그렇지 않으면 그 74% 를 전부 수동 태깅해야만 쓸 수 있다.
     */
    @Test
    fun nullTimeOfDayIsCandidateInEverySlot() {
        repo.ensureBaseEntitlement()
        val id = 9002L
        insertSpecial(id, listOf(WeatherGroup.FOG), timeOfDay = null)

        for (slot in TimeOfDay.entries) {
            assertTrue(id in candidateIds(WeatherGroup.FOG, slot), "$slot 에 시간무관 엔트리가 없다")
        }
    }

    /** 시간대가 명시된 엔트리는 그 슬롯에만 잡혀야 한다. */
    @Test
    fun explicitTimeOfDayIsScopedToItsSlot() {
        repo.ensureBaseEntitlement()
        val id = 9003L
        insertSpecial(id, listOf(WeatherGroup.FOG), TimeOfDay.EVENING_NIGHT)

        assertTrue(id in candidateIds(WeatherGroup.FOG, TimeOfDay.EVENING_NIGHT))
        assertTrue(id !in candidateIds(WeatherGroup.FOG, TimeOfDay.MORNING))
    }

    /** 시간무관 엔트리 하나만으로도 그 그룹의 세 버킷이 전부 덮여야 한다. */
    @Test
    fun coverageCountsNullTimeAsAllSlots() {
        repo.grantPack("sea-1851", PackSource.PURCHASE)
        val before = repo.emptyBuckets("en").count { it.weatherGroup == WeatherGroup.FOG }
        assertEquals(3, before)

        insertSpecial(9004L, listOf(WeatherGroup.FOG), timeOfDay = null)
        repo.grantPack("base-2026", PackSource.BUNDLED)
        assertEquals(0, repo.emptyBuckets("en").count { it.weatherGroup == WeatherGroup.FOG })
    }

    private fun candidateIds(group: WeatherGroup, timeOfDay: TimeOfDay): List<Long> =
        content.contentQueries
            .candidateIdsForBucket("en", group.key, timeOfDay.key, repo.ownedPackIds())
            .executeAsList()

    // ---- 히스토리 -----------------------------------------------------------

    @Test
    fun recordingIsIdempotentPerSlot() {
        repo.ensureBaseEntitlement()
        resolve(countAsRead = true); resolve(countAsRead = true); resolve(countAsRead = true)
        assertEquals(1, repo.archive(limit = 100).size, "같은 슬롯은 한 줄만 남아야 한다")
    }

    /**
     * 기록을 남긴 뒤 같은 슬롯을 다시 조회해도 문장이 같아야 한다.
     *
     * `record = false` 로만 검증하면 이 경로를 놓친다 — 기록된 문장이 히스토리
     * 맨 앞으로 들어와 자기 자신을 회피 대상으로 삼는 순간 결과가 뒤집힌다.
     * 앱을 열고 위젯이 갱신되는 것만으로 문장이 바뀌던 버그의 회귀 테스트다.
     */
    @Test
    fun recordedSlotKeepsItsEntryOnEveryLaterRead() {
        repo.ensureBaseEntitlement()
        repo.grantPack("sea-1851", PackSource.PURCHASE)

        val first = resolve()
        assertNotNull(first)
        repeat(10) {
            now += 60
            assertEquals(first.entry.id, resolve()?.entry?.id, "같은 슬롯인데 문장이 바뀌었다")
        }
        // 위젯 미리보기 경로(record=false)도 같은 문장을 봐야 한다.
        assertEquals(first.entry.id, resolve(record = false)?.entry?.id)
    }

    /**
     * 날씨가 바뀌면 다시 고른다. 그리고 원래 날씨로 돌아오면 원래 문장으로 돌아온다.
     * (슬롯 기록이 히스토리에서 빠지지 않으면 이 왕복에서 문장이 갈린다.)
     */
    @Test
    fun weatherChangeRepicksAndReturningRestoresTheSameEntry() {
        repo.ensureBaseEntitlement()
        val rain = resolve(group = WeatherGroup.RAIN)
        assertNotNull(rain)

        val clear = resolve(group = WeatherGroup.CLEAR)
        assertNotNull(clear)
        assertTrue(clear.entry.id != rain.entry.id, "날씨가 바뀌면 후보 자체가 달라진다")

        assertEquals(rain.entry.id, resolve(group = WeatherGroup.RAIN)?.entry?.id)
    }

    /** 같은 날 안에서는 실제 시간순(저녁 → 낮 → 아침)으로 내려와야 한다. */
    @Test
    fun archiveOrdersSlotsChronologicallyWithinADay() {
        repo.ensureBaseEntitlement()
        for (slot in TimeOfDay.entries) {
            now += 3_600
            assertNotNull(resolve(timeOfDay = slot, countAsRead = true))
        }
        assertEquals(
            listOf(TimeOfDay.EVENING_NIGHT.key, TimeOfDay.DAY.key, TimeOfDay.MORNING.key),
            repo.archive(limit = 10).map { it.time_of_day },
        )
    }

    @Test
    fun historyPushesSelectionAwayFromRecentEntries() {
        repo.ensureBaseEntitlement()
        val seen = mutableSetOf<Long>()
        for (day in 1..3) {
            now += 86_400
            val page = resolve(dateKey = "2026-08-%02d".format(day))
            assertNotNull(page)
            seen += page.entry.id
        }
        // 버킷당 3개뿐이므로 3일이면 셋 다 나와야 한다 (히스토리 회피가 동작한다는 증거).
        assertEquals(3, seen.size, "히스토리 회피가 동작하면 3일 안에 3개가 모두 소진된다")
    }

    // ---- 읽음 집계 -----------------------------------------------------------

    /**
     * 위젯이 그린 것은 소비가 아니다.
     *
     * 위젯은 슬롯 고정에는 참여해야 하고(그래야 앱과 같은 문장을 그린다) 소비로는
     * 세면 안 된다. 둘을 한 값으로 묶으면 주머니 속에서 하루 3편씩 사라진다.
     */
    @Test
    fun widgetRenderPinsTheSlotButIsNotCountedAsRead() {
        repo.ensureBaseEntitlement()

        val fromWidget = resolve(countAsRead = false)
        assertNotNull(fromWidget)
        assertEquals(emptyMap(), repo.readCounts(), "위젯 갱신이 읽음으로 잡혔다")
        assertEquals(emptyList(), repo.archive(limit = 10), "위젯만 그린 슬롯이 아카이브에 나타났다")

        // 앱이 같은 슬롯을 열면 같은 문장이 나오고, 그때 비로소 읽음이 된다.
        val fromApp = resolve(countAsRead = true)
        assertEquals(fromWidget.entry.id, fromApp?.entry?.id, "위젯과 앱이 다른 문장을 봤다")
        assertEquals(mapOf(fromWidget.entry.id to 1), repo.readCounts())
        assertEquals(1, repo.archive(limit = 10).size, "앱에서 읽은 슬롯이 아카이브에 없다")
    }

    /** 앱을 하루에 열 번 열어도 그 문장을 열 번 읽은 것은 아니다. */
    @Test
    fun reopeningTheAppInTheSameSlotCountsOnce() {
        repo.ensureBaseEntitlement()
        val page = resolve(countAsRead = true)
        assertNotNull(page)
        repeat(5) {
            now += 600
            resolve(countAsRead = true)
        }
        assertEquals(mapOf(page.entry.id to 1), repo.readCounts())
    }

    /** 날씨가 바뀌어 문장이 교체되면 읽음도 그 문장을 따라간다. */
    @Test
    fun replacingTheSlotMovesTheReadToTheNewEntry() {
        repo.ensureBaseEntitlement()
        val rain = resolve(group = WeatherGroup.RAIN, countAsRead = true)
        assertNotNull(rain)
        assertEquals(mapOf(rain.entry.id to 1), repo.readCounts())

        now += 3_600
        val clear = resolve(group = WeatherGroup.CLEAR, countAsRead = true)
        assertNotNull(clear)
        assertEquals(mapOf(clear.entry.id to 1), repo.readCounts(), "교체된 슬롯의 읽음이 남아 있다")
    }

    /**
     * 읽은 것이 적은 문장이 먼저 나온다.
     *
     * 시드만으로 고르면 무작위 인덱싱이라 장기적으로 편중된다.
     * 실제 콘텐츠 1년 시뮬레이션에서 428편 중 111편이 한 번도 안 나왔다.
     */
    @Test
    fun selectionPrefersEntriesTheUserHasReadLeast() {
        repo.ensureBaseEntitlement()
        // 버킷당 3개뿐이라 3일이면 한 바퀴 돈다.
        val firstRound = (1..3).map { day ->
            now += 86_400
            resolve(dateKey = "2026-08-0$day", countAsRead = true)?.entry?.id
        }
        assertEquals(3, firstRound.toSet().size, "한 바퀴에서 3개가 모두 나와야 한다")
        assertEquals(setOf(1), repo.readCounts().values.toSet(), "모두 한 번씩 읽혀야 한다")

        // 두 바퀴째도 특정 문장에 쏠리지 않는다.
        val secondRound = (4..6).map { day ->
            now += 86_400
            resolve(dateKey = "2026-08-0$day", countAsRead = true)?.entry?.id
        }
        assertEquals(3, secondRound.toSet().size)
        assertEquals(setOf(2), repo.readCounts().values.toSet(), "한쪽만 두 번 읽히면 편중이다")
    }

    @Test
    fun archiveSurvivesContentDatabaseReplacement() {
        repo.ensureBaseEntitlement()
        resolve(countAsRead = true)
        assertEquals(1, repo.archive(limit = 10).size)

        // 앱 업데이트로 content.db 가 통째로 교체되는 상황을 재현한다.
        contentDriver.close()
        contentDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ContentDatabase.Schema.create(contentDriver)
        content = ContentDatabase(contentDriver)
        val reopened = AlmanacRepository(content, user) { now }

        assertEquals(1, reopened.archive(limit = 10).size, "히스토리가 user.db 에 있으니 살아남아야 한다")
    }
}
