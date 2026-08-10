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
            insertPack("base-2026", "The Almanac", isBase = true, order = 0)
            insertPack("sea-1851", "Sea & Storm", isBase = false, order = 1)

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

    private fun insertPack(id: String, title: String, isBase: Boolean, order: Int) {
        contentDriver.execute(
            null,
            "INSERT INTO packs (id, title, subtitle, is_base, sort_order) VALUES (?, ?, NULL, ?, ?)",
            4,
        ) {
            bindString(0, id); bindString(1, title)
            bindLong(2, if (isBase) 1L else 0L); bindLong(3, order.toLong())
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
    ) = repo.resolvePage(
        dateKey = dateKey,
        timeOfDay = timeOfDay,
        weatherGroup = group,
        language = "en",
        locationKey = "37.5665,126.9780",
        windFlag = false,
        installId = installId,
        record = record,
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
    fun ensureBaseEntitlementGrantsBasePacksOnly() {
        repo.ensureBaseEntitlement()
        assertEquals(listOf("base-2026"), repo.ownedPackIds())
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
        resolve(); resolve(); resolve()
        assertEquals(1, repo.archive(limit = 100).size, "같은 슬롯은 한 줄만 남아야 한다")
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

    @Test
    fun archiveSurvivesContentDatabaseReplacement() {
        repo.ensureBaseEntitlement()
        resolve()
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
