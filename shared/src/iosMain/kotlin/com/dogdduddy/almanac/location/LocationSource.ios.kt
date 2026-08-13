package com.dogdduddy.almanac.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.darwin.NSObject

/**
 * iOS 측위.
 *
 * 정확도를 **킬로미터 단위로 낮춘다.** 예보 격자 셀보다 정밀할 이유가 없고,
 * 정확도를 낮추면 픽스가 빨리 잡히고 배터리도 덜 쓴다.
 *
 * Info.plist 에 `NSLocationWhenInUseUsageDescription` 이 필요하다.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationSource : LocationSource {

    private val manager = CLLocationManager().apply {
        desiredAccuracy = kCLLocationAccuracyKilometer
    }

    override fun hasPermission(): Boolean = when (CLLocationManager.authorizationStatus()) {
        kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> true
        else -> false
    }

    override suspend fun currentCoordinates(): Coordinates? {
        if (!hasPermission()) return null

        // 캐시된 위치가 있으면 즉시 쓴다. 날씨는 몇 분 전 위치로도 정확하다.
        manager.location?.let { return it.toCoordinates() }

        val deferred = CompletableDeferred<Coordinates?>()
        val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                val location = didUpdateLocations.filterIsInstance<CLLocation>().lastOrNull()
                manager.stopUpdatingLocation()
                deferred.complete(location?.toCoordinates())
            }

            override fun locationManager(manager: CLLocationManager, didFailWithError: platform.Foundation.NSError) {
                manager.stopUpdatingLocation()
                deferred.complete(null)
            }
        }
        manager.delegate = delegate
        manager.requestLocation()

        return withTimeoutOrNull(FIX_TIMEOUT_MS) { deferred.await() }
            .also { manager.delegate = null }
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 8_000L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CLLocation.toCoordinates(): Coordinates =
    coordinate.useContents { Coordinates(latitude, longitude) }

actual fun systemLocationSource(): LocationSource = IosLocationSource()

@Suppress("UNUSED_PARAMETER")
private fun unusedAuthorizationStatus(status: CLAuthorizationStatus) = Unit
