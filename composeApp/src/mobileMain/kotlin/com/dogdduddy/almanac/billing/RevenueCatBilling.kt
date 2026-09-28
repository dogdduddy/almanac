package com.dogdduddy.almanac.billing

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.PurchasesConfiguration
import com.revenuecat.purchases.kmp.ktx.awaitCustomerInfo
import com.revenuecat.purchases.kmp.ktx.awaitGetProducts
import com.revenuecat.purchases.kmp.ktx.awaitPurchase
import com.revenuecat.purchases.kmp.ktx.awaitRestore
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.PurchasesTransactionException
import com.revenuecat.purchases.kmp.models.StoreProduct

/**
 * 팩 하나에 스토어 상품 하나. **RevenueCat 의 entitlement 식별자를 packId 와 같게 두면**
 * 별도 매핑 테이블이 필요 없다.
 */
data class BillingCatalogEntry(
    val packId: String,
    val productId: String,
    val fallbackTitle: String,
    val fallbackDescription: String,
)

/**
 * RevenueCat 구현.
 *
 * 두 가지를 지킨다.
 * - **엔티틀먼트가 진실의 원천이다.** 상품을 샀는지가 아니라 지금 활성인 엔티틀먼트를 본다.
 *   환불·가족공유 해제 같은 사후 변화가 여기로 들어온다
 * - **실패를 예외로 흘리지 않는다.** 오프라인은 흔하고, 그때 앱이 죽거나
 *   콘텐츠를 잃으면 안 된다. 회수 판단은 [EntitlementSync] 가 한다
 *
 * 제목·설명은 스토어 값을 우선하되 비어 있으면 카탈로그의 기본값을 쓴다 —
 * Test Store 는 메타데이터가 비어 올 수 있다.
 */
class RevenueCatBilling(
    private val catalog: List<BillingCatalogEntry>,
) : Billing {

    private val byProductId = catalog.associateBy { it.productId }
    private val knownPackIds = catalog.mapTo(mutableSetOf()) { it.packId }

    override suspend fun products(): List<BillingProduct> {
        val storeProducts: List<StoreProduct> =
            runCatching { Purchases.sharedInstance.awaitGetProducts(catalog.map { it.productId }) }
                .getOrElse { return emptyList() }

        return storeProducts.mapNotNull { product ->
            val entry = byProductId[product.id] ?: return@mapNotNull null
            BillingProduct(
                packId = entry.packId,
                productId = product.id,
                title = storeTitle(product.title).ifBlank { entry.fallbackTitle },
                description = product.localizedDescription?.takeIf { it.isNotBlank() }
                    ?: entry.fallbackDescription,
                displayPrice = product.price.formatted,
            )
        }
    }

    override suspend fun entitledPackIds(): Set<String> {
        val info = runCatching { Purchases.sharedInstance.awaitCustomerInfo() }
            // 조회 실패는 "아무것도 없음" 과 다르다. 예외를 그대로 올려
            // EntitlementSync 가 캐시를 보존하도록 한다.
            .getOrElse { throw it }
        return info.toPackIds()
    }

    override suspend fun purchase(product: BillingProduct): PurchaseOutcome {
        val storeProduct = runCatching {
            Purchases.sharedInstance.awaitGetProducts(listOf(product.productId)).firstOrNull()
        }.getOrNull() ?: return PurchaseOutcome.Failed("상품을 찾을 수 없다: ${product.productId}")

        return try {
            val result = Purchases.sharedInstance.awaitPurchase(storeProduct)
            // 구매 직후의 customerInfo 를 쓴다. 여기서 다시 조회하면
            // 반영 지연 때문에 방금 산 팩이 빠질 수 있다.
            val active = result.customerInfo.toPackIds()
            if (product.packId in active) {
                // 다른 상품을 이미 보유하고 있어도 이번 구매가 연 팩만 넘긴다.
                // 그래야 로컬 지급 실패의 범위와 구매 확인 문구가 이번 상품에 한정된다.
                PurchaseOutcome.Purchased(setOf(product.packId))
            } else {
                // 스토어 거래가 반환됐더라도 RevenueCat 상품-entitlement 연결이 빠졌다면
                // 콘텐츠는 열리지 않는다. 이 상태를 성공으로 말하면 유저는 결제 후에도
                // 잠긴 서가와 마주친다.
                PurchaseOutcome.Failed("expected entitlement is not active: ${product.packId}")
            }
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) PurchaseOutcome.Cancelled
            else PurchaseOutcome.Failed(e.message ?: "purchase failed")
        } catch (e: Throwable) {
            PurchaseOutcome.Failed(e.message ?: "purchase failed")
        }
    }

    override suspend fun restore(): Set<String> =
        Purchases.sharedInstance.awaitRestore().toPackIds()

    /**
     * 활성 엔티틀먼트 → 이 앱의 보유 팩.
     *
     * 같은 RevenueCat 프로젝트에 다른 앱이나 실험용 entitlement 가 있어도 로컬 DB 에
     * 넣지 않는다. content.db 에 없는 packId 는 지급할 수 없고, 구매 완료 뒤 DB 예외를
     * 만들면 실제 청구 여부와 화면 안내가 어긋날 수 있다.
     */
    private fun CustomerInfo.toPackIds(): Set<String> =
        entitlements.active.keys.filterTo(mutableSetOf()) { it in knownPackIds }
}

/**
 * SDK 초기화. 앱 시작 시 **한 번만** 부른다.
 *
 * 키가 없으면 초기화하지 않고 false 를 돌려준다 — 호출자는 [PreviewBilling] 로 떨어진다.
 * 키 없이도 앱이 온전히 돌아야 한다 (CI, 신규 클론, 무료 유저).
 *
 * @param appUserId null 이면 RevenueCat 이 익명 ID 를 만든다. 우리는 계정이 없으므로 그대로 둔다.
 */
fun configureRevenueCat(apiKey: String, appUserId: String? = null): Boolean {
    if (apiKey.isBlank()) return false
    if (Purchases.isConfigured) return true

    return runCatching {
        Purchases.configure(
            PurchasesConfiguration.Builder(apiKey)
                .apply { if (appUserId != null) appUserId(appUserId) }
                .build()
        )
        true
    }.getOrDefault(false)
}

/**
 * 이 앱이 파는 것. 지금은 전체 컬렉션 하나뿐이다.
 *
 * productId 는 App Store Connect / Play Console 에 등록한 값과 **정확히** 같아야 하고,
 * packId 는 content.db 의 packs.id 및 RevenueCat entitlement 식별자와 같아야 한다.
 */
val ALMANAC_CATALOG: List<BillingCatalogEntry> = listOf(
    BillingCatalogEntry(
        packId = "core-2026",
        productId = "com.dogdduddy.almanac.core2026",
        fallbackTitle = "The 2026 Collection",
        fallbackDescription = "Every passage collected so far, in every weather. " +
            "One purchase, no subscription.",
    ),
)

/**
 * 플랫폼 키를 골라 결제 백엔드를 만든다.
 *
 * 실키를 우선하고, 쓸 수 없으면 Test Store 키로 떨어진다.
 * 둘 다 없으면 [PreviewBilling] — 화면은 보이되 구매는 되지 않는다.
 *
 * 키가 "비었는지" 가 아니라 "형식이 맞는지" 로 고른다. 이유는 [selectBillingKey] 참고.
 */
fun createBilling(platformKey: String): Billing {
    val key = selectBillingKey(platformKey, BillingKeys.TEST) ?: return PreviewBilling
    return if (configureRevenueCat(key)) RevenueCatBilling(ALMANAC_CATALOG) else PreviewBilling
}

internal object PlatformBillingKeys {
    val android: String get() = BillingKeys.ANDROID
    val ios: String get() = BillingKeys.IOS
}

/** Android 조립 지점에서 부른다. */
fun createAndroidBilling(): Billing = createBilling(BillingKeys.ANDROID)

/** iOS 조립 지점에서 부른다. */
fun createIosBilling(): Billing = createBilling(BillingKeys.IOS)
