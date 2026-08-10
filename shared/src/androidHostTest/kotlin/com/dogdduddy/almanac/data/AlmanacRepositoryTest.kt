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
                    insertEntry(id++, "base-2026", bucket)
                }
            }
            // 테마 팩은 비 버킷에만 존재한다.
            for (timeOfDay in TimeOfDay.entries) {
                repeat(4) {
                    insertEntry(id++, "sea-1851", Bucket(WeatherGroup.RAIN, timeOfDay))
                }
            }
        }
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

    private fun insertEntry(id: Long, packId: String, bucket: Bucket) {
        contentDriver.execute(
            null,
            """
            INSERT INTO entries (
                id, pack_id, text, author, title, year, source_id, language,
                weather_group, time_of_day, season_weight, temp_weight, word_count, license_note
            ) VALUES (?, ?, ?, 'PLACEHOLDER', 'PLACEHOLDER', 1847, 'gutenberg-0', 'en',
                      ?, ?, NULL, NULL, 50, 'public domain')
            """.trimIndent(),
            5,
        ) {
            bindLong(0, id); bindString(1, packId)
            bindString(2, "placeholder entry $id")
            bindString(3, bucket.weatherGroup.key); bindString(4, bucket.timeOfDay.key)
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
