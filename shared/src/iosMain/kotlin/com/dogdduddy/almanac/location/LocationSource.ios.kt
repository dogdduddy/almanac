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
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * iOS 측위.
 *
 * 정확도를 **킬로미터 단위로 낮춘다.** 예보 격자 셀보다 정밀할 이유가 없고,
 * 정확도를 낮추면 픽스가 빨리 잡히고 배터리도 덜 쓴다.
 *
 * Info.plist 에 `NSLocationWhenInUseUsageDescription` 이 필요하다.
 *
 * **권한 요청을 여기서 한다.** iOS 는 CLLocationManager 를 만들거나 위치를 물어본다고
 * 프롬프트를 띄워주지 않는다 — `requestWhenInUseAuthorization()` 을 부르는 곳이
 * 없으면 상태는 영원히 notDetermined 이고 자동 위치는 기본 도시에 머문다.
 *
 * 델리게이트는 클래스가 **강하게 붙들고 있어야 한다.** `CLLocationManager.delegate` 는
 * weak 이라, 호출 스코프에만 있는 객체를 물리면 콜백이 오기 전에 사라질 수 있다.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocationSource : LocationSource {

    private val callbacks = LocationCallbacks()

    /**
     * 메인 스레드에서 만들어야 한다. CLLocationManager 는 자기가 만들어진 스레드의
     * 런루프로 콜백을 보내므로, 런루프 없는 스레드에서 만들면 델리게이트가 영영 안 불린다.
     * (앱은 Dispatchers.Main 에서 위치 갱신을 호출한다. 위젯은 저장된 위치만 읽는다.)
     */
    private val manager = CLLocationManager().apply {
        desiredAccuracy = kCLLocationAccuracyKilometer
        delegate = callbacks
    }

    override fun hasPermission(): Boolean = manager.authorizationStatus.isGranted()

    override suspend fun currentCoordinates(): Coordinates? {
        if (!ensureAuthorization()) return null

        // 캐시된 위치가 있으면 즉시 쓴다. 날씨는 몇 분 전 위치로도 정확하다.
        manager.location?.let { return it.toCoordinates() }

        val fix = CompletableDeferred<Coordinates?>()
        callbacks.fix = fix
        manager.requestLocation()

        return withTimeoutOrNull(FIX_TIMEOUT_MS) { fix.await() }
            .also { callbacks.fix = null }
    }

    /**
     * 권한이 아직 정해지지 않았으면 프롬프트를 띄우고 유저의 응답을 기다린다.
     *
     * 이미 거부된 상태에서는 다시 묻지 않는다 — iOS 가 두 번째 프롬프트를 띄우지 않으므로
     * 기다려봐야 타임아웃만 먹는다. 그 경우 도시를 직접 고르는 경로가 정답이다.
     */
    private suspend fun ensureAuthorization(): Boolean {
        val status = manager.authorizationStatus
        if (status.isGranted()) return true
        if (status != kCLAuthorizationStatusNotDetermined) return false

        val granted = CompletableDeferred<Boolean>()
        callbacks.authorization = granted
        manager.requestWhenInUseAuthorization()

        return (withTimeoutOrNull(PROMPT_TIMEOUT_MS) { granted.await() } ?: false)
            .also { callbacks.authorization = null }
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 8_000L

        /**
         * 프롬프트 대기 상한. 유저가 답하지 않으면 기본 도시로 그린다 —
         * 첫 화면이 프롬프트 뒤에서 무한정 비어 있으면 안 된다.
         */
        const val PROMPT_TIMEOUT_MS = 30_000L
    }
}

/**
 * 델리게이트 콜백을 CompletableDeferred 로 옮긴다.
 *
 * 매니저 하나에 델리게이트 하나만 붙일 수 있으므로 권한과 측위를 한 객체가 받는다.
 */
@OptIn(ExperimentalForeignApi::class)
private class LocationCallbacks : NSObject(), CLLocationManagerDelegateProtocol {

    var authorization: CompletableDeferred<Boolean>? = null
    var fix: CompletableDeferred<Coordinates?>? = null

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        val status = manager.authorizationStatus
        // 앱 시작 직후에도 한 번 불린다. 아직 미결정이면 유저가 답한 게 아니다.
        if (status == kCLAuthorizationStatusNotDetermined) return
        authorization?.complete(status.isGranted())
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        manager.stopUpdatingLocation()
        fix?.complete(didUpdateLocations.filterIsInstance<CLLocation>().lastOrNull()?.toCoordinates())
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        manager.stopUpdatingLocation()
        fix?.complete(null)
    }
}

private fun CLAuthorizationStatus.isGranted(): Boolean =
    this == kCLAuthorizationStatusAuthorizedAlways || this == kCLAuthorizationStatusAuthorizedWhenInUse

@OptIn(ExperimentalForeignApi::class)
private fun CLLocation.toCoordinates(): Coordinates =
    coordinate.useContents { Coordinates(latitude, longitude) }

actual fun systemLocationSource(): LocationSource = IosLocationSource()
