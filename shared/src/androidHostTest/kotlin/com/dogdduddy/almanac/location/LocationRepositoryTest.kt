package com.dogdduddy.almanac.location

import com.dogdduddy.almanac.weather.LocationKey

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
        assertEquals(london.rounded(), resolved.coordinates)
    }

    /**
     * 권한이 없으면 위치는 그대로다. 다만 **소스는 불러야 한다** —
     * 권한을 물을지 판단하는 것이 소스의 일이고, 저장소가 미리 막으면
     * iOS 에서 프롬프트를 띄울 경로가 사라진다.
     */
    @Test
    fun withoutPermissionKeepsExistingLocationButStillAsksTheSource() = runTest {
        source.permission = false
        source.coordinates = london

        val resolved = repo.refresh()
        assertEquals(LocationMode.DEFAULT, resolved.mode)
        assertEquals(Cities.DEFAULT.name, resolved.label)
        assertEquals(1, source.fixCalls, "소스를 부르지 않으면 권한을 요청할 기회가 없다")
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
        val stored = tokyo.coordinates.rounded()
        repo.selectCity(tokyo)
        val afterSwitch = repo.useGps()
        assertEquals(stored, afterSwitch.coordinates)

        source.permission = false
        assertEquals(stored, repo.refresh().coordinates)
    }

    /**
     * 저장되는 좌표는 소수점 2자리(≈1.1km)를 넘지 않는다.
     *
     * 이 테스트가 지키는 것은 캐시 적중률이 아니라 **개인정보처리방침의 문장**이다.
     * "정밀한 위치를 보관하지 않는다" 고 공개한 이상, 코드가 그걸 어기면 문서가
     * 거짓이 된다. 예전에는 MET 로 보내기 직전에만 반올림해서 DB 에는 GPS 원본이
     * 남아 있었고, 안드로이드 자동 백업을 타고 클라우드로도 갔다.
     */
    @Test
    fun storedCoordinatesAreRounded() = runTest {
        source.permission = true
        source.coordinates = Coordinates(37.566823456, 126.977512345)

        val resolved = repo.refresh()
        assertEquals(37.57, resolved.coordinates.latitude)
        assertEquals(126.98, resolved.coordinates.longitude)

        // 다시 읽어도 같아야 한다 — 저장 시점에 깎였다는 뜻이다.
        val reread = repo.current().coordinates
        assertEquals(37.57, reread.latitude)
        assertEquals(126.98, reread.longitude)
    }
}

private fun Coordinates.rounded() =
    Coordinates(LocationKey.round(latitude), LocationKey.round(longitude))

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

    /** 실제 소스와 같이, 권한이 없으면 좌표를 주지 않는다. */
    override suspend fun currentCoordinates(): Coordinates? {
        fixCalls++
        return if (permission) coordinates else null
    }
}
