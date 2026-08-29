package com.dogdduddy.almanac

import com.dogdduddy.almanac.billing.BillingProduct
import com.dogdduddy.almanac.location.LocationMode

/**
 * 화면이 그릴 상태. [AppLoader] 가 만들고 플랫폼이 Compose 로 넘긴다.
 *
 * Compose 타입이 하나도 없어서 shared 에 둔다 — 조립 지점과 화면 사이의 계약이지
 * 화면의 일부가 아니고, 여기 있어야 [AppLoader] 의 순서를 테스트할 수 있다.
 */
sealed interface AppState {

    /**
     * 아직 아무것도 못 그린 상태.
     *
     * **여기 머무는 시간이 곧 유저가 보는 빈 화면이다.** [AppLoader] 는 저장된 위치만으로
     * 만들 수 있는 첫 화면을 먼저 내보내 이 상태를 최대한 짧게 지나간다.
     */
    data object Loading : AppState

    /** `pages[0]` 이 오늘. 뒤로 갈수록 과거다. */
    data class Ready(
        val pages: List<TodaysPage>,
        val locationLabel: String,
        val locationMode: LocationMode,
        /** 살 수 있는 상품. 비어 있으면 페이월 진입점을 감춘다. */
        val products: List<BillingProduct> = emptyList(),
    ) : AppState

    data class Empty(
        val reason: PageUnavailable,
        val locationLabel: String = "",
        val locationMode: LocationMode = LocationMode.DEFAULT,
    ) : AppState
}
