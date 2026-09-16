package com.dogdduddy.almanac.billing

/**
 * 구매 가능한 상품. 팩 하나에 상품 하나가 대응한다.
 *
 * @param packId content.db 의 packs.id. 결제가 확인되면 이 팩을 지급한다
 * @param productId 스토어 상품 ID (App Store / Play Console 에 같은 값으로 등록)
 */
data class BillingProduct(
    val packId: String,
    val productId: String,
    val title: String,
    val description: String,
    /** 스토어가 알려준 현지 통화 표시 가격. 없으면 아직 못 받아온 것. */
    val displayPrice: String?,
)

sealed interface PurchaseOutcome {
    data class Purchased(val packIds: Set<String>) : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data class Failed(val message: String) : PurchaseOutcome
}

/**
 * 복원 결과.
 *
 * **"복원할 구매가 없었다" 와 "스토어에 못 물어봤다" 를 반드시 구분한다.**
 * 둘 다 빈 집합으로 뭉개면, 비행기 안에서 복원을 누른 유료 유저에게 화면이
 * "구매 내역이 없습니다" 라고 말한다. 환불 요청이 오는 경로가 정확히 이것이다.
 */
sealed interface RestoreOutcome {

    /** [packIds] 는 **이번에 새로 붙은** 팩. 비어 있으면 복원할 구매가 없었다. */
    data class Restored(val packIds: Set<String>) : RestoreOutcome

    data class Failed(val message: String) : RestoreOutcome
}

/**
 * 결제 백엔드.
 *
 * 구현은 RevenueCat 하나지만 인터페이스로 끊는다.
 * - 결제 SDK 없이 엔티틀먼트 동기화 로직을 테스트할 수 있어야 한다
 * - 키가 없는 개발/CI 환경에서도 앱이 돌아야 한다 ([NoBilling])
 */
interface Billing {

    /** 판매 중인 상품. 스토어 조회에 실패하면 빈 목록 — 화면은 페이월을 감춘다. */
    suspend fun products(): List<BillingProduct>

    /**
     * 현재 보유 중인 팩 id.
     *
     * **RevenueCat 이 진실의 원천이고 user.db 는 캐시다.** 기기를 바꾸거나
     * 환불이 나면 여기 결과가 바뀌므로, 앱 시작 시마다 이걸로 맞춘다.
     */
    suspend fun entitledPackIds(): Set<String>

    suspend fun purchase(product: BillingProduct): PurchaseOutcome

    /** 기기 변경·재설치 복원. 스토어 정책상 반드시 제공해야 한다. */
    suspend fun restore(): Set<String>
}

/**
 * 결제가 설정되지 않았을 때 쓰는 구현.
 *
 * 키가 없다고 앱이 죽거나 페이월이 깨지면 안 된다 — 무료 콘텐츠는 그대로 돌아야 한다.
 * 개발 중과 CI 에서 쓰인다.
 */
object NoBilling : Billing {
    override suspend fun products(): List<BillingProduct> = emptyList()
    override suspend fun entitledPackIds(): Set<String> = emptySet()
    override suspend fun purchase(product: BillingProduct) =
        PurchaseOutcome.Failed("billing not configured")
    override suspend fun restore(): Set<String> = emptySet()
}

/**
 * **표시 전용** 구현. RevenueCat 키가 생기기 전까지 쓴다.
 *
 * 상품 정보는 돌려주되 [purchase] 는 항상 실패한다 — 실제 결제 없이 팩이 지급되면
 * 그게 더 나쁜 버그다. 페이월 화면을 만들고 데모하는 데는 이걸로 충분하다.
 *
 * 가격은 원래 스토어가 알려준다. 여기서는 null 이라 화면이 "one time" 만 보여준다.
 *
 * 키가 생기면 `RevenueCatBilling` 으로 교체한다 —
 * 자세한 절차는 docs/decisions/revenuecat-integration.md 참고.
 */
object PreviewBilling : Billing {

    private val fullCollection = BillingProduct(
        packId = "core-2026",
        productId = "com.dogdduddy.almanac.core2026",
        title = "The 2026 Collection",
        description = "Every passage collected so far, in every weather. " +
            "One purchase, no subscription.",
        displayPrice = null,
    )

    override suspend fun products() = listOf(fullCollection)
    override suspend fun entitledPackIds(): Set<String> = emptySet()
    override suspend fun purchase(product: BillingProduct) =
        PurchaseOutcome.Failed("billing not configured yet")
    override suspend fun restore(): Set<String> = emptySet()
}
