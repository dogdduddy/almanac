package com.dogdduddy.almanac.location

import com.dogdduddy.almanac.db.user.UserDatabase

/**
 * 위치 해결과 저장.
 *
 * 우선순위: **수동 선택 > GPS > 마지막 저장값 > 기본 도시**
 *
 * 수동이 GPS 를 이기는 이유: 유저가 도시를 골랐다는 건 명시적 의사표시다.
 * 여행 중에 폰이 현재 위치를 잡아도 "런던의 하늘"을 보고 싶어 고른 것을 덮으면 안 된다.
 * 다시 GPS 를 쓰고 싶으면 [useGps] 로 되돌린다.
 *
 * 결과는 항상 user.db 에 저장된다. **위젯은 이 저장값만 읽는다** —
 * 위젯은 권한을 요청할 수도, 측위를 기다릴 수도 없기 때문이다.
 */
class LocationRepository(
    private val user: UserDatabase,
    private val source: LocationSource,
    private val nowEpochSeconds: () -> Long,
) {
    private val queries get() = user.userQueries

    /** 저장된 위치. 없으면 기본 도시를 심고 그것을 돌려준다. */
    fun current(): ResolvedLocation {
        queries.savedLocation().executeAsOneOrNull()?.let {
            return ResolvedLocation(
                coordinates = Coordinates(it.latitude, it.longitude),
                label = it.label,
                mode = LocationMode.fromWire(it.mode),
            )
        }
        val fallback = ResolvedLocation(
            coordinates = Cities.DEFAULT.coordinates,
            label = Cities.DEFAULT.name,
            mode = LocationMode.DEFAULT,
        )
        persist(fallback)
        return fallback
    }

    /**
     * 앱 시작 시 호출. 필요하면 GPS 로 갱신한다.
     *
     * 수동 선택이 살아 있으면 **측위를 시도조차 하지 않는다** — 배터리도 아끼고
     * 유저의 선택도 존중한다.
     *
     * 반면 **권한 여부로는 막지 않는다.** 아직 아무것도 묻지 않은 상태에서 권한이
     * 없다는 이유로 돌아서면 권한을 요청할 경로가 영영 생기지 않는다 (iOS 가 정확히
     * 그랬다 — 자동 위치가 최초 설치에서 기본 도시에 머물렀다).
     * 물을지 말지는 소스가 안다. 물을 수 없는 소스는 즉시 null 을 돌려준다.
     */
    suspend fun refresh(): ResolvedLocation {
        val existing = current()
        if (existing.mode == LocationMode.MANUAL) return existing

        val coordinates = source.currentCoordinates() ?: return existing
        val resolved = ResolvedLocation(
            coordinates = coordinates,
            label = Cities.nearest(coordinates)?.name ?: formatCoordinateLabel(coordinates),
            mode = LocationMode.GPS,
        )
        persist(resolved)
        return resolved
    }

    /** 유저가 도시를 골랐다. 이후 GPS 는 이 선택을 덮지 않는다. */
    fun selectCity(city: City): ResolvedLocation {
        val resolved = ResolvedLocation(city.coordinates, city.name, LocationMode.MANUAL)
        persist(resolved)
        return resolved
    }

    /**
     * 수동 선택을 해제하고 GPS 로 되돌린다.
     *
     * 즉시 측위하지 않고 모드만 바꾼다 — 다음 [refresh] 에서 잡힌다.
     * 권한이 없으면 좌표는 그대로 두고 모드만 바뀌므로 화면이 비지 않는다.
     */
    fun useGps(): ResolvedLocation {
        val existing = current()
        val resolved = existing.copy(mode = LocationMode.GPS)
        persist(resolved)
        return resolved
    }

    private fun persist(location: ResolvedLocation) {
        queries.upsertLocation(
            latitude = location.coordinates.latitude,
            longitude = location.coordinates.longitude,
            label = location.label,
            mode = location.mode.wire,
            updated_at = nowEpochSeconds(),
        )
    }
}
