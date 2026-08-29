package com.dogdduddy.almanac

import com.dogdduddy.almanac.billing.Billing
import com.dogdduddy.almanac.billing.BillingProduct
import com.dogdduddy.almanac.billing.EntitlementSync
import com.dogdduddy.almanac.location.City
import com.dogdduddy.almanac.location.LocationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 앱 시작 절차. **Android 와 iOS 가 같은 것을 쓴다.**
 *
 * 규칙은 하나다 — **첫 프레임은 아무것도 기다리지 않는다.**
 *
 * 예전에는 정반대였다. 스토어 동기화 → 상품 조회 → 측위 → 날씨를 전부 끝낸 **뒤에야**
 * 상태를 한 번 넣었고, 그때까지 화면은 [AppState.Loading] 이었다. 그래서
 * - 위치 권한 프롬프트를 띄운 채 최대 30초 동안 화면이 비었고 (실측),
 * - 스토어나 날씨가 느리면 그만큼 더 비었고,
 * - 어느 한 단계가 던지면 **영원히** 비었다 (상태를 넣는 줄에 닿지 못하므로).
 *
 * App Review 는 그 빈 화면을 보고 0.3.0 빌드 4 를 지침 2.1(a) 로 반려했다.
 * "앱을 실행하면 아무 내용도 없는 빈 흰색 페이지가 나타납니다."
 *
 * 그래서 순서를 뒤집는다.
 * 1. **저장된(없으면 기본) 위치로 즉시 그린다.** 권한도 스토어도 건드리지 않는다
 * 2. 측위해서 다시 그린다 — 프롬프트가 떠 있는 동안에도 1) 이 화면에 있다
 * 3. 스토어를 맞추고 상품을 채운다. 늦거나 실패하면 업그레이드 진입점만 빠진다
 *
 * 단계는 서로를 막지 않고, **어느 단계가 던져도 화면은 직전 상태로 남는다.**
 * [AppState.Loading] 으로 되돌아가는 경로는 이 클래스에 없다.
 *
 * **[scope] 는 메인 디스패처여야 한다.** iOS 의 CLLocationManager 는 자기가 만들어진
 * 스레드의 런루프로 콜백을 보내므로, 백그라운드에서 [service] 를 처음 만들면
 * 델리게이트가 영영 불리지 않는다.
 *
 * 의존성을 값이 아니라 **함수로 받는 이유**: 셋 다 처음 만질 때 무거운 일이 일어난다
 * (DB 복사와 SQLite 열기, RevenueCat 초기화). 생성자에서 값으로 받으면 그 일이
 * 첫 컴포지션 중에 메인 스레드에서 벌어져, 고치려는 바로 그 지연을 다시 만든다.
 * 게다가 iOS 는 결제 구현을 시작 직전에 주입하므로 [entitlements] 를 미리 만들면
 * 그 lazy 가 PreviewBilling 을 붙들어 결제가 조용히 죽는다.
 */
class AppLoader(
    private val scope: CoroutineScope,
    private val service: () -> AlmanacService,
    private val entitlements: () -> EntitlementSync,
    private val billing: () -> Billing,
    private val onState: (AppState) -> Unit,
) {

    /** 마지막으로 내보낸 상태. 상품만 갈아 끼울 때 페이지를 잃지 않으려면 필요하다. */
    private var current: AppState = AppState.Loading

    /** 화면에 걸린 상품. 페이지를 다시 그려도 유지된다. */
    private var products: List<BillingProduct> = emptyList()

    /** 앱 시작. 한 번만 부른다. */
    fun start() {
        scope.launch {
            // 1) 저장된 위치로 즉시. 아직 읽음으로 세지 않는다 — 2) 가 같은 슬롯을
            //    다시 풀면서 그때 센다. 권한을 거부해도 읽음은 정확히 한 번이다.
            draw(refreshLocation = false, countAsRead = false)

            // 2) 측위. 프롬프트가 떠 있는 동안에도 1) 이 화면을 채우고 있다.
            draw(refreshLocation = true, countAsRead = true)

            // 여기까지 한 번도 못 그렸으면 그릴 수 없는 것이다. 안내 화면이라도 세운다 —
            // Loading 으로 남겨두는 것이 심사에서 본 그 '빈 흰 화면' 이다.
            if (current is AppState.Loading) drawUnavailable()

            // 3) 스토어. 첫 화면과 무관하다.
            syncStore()
        }
    }

    /** 유저가 도시를 골랐다. */
    fun selectCity(city: City) {
        runCatching { service().selectCity(city) }
        redraw()
    }

    /** 수동 선택을 풀고 GPS 로 되돌린다. */
    fun useGps() {
        runCatching { service().useGps() }
        redraw()
    }

    /** 위치 권한 응답이 왔다. 허용이든 거부든 같은 경로로 다시 그린다. */
    fun locationPermissionSettled() = redraw()

    fun purchase(product: BillingProduct) {
        scope.launch {
            runCatching { entitlements().purchase(product) }
            draw(refreshLocation = true, countAsRead = true)
            loadProducts()
        }
    }

    fun restore() {
        scope.launch {
            runCatching { entitlements().restore() }
            draw(refreshLocation = true, countAsRead = true)
            loadProducts()
        }
    }

    private fun redraw() {
        scope.launch { draw(refreshLocation = true, countAsRead = true) }
    }

    /**
     * 페이지를 한 번 그린다.
     *
     * **던지지 않는다.** 실패하면 화면을 건드리지 않고 조용히 나간다 — 이미 그린 것이
     * 있으면 그게 남고, 없으면 뒤 단계가 채운다. 여기서 예외를 흘리면 코루틴이 죽어
     * 그 뒤 단계가 통째로 사라지고, 화면은 Loading 에 갇힌다.
     */
    private suspend fun draw(refreshLocation: Boolean, countAsRead: Boolean) {
        val result = runCatching {
            service().pages(refreshLocation = refreshLocation, countAsRead = countAsRead)
        }.getOrElse { return }

        emit(
            when (result) {
                is PagesState.Ready -> AppState.Ready(
                    pages = result.pages,
                    locationLabel = result.locationLabel,
                    locationMode = result.locationMode,
                    products = products,
                )

                is PagesState.Empty -> AppState.Empty(
                    reason = result.reason,
                    locationLabel = result.locationLabel,
                    locationMode = result.locationMode,
                )
            }
        )
    }

    /** 페이지를 아예 만들지 못했을 때의 최후 화면. 도시를 고를 창구가 여기 있다. */
    private fun drawUnavailable() {
        val place = runCatching { service().currentPlace() }.getOrNull()
        emit(
            AppState.Empty(
                reason = PageUnavailable.NO_WEATHER,
                locationLabel = place?.label.orEmpty(),
                locationMode = place?.mode ?: LocationMode.DEFAULT,
            )
        )
    }

    /**
     * 스토어를 맞춘다. **첫 화면과 무관하다.**
     *
     * 상한을 거는 이유: 결제 SDK 호출은 스토어·네트워크 사정에 따라 얼마든지 늘어질 수
     * 있고, 그동안 유저가 잃는 것은 업그레이드 진입점 하나뿐이다. 다음 실행에서 다시 맞춘다.
     *
     * `withTimeoutOrNull` 로 호출 자체를 감싸지 않고 **별도 코루틴을 await 하는 이유**:
     * SDK 호출이 취소에 응한다는 보장이 없다. 응하지 않으면 감싸도 그대로 붙들린다.
     * `await` 는 언제나 취소되므로, 늦게 오는 호출은 제 갈 길을 가게 두고 우리가 빠진다.
     */
    private suspend fun syncStore() {
        val synced = scope.async { runCatching { entitlements().sync() } }
        val ok = withTimeoutOrNull(STORE_TIMEOUT_MS) { synced.await() }?.isSuccess == true

        // 보유 팩이 늘었을 수 있다(재설치 후 복원 등). 오늘 슬롯은 이미 고정돼 문장이
        // 바뀌지는 않지만, 아카이브와 페이월 진입 조건은 여기서 갱신된다.
        if (ok) draw(refreshLocation = false, countAsRead = true)

        loadProducts()
    }

    /** 상품 목록. 없으면 화면이 업그레이드 진입점을 감춘다 — 못 사는 버튼을 두지 않는다. */
    private suspend fun loadProducts() {
        val fetched = scope.async { runCatching { service().paywall(billing()).products } }
        products = withTimeoutOrNull(STORE_TIMEOUT_MS) { fetched.await() }?.getOrNull() ?: return
        (current as? AppState.Ready)?.let { emit(it.copy(products = products)) }
    }

    private fun emit(state: AppState) {
        current = state
        onState(state)
    }

    private companion object {
        /**
         * 결제 SDK 호출 상한.
         *
         * 넉넉하게 잡는다 — 이 시간이 화면을 막지 않기 때문이다. 막던 시절이었다면
         * 훨씬 짧아야 했겠지만, 지금 여기서 늦어져도 유저는 오늘의 문장을 읽고 있다.
         */
        const val STORE_TIMEOUT_MS = 10_000L
    }
}
