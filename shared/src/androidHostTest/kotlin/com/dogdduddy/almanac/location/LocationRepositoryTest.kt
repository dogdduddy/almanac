package com.dogdduddy.almanac.location

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.dogdduddy.almanac.db.user.UserDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var user: UserDatabase
    private lateinit var source: FakeLocationSource
    private lateinit var repo: LocationRepository

    private val now = 1_786_369_300L
    private val london = Coordinates(51.5074, -0.1278)

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserDatabase.Schema.create(driver)
        user = UserDatabase(driver)
        source = FakeLocationSource()
        repo = LocationRepository(user, source) { now }
    }

    @AfterTest
    fun tearDown() = driver.close()

    /** 앱을 한 번도 안 열었어도 위젯이 빈 화면이 되면 안 된다. */
    @Test
    fun fallsBackToDefaultCityAndPersistsIt() {
        val current = repo.current()
        assertEquals(LocationMode.DEFAULT, current.mode)
        assertEquals(Cities.DEFAULT.name, current.label)
        // 저장까지 돼야 위젯이 읽을 수 있다.
        assertNotNull(user.userQueries.savedLocation().executeAsOneOrNull())
    }

    @Test
    fun gpsUpdatesLocationAndLabelsNearestCity() = runTest {
        source.permission = true
        source.coordinates = london

        val resolved = repo.refresh()
        assertEquals(LocationMode.GPS, resolved.mode)
        assertEquals("London", resolved.label)
        assertEquals(london, resolved.coordinates)
    }

    @Test
    fun withoutPermissionKeepsExistingLocation() = runTest {
        source.permission = false
        source.coordinates = london

        val resolved = repo.refresh()
        assertEquals(LocationMode.DEFAULT, resolved.mode)
        assertEquals(Cities.DEFAULT.name, resolved.label)
        assertEquals(0, source.fixCalls, "권한이 없는데 측위를 시도했다")
    }

    @Test
    fun failedFixKeepsExistingLocation() = runTest {
        source.permission = true
        source.coordinates = null

        val resolved = repo.refresh()
        assertEquals(LocationMode.DEFAULT, resolved.mode)
    }

    // ---- 수동 선택 우선 --------------------------------------------------------

    @Test
    fun manualSelectionIsPersisted() {
        val tokyo = Cities.byId("tokyo")!!
        val resolved = repo.selectCity(tokyo)
        assertEquals(LocationMode.MANUAL, resolved.mode)
        assertEquals("Tokyo", repo.current().label)
    }

    /**
     * 유저가 도시를 고른 건 명시적 의사표시다. GPS 가 덮으면 안 된다.
     * (여행 중에 "런던의 하늘"을 보고 싶어 고른 것을 현재 위치가 지우면 곤란하다)
     */
    @Test
    fun gpsNeverOverridesManualSelection() = runTest {
        repo.selectCity(Cities.byId("tokyo")!!)
        source.permission = true
        source.coordinates = london

        val resolved = repo.refresh()
        assertEquals(LocationMode.MANUAL, resolved.mode)
        assertEquals("Tokyo", resolved.label)
        assertEquals(0, source.fixCalls, "수동 선택 상태에서 측위를 시도했다 — 배터리 낭비다")
    }

    @Test
    fun useGpsReleasesManualSelection() = runTest {
        repo.selectCity(Cities.byId("tokyo")!!)
        repo.useGps()

        source.permission = true
        source.coordinates = london
        assertEquals("London", repo.refresh().label)
    }

    /** 모드만 바꾸고 좌표는 남겨야 화면이 비지 않는다. */
    @Test
    fun useGpsWithoutPermissionKeepsCoordinates() = runTest {
        val tokyo = Cities.byId("tokyo")!!
        repo.selectCity(tokyo)
        val afterSwitch = repo.useGps()
        assertEquals(tokyo.coordinates, afterSwitch.coordinates)

        source.permission = false
        assertEquals(tokyo.coordinates, repo.refresh().coordinates)
    }
}

class CitiesTest {

    @Test
    fun idsAreUnique() {
        val ids = Cities.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun coordinatesAreInValidRange() {
        for (city in Cities.ALL) {
            assertTrue(city.latitude in -90.0..90.0, city.id)
            assertTrue(city.longitude in -180.0..180.0, city.id)
        }
    }

    @Test
    fun nearestFindsTheObviousCity() {
        // 서울 시청 근처
        assertEquals("seoul", Cities.nearest(Coordinates(37.5665, 126.9780))?.id)
        // 도쿄역 근처
        assertEquals("tokyo", Cities.nearest(Coordinates(35.6812, 139.7671))?.id)
    }

    /** 목록에 없는 곳을 억지로 가장 가까운 도시로 부르면 거짓말이 된다. */
    @Test
    fun nearestReturnsNullWhenTooFar() {
        // 태평양 한가운데
        assertNull(Cities.nearest(Coordinates(0.0, -160.0)))
    }

    @Test
    fun searchMatchesNameAndCountry() {
        assertTrue(Cities.search("seo").any { it.id == "seoul" })
        assertTrue(Cities.search("JP").all { it.country == "JP" })
        assertEquals(Cities.ALL.size, Cities.search("  ").size)
    }

    @Test
    fun coordinateLabelIsReadableForBothHemispheres() {
        assertEquals("51.5°N 0.1°W", formatCoordinateLabel(Coordinates(51.5074, -0.1278)))
        assertEquals("33.8°S 151.2°E", formatCoordinateLabel(Coordinates(-33.8688, 151.2093)))
    }

    @Test
    fun haversineMatchesKnownDistance() {
        // 서울–도쿄 약 1150km
        val km = haversineKm(Coordinates(37.5665, 126.9780), Coordinates(35.6762, 139.6503))
        assertTrue(km in 1100.0..1200.0, "실제: $km")
    }
}

private class FakeLocationSource : LocationSource {
    var permission = false
    var coordinates: Coordinates? = null
    var fixCalls = 0

    override fun hasPermission() = permission

    override suspend fun currentCoordinates(): Coordinates? {
        fixCalls++
        return coordinates
    }
}
