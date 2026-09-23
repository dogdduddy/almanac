package com.dogdduddy.almanac

import com.dogdduddy.almanac.billing.BillingProduct
import com.dogdduddy.almanac.location.LocationMode

/**
 * 결제 흐름의 현재 상태. **페이지 상태와 독립이다.**
 *
 * 예전에는 이런 타입이 없었다. [AppLoader] 가 `PurchaseOutcome` 을 받자마자
 * `runCatching { }` 안에서 버렸고, 화면에는 결과를 실어 보낼 자리조차 없었다.
 * 그래서 성공·취소·실패가 전부 똑같이 보였다 — 페이월이 닫히고 끝.
 * 결제가 실패했는데 실패했다는 말을 못 듣는 것이 그중 가장 나쁘다.
 */
sealed interface PurchaseState {

    /** 아무것도 진행 중이 아니다. 유저가 결과를 확인하고 나면 여기로 돌아온다. */
    data object Idle : PurchaseState

    /** 스토어 시트를 띄우고 응답을 기다리는 중. 이 동안 구매 버튼은 잠긴다. */
    data object Working : PurchaseState

    /**
     * 팩이 열렸다.
     *
     * @param entryCount 이번에 열린 문장 수. 0 이면 세지 못한 것이고 화면이 숫자를 뺀다
     * @param restored 새 구매가 아니라 복원이었는가. 청구가 없었으므로 문구가 달라진다
     */
    data class Unlocked(val entryCount: Int, val restored: Boolean = false) : PurchaseState

    /** 유저가 스토어 시트를 닫았다. 청구는 없다. */
    data object Cancelled : PurchaseState

    /** 복원할 구매가 없었다. **스토어에 못 물어본 [Failed] 와 다르다.** */
    data object NothingToRestore : PurchaseState

    /**
     * @param message 스토어가 준 원문. 진단용이고 **화면에 그대로 내보내지 않는다** —
     *   SDK 메시지는 번역되지 않고 기술적이라 유저에게 보여줄 물건이 아니다
     */
    data class Failed(val message: String) : PurchaseState
}

/**
 * 화면이 그릴 상태. [AppLoader] 가 만들고 플랫폼이 Compose 로 넘긴다.
 *
 * Compose 타입이 하나도 없어서 shared 에 둔다 — 조립 지점과 화면 사이의 계약이지
 * 화면의 일부가 아니고, 여기 있어야 [AppLoader] 의 순서를 테스트할 수 있다.
 */
sealed interface AppState {

    /**
     * 결제 흐름의 상태.
     *
     * 어느 화면 상태에 있든 따라다닌다 — 구매 도중 날씨가 끊겨 [Empty] 로 떨어져도
     * 결제 결과는 말해야 한다. [Ready] 에만 달면 그 경로에서 결과가 사라진다.
     */
    val purchase: PurchaseState

    /**
     * 아직 아무것도 못 그린 상태.
     *
     * **여기 머무는 시간이 곧 유저가 보는 빈 화면이다.** [AppLoader] 는 저장된 위치만으로
     * 만들 수 있는 첫 화면을 먼저 내보내 이 상태를 최대한 짧게 지나간다.
     */
    data object Loading : AppState {
        override val purchase: PurchaseState = PurchaseState.Idle
    }

    /** `pages[0]` 이 오늘. 뒤로 갈수록 과거다. */
    data class Ready(
        val pages: List<TodaysPage>,
        val locationLabel: String,
        val locationMode: LocationMode,
        /** 살 수 있는 상품. 비어 있으면 **구매** 진입점을 감춘다. */
        val products: List<BillingProduct> = emptyList(),
        /**
         * 아직 열리지 않은 팩이 남았는가.
         *
         * [products] 와 다른 질문이다 — 오프라인이거나 상품 심사가 안 끝나면 상품 목록은
         * 비지만 코드로는 열 수 있다. 서가 진입점은 이 값으로 결정한다.
         */
        val hasLockedPacks: Boolean = false,
        override val purchase: PurchaseState = PurchaseState.Idle,
    ) : AppState

    data class Empty(
        val reason: PageUnavailable,
        val locationLabel: String = "",
        val locationMode: LocationMode = LocationMode.DEFAULT,
        override val purchase: PurchaseState = PurchaseState.Idle,
    ) : AppState
}
