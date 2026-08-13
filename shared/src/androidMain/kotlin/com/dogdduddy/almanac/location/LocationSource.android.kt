package com.dogdduddy.almanac.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android 측위.
 *
 * Play Services(FusedLocationProvider)를 쓰지 않는다. GMS 없는 기기에서도 돌아야 하고,
 * 이 앱에 필요한 정확도는 **예보 격자 셀 수준**이라 플랫폼 LocationManager 로 충분하다.
 *
 * 마지막으로 알려진 위치를 먼저 본다. 날씨는 몇 분 전 위치로도 정확하고,
 * 콜드 픽스를 기다리면 앱 첫 화면이 늦어진다.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    override fun hasPermission(): Boolean =
        PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission") // hasPermission() 으로 감싼다
    override suspend fun currentCoordinates(): Coordinates? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        lastKnown(manager)?.let { return it.toCoordinates() }

        // 캐시가 없을 때만 실제 측위. 무한정 기다리지 않는다.
        return withTimeoutOrNull(FIX_TIMEOUT_MS) { requestSingleFix(manager) }?.toCoordinates()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? =
        manager.allProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleFix(manager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            val provider = bestProvider(manager)
            if (provider == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = android.os.CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    continuation.resumeIfActive(location)
                }
            } else {
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        continuation.resumeIfActive(location)
                    }

                    @Deprecated("API 29 미만 호환용")
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                manager.requestLocationUpdates(provider, 0L, 0f, listener, context.mainLooper)
            }
        }

    private fun bestProvider(manager: LocationManager): String? =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        const val FIX_TIMEOUT_MS = 8_000L
    }
}

private fun Location.toCoordinates() = Coordinates(latitude, longitude)

private fun CancellableContinuation<Location?>.resumeIfActive(location: Location?) {
    if (isActive) resume(location)
}

/**
 * Context 없이는 만들 수 없다. Android 에서는 [AndroidLocationSource] 를 직접 생성해
 * 주입하고, 이 함수는 안전한 no-op 을 돌려준다 (초기화 순서를 강제하지 않기 위함).
 */
actual fun systemLocationSource(): LocationSource = NoLocationSource

internal object NoLocationSource : LocationSource {
    override fun hasPermission() = false
    override suspend fun currentCoordinates(): Coordinates? = null
}
