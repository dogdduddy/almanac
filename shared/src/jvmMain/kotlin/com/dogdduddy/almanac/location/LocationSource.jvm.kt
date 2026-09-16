package com.dogdduddy.almanac.location

/**
 * 데스크톱에는 측위가 없다. 권한 없음으로 답하면 [LocationRepository] 가
 * 저장된 도시(없으면 기본 도시)로 떨어진다 — 모바일에서 권한을 거부한 것과 같은 경로다.
 */
actual fun systemLocationSource(): LocationSource = NoLocationSource

internal object NoLocationSource : LocationSource {
    override fun hasPermission() = false
    override suspend fun currentCoordinates(): Coordinates? = null
}
