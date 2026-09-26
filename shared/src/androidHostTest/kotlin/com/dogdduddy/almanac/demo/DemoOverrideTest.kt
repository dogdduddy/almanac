package com.dogdduddy.almanac.demo

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.dogdduddy.almanac.AlmanacService
import com.dogdduddy.almanac.PageResult
import com.dogdduddy.almanac.PagesState
import com.dogdduddy.almanac.core.TimeOfDay
import com.dogdduddy.almanac.core.WeatherGroup
import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase
import com.dogdduddy.almanac.location.Coordinates
import com.dogdduddy.almanac.location.LocationRepository
import com.dogdduddy.almanac.location.LocationSource
import com.dogdduddy.almanac.weather.FetchMeta
import com.dogdduddy.almanac.weather.DeviceClock
import com.dogdduddy.almanac.weather.formatDateKey
import com.dogdduddy.almanac.weather.localHourFrom
import com.dogdduddy.almanac.weather.SunTimes
import com.dogdduddy.almanac.weather.WeatherFetch
import com.dogdduddy.almanac.weather.WeatherRepository
import com.dogdduddy.almanac.weather.WeatherSnapshot
import com.dogdduddy.almanac.weather.WeatherSource
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 촬영용 고정이 **엔진까지 닿는지**.
 *
 * [DemoControlsTest] 는 고정 객체 자체를 보고, 여기서는 그 고정이 실제로 다른 문장을
 * 내는지 본다. 영상에서 증명하려는 두 가지가 그대로 두 개의 테스트다.
 *
 * 1. 날씨·시간대를 고정하면 그 버킷의 문장이 나온다 (몽타주 컷)
 * 2. **시드를 맞춘 두 설치가 같은 문장을 낸다** (분할 화면 컷)
 *
 * 2번이 없으면 영상의 "One Kotlin core" 장면은 두 기기가 서로 다른 문장을 보여준다 —
 * 시드에 installId 가 들어가기 때문이다.
 */
class DemoOverrideTest {

    private lateinit var contentDriver: JdbcSqliteDriver
    private lateinit var content: ContentDatabase
    private val userDrivers = mutableListOf<JdbcSqliteDriver>()

    @BeforeTest
    fun setUp() {
        contentDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ContentDatabase.Schema.create(contentDriver)
        content = ContentDatabase(contentDriver)
        seedContent()
    }

    @AfterTest
    fun tearDown() {
        userDrivers.forEach { it.close() }
        contentDriver.close()
    }

    /** 실제 날씨는 맑음이고 시간대는 낮이다. 고정이 없으면 그대로 나와야 한다. */
    @Test
    fun `고정하지 않으면 실제 조건 그대로다`() = runTest {
        val device = device(INSTALL_A)

        val page = device.page()

        assertEquals(WeatherGroup.CLEAR, page.weatherGroup)
        assertEquals(TimeOfDay.DAY, page.timeOfDay)
    }

    @Test
    fun `날씨와 시간대를 고정하면 그 버킷에서 고른다`() = runTest {
        val device = device(INSTALL_A)
        device.demo.weatherGroup = WeatherGroup.RAIN
        device.demo.timeOfDay = TimeOfDay.MORNING

        val page = device.page()

        assertEquals(WeatherGroup.RAIN, page.weatherGroup)
        assertEquals(TimeOfDay.MORNING, page.timeOfDay)
        assertEquals("rain/morning", page.section, "다른 버킷의 문장이 새어 들어왔다")
    }

    /** 기온까지 옮기지 않으면 눈 아이콘 옆에 21° 가 남는다. 영상에서 바로 보인다. */
    @Test
    fun `기온도 고정한 값으로 나간다`() = runTest {
        val device = device(INSTALL_A)
        device.demo.weatherGroup = WeatherGroup.SNOW
        device.demo.temperatureC = DemoControls.defaultTemperature(WeatherGroup.SNOW)

        assertEquals(-3.0, device.page().temperatureC)
    }

    /**
     * 시드를 안 맞추면 두 기기가 갈린다 — 분할 화면 컷이 성립하지 않는 상태다.
     *
     * 두 설치 ID 는 임의로 고른 값이 아니라 **실제로 다른 문장으로 떨어지는 값**이다.
     * 우연히 같은 값이 나오면 다음 테스트가 아무것도 증명하지 못하기 때문이다.
     */
    @Test
    fun `설치가 다르면 같은 조건에도 다른 문장이 나온다`() = runTest {
        val a = device(INSTALL_A).apply { forceRainyMorning() }
        val b = device(INSTALL_B).apply { forceRainyMorning() }

        assertNotEquals(a.page().text, b.page().text)
    }

    @Test
    fun `시드를 맞추면 두 설치가 같은 문장을 낸다`() = runTest {
        val a = device(INSTALL_A).apply { forceRainyMorning() }
        val b = device(INSTALL_B).apply { forceRainyMorning() }
        listOf(a, b).forEach { it.demo.installId = DemoControls.SHARED_SEED_INSTALL_ID }

        assertEquals(a.page().text, b.page().text)
    }

    /** 촬영이 끝나면 원래대로 돌아와야 한다. 고정이 기록을 오염시키고 끝나면 안 된다. */
    @Test
    fun `고정을 풀면 실제 조건으로 돌아온다`() = runTest {
        val device = device(INSTALL_A)
        device.forceRainyMorning()
        assertEquals(WeatherGroup.RAIN, device.page().weatherGroup)

        device.demo.clear()

        val page = device.page()
        assertEquals(WeatherGroup.CLEAR, page.weatherGroup)
        assertEquals(TimeOfDay.DAY, page.timeOfDay)
    }

    // ---- 아카이브 채우기 (역방향 넘김 컷용) -------------------------------------

    /**
     * 새로 설치한 기기에는 넘길 과거가 없다. 촬영 메뉴로 시간대만 바꿔 쌓으면
     * 기록이 전부 오늘 날짜에 몰려 "과거로 되돌아간다" 는 느낌이 죽는다.
     */
    @Test
    fun `아카이브를 채우면 지난 날짜의 페이지가 생긴다`() = runTest {
        val device = device(INSTALL_A)

        val made = device.service.seedArchive(days = 21)

        assertTrue(made > 0, "한 장도 못 채웠다")
        val dates = device.archiveDates()
        assertEquals(made, dates.size)
        assertTrue(dates.all { it < DATE_KEY }, "오늘이거나 미래인 날짜가 섞였다: $dates")
    }

    /** 매일 세 칸을 꽉 채우면 사람이 읽은 기록으로 안 보인다. */
    @Test
    fun `하루를 꽉 채우지 않는다`() = runTest {
        val device = device(INSTALL_A)

        val made = device.service.seedArchive(days = 21)

        assertTrue(made < 21 * 3, "21일 × 3칸을 전부 채웠다 — 빈틈이 없다")
        assertTrue(made > 21, "너무 적다 — 넘길 것이 없다")
    }

    /**
     * 넘길 때 보이는 것이 날씨 아이콘과 문장이다. 둘 다 같으면 페이지를 넘기는
     * 그림이 아니라 같은 화면을 반복하는 그림이 된다.
     */
    @Test
    fun `채운 아카이브는 날씨도 문장도 갈린다`() = runTest {
        val device = device(INSTALL_A)
        device.service.seedArchive(days = 21)

        val pages = device.service.pages(archiveLimit = 100)
        assertIs<PagesState.Ready>(pages)
        val past = pages.pages.filterNot { it.isToday }

        assertTrue(past.map { it.weatherGroup }.distinct().size >= 3,
            "날씨가 거의 한 종류다: ${past.map { it.weatherGroup }.distinct()}")

        // 같은 문장이 아예 안 나올 수는 없다 — 얇은 칸(thunder/아침은 실제 콘텐츠에서
        // 2편뿐이다)은 반복이 불가피하다. 문제는 **연달아** 같은 것이 오는 경우다.
        // 넘기는 컷에서 그 그림은 페이지가 안 넘어간 것처럼 보인다.
        val texts = past.map { it.text }
        val backToBack = texts.zipWithNext().count { (a, b) -> a == b }
        assertEquals(0, backToBack, "같은 문장이 연달아 나온다")
        assertTrue(texts.distinct().size * 10 >= texts.size * 6,
            "반복이 너무 많다: ${texts.distinct().size}/${texts.size}")
    }

    /**
     * **아카이브 채우기도 촬영용 시드를 따라야 한다.**
     *
     * 이 기능이 있는 이유가 분할 화면(#7)에서 두 기기의 아카이브를 같은 모양으로
     * 만드는 것이다. 기기 고유 installId 로 채우면 오늘 페이지만 맞고 넘긴 페이지는
     * 갈리는데, 하필 "같은 제스처, 같은 문장" 을 증명하려는 컷이라 정반대가 찍힌다.
     * 실제로 그렇게 찍혔다 — 한쪽은 Dracula, 다른 쪽은 Riders of the Purple Sage.
     */
    @Test
    fun `시드를 맞추면 두 설치가 같은 아카이브를 만든다`() = runTest {
        val a = device(INSTALL_A)
        val b = device(INSTALL_B)
        listOf(a, b).forEach { it.demo.installId = DemoControls.SHARED_SEED_INSTALL_ID }

        a.service.seedArchive(days = 21)
        b.service.seedArchive(days = 21)

        assertTrue(a.archiveEntries().isNotEmpty(), "한 장도 안 채워졌다")
        assertEquals(a.archiveEntries(), b.archiveEntries())
    }

    /** 위 검증이 공짜로 통과하지 않는지 본다 — 시드를 안 맞추면 갈려야 한다. */
    @Test
    fun `시드를 맞추지 않으면 아카이브가 갈린다`() = runTest {
        val a = device(INSTALL_A)
        val b = device(INSTALL_B)

        a.service.seedArchive(days = 21)
        b.service.seedArchive(days = 21)

        assertNotEquals(a.archiveEntries(), b.archiveEntries())
    }

    /** 채운 뒤에도 오늘 페이지는 오늘 것이어야 한다 — 아카이브가 오늘을 덮으면 안 된다. */
    @Test
    fun `채워도 오늘 페이지는 그대로다`() = runTest {
        val device = device(INSTALL_A)
        val before = device.page().text

        device.service.seedArchive(days = 21)

        assertEquals(before, device.page().text)
    }

    // ---- 조립 ----------------------------------------------------------------

    private class Device(
        val service: AlmanacService,
        val demo: DemoControls,
        val repository: AlmanacRepository,
    ) {
        fun forceRainyMorning() {
            demo.weatherGroup = WeatherGroup.RAIN
            demo.timeOfDay = TimeOfDay.MORNING
        }

        suspend fun page() = assertIs<PageResult.Ready>(service.todaysPage()).page

        /** 아카이브에 남은 날짜들. 오늘 슬롯은 빼고 본다. */
        fun archiveDates(): List<String> = repository.archivedPages(200)
            .map { it.dateKey }
            .filterNot { it == DATE_KEY }

        /** 아카이브의 (날짜, 시간대, 문장). 두 기기가 같은 것을 만들었는지 볼 때 쓴다. */
        fun archiveEntries(): List<String> = repository.archivedPages(200)
            .filterNot { it.dateKey == DATE_KEY }
            .map { "%s|%s|%s".format(it.dateKey, it.timeOfDay.key, it.entry.text) }
    }

    /** 기기 하나 = user.db 하나. content.db 는 번들이므로 공유한다. */
    private fun device(installId: String): Device {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserDatabase.Schema.create(driver)
        userDrivers += driver

        // 설치 ID 를 미리 심는다. 임의 UUID 가 들어가면 시드가 매 실행 달라져
        // "다른 설치는 다른 문장" 검증이 우연에 기댄다.
        driver.execute(null, "INSERT INTO install (id, install_id, created_at) VALUES (1, ?, 0)", 1) {
            bindString(0, installId)
        }

        val user = UserDatabase(driver)
        val clock = CalendarClock(NOW)
        val demo = DemoControls()
        val repository = AlmanacRepository(content, user) { clock.nowEpochSeconds() }

        return Device(
            AlmanacService(
                content = repository,
                weather = WeatherRepository(user, ClearSky, clock),
                location = LocationRepository(user, NoLocation) { clock.nowEpochSeconds() },
                clock = clock,
                demo = demo,
            ),
            demo,
            repository,
        )
    }

    /**
     * 버킷마다 다섯 편. 한 편뿐이면 어떤 시드로도 같은 문장이 나와
     * "시드를 맞춰서 같아졌다" 를 증명하지 못한다.
     */
    private fun seedContent() {
        contentDriver.execute(
            null,
            "INSERT INTO packs (id, title, subtitle, is_base, auto_grant, sort_order) " +
                "VALUES ('base-2026', 'The Almanac', NULL, 1, 1, 0)",
            0,
        )
        var id = 1L
        for (group in WeatherGroup.entries) {
            for (timeOfDay in TimeOfDay.entries) {
                repeat(CANDIDATES_PER_BUCKET) { insertEntry(id++, group, timeOfDay) }
            }
        }
    }

    /** section 에 버킷 이름을 적어둔다 — 어느 칸에서 골랐는지 테스트가 눈으로 본다. */
    private fun insertEntry(id: Long, group: WeatherGroup, timeOfDay: TimeOfDay) {
        contentDriver.execute(
            null,
            """
            INSERT INTO entries (
                id, pack_id, text, author, title, section, year, source_id, language,
                time_of_day, tier, season_weight, temp_weight, word_count, license_note
            ) VALUES (?, 'base-2026', ?, 'PLACEHOLDER', 'PLACEHOLDER', ?, 1847,
                      'gutenberg-0', 'en', ?, 'A', NULL, NULL, 50, 'public domain')
            """.trimIndent(),
            4,
        ) {
            bindLong(0, id)
            bindString(1, "entry $id")
            bindString(2, "${group.key}/${timeOfDay.key}")
            bindString(3, timeOfDay.key)
        }
        contentDriver.execute(
            null,
            "INSERT INTO entry_weather_groups (entry_id, weather_group) VALUES (?, ?)",
            2,
        ) {
            bindLong(0, id); bindString(1, group.key)
        }
    }

    private companion object {
        /** 2026-08-28 10:00 +09:00. [CalendarClock] 이 이걸 날짜로 환산한다. */
        const val NOW = 1_787_878_800L
        const val DATE_KEY = "2026-08-28"
        const val CANDIDATES_PER_BUCKET = 5

        /** rain/morning 에서 서로 다른 후보로 떨어지는 두 설치 ID. */
        const val INSTALL_A = "11111111-1111-4111-8111-111111111111"
        const val INSTALL_B = "22222222-2222-4222-8222-222222222222"
    }
}

/**
 * 진짜로 날짜를 계산하는 시계.
 *
 * [FixedDeviceClock] 은 어떤 시각을 줘도 같은 `dateKey` 를 돌려주므로 **지난 날짜를
 * 검증할 수 없다** — 아카이브 채우기는 21일을 되짚는데 전부 오늘이 되어버린다.
 */
private class CalendarClock(private val now: Long) : DeviceClock {
    private val offsetSeconds = 9 * 3600L

    override fun nowEpochSeconds() = now
    override fun localDateKey(epochSeconds: Long) = formatDateKey(epochSeconds, offsetSeconds)
    override fun localHour(epochSeconds: Long) = localHourFrom(epochSeconds, offsetSeconds)
    override fun utcOffsetString(epochSeconds: Long) = "+09:00"
}

/** 언제나 맑음. 고정이 없을 때의 기준선이다. */
private object ClearSky : WeatherSource {
    override suspend fun fetchWeather(
        latitude: Double,
        longitude: Double,
        lastModified: String?,
    ): WeatherFetch = WeatherFetch.Updated(
        snapshot = WeatherSnapshot(
            wmoCode = 0,
            windSpeedMs = 1.0,
            temperatureC = 21.0,
            observedAtEpochSeconds = 1_787_000_000L,
        ),
        meta = FetchMeta(1_787_000_000L, null, null),
    )

    // 일출·일몰이 없으면 로컬 시(10시) 폴백 → 낮.
    override suspend fun fetchSunTimes(
        latitude: Double,
        longitude: Double,
        date: String,
        utcOffset: String,
    ): SunTimes? = null
}

/** 측위하지 않는다. 저장된 위치가 없으므로 기본 도시가 들어간다 — 두 기기가 같은 좌표다. */
private object NoLocation : LocationSource {
    override fun hasPermission() = false
    override suspend fun currentCoordinates(): Coordinates? = null
}
