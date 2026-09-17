package com.dogdduddy.almanac.billing

import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.data.PackSource

/**
 * 결제 상태 → 보유 팩 동기화.
 *
 * **RevenueCat 이 진실의 원천이고 user.db 의 owned_packs 는 캐시다.**
 * 캐시를 두는 이유는 위젯 때문이다 — 위젯은 네트워크를 기다릴 수 없으므로
 * 로컬에 적힌 보유 팩으로 즉시 그려야 한다.
 *
 * 동기화가 하는 일은 두 가지뿐이다.
 * - 결제로 얻은 팩을 지급한다
 * - **결제가 사라진 팩을 회수한다** (환불, 기기 변경, 구독 만료)
 *
 * 자동 지급 팩(무료 스타터)은 절대 회수하지 않는다. 회수하면 무료 유저의 화면이
 * 통째로 비어버린다.
 */
class EntitlementSync(
    private val repository: AlmanacRepository,
    private val billing: Billing,
) {

    /**
     * @param autoGrantPackIds 설치 시 자동 지급되는 팩. 회수 대상에서 제외된다
     * @return 동기화 후 보유 팩
     */
    suspend fun sync(): Set<String> {
        repository.ensureBaseEntitlement()

        val entitled = runCatching { billing.entitledPackIds() }.getOrElse {
            // 스토어 조회 실패는 흔하다(오프라인 등). 이때 회수하면 비행기 안에서
            // 유료 유저가 콘텐츠를 잃는다. 캐시를 그대로 두고 나간다.
            return repository.ownedPackIds().toSet()
        }

        val owned = repository.ownedPackIds().toSet()
        val protectedPacks = repository.autoGrantPackIds().toSet()

        (entitled - owned).forEach { repository.grantPack(it, PackSource.PURCHASE) }
        (owned - entitled - protectedPacks).forEach { repository.revokePack(it) }

        return repository.ownedPackIds().toSet()
    }

    suspend fun purchase(product: BillingProduct): PurchaseOutcome {
        val outcome = billing.purchase(product)
        if (outcome is PurchaseOutcome.Purchased) {
            // 거래가 끝났다는 사실과 약속한 콘텐츠가 열렸다는 사실은 다르다.
            // RevenueCat 상품-entitlement 연결이 빠졌거나 다른 Billing 구현이 잘못된
            // 결과를 주더라도, 구매한 상품의 packId 가 확인되기 전에는 성공으로 말하지 않는다.
            if (product.packId !in outcome.packIds) {
                return PurchaseOutcome.Failed(
                    "purchased product did not activate entitlement: ${product.packId}"
                )
            }

            // Billing 이 현재 활성 entitlement 전체를 돌려주더라도 이번 상품만 지급한다.
            // 무관한 ID 를 content.db 에 넣다가 구매 완료 뒤 실패하는 일을 막는다.
            repository.grantPack(product.packId, PackSource.PURCHASE)
            return PurchaseOutcome.Purchased(setOf(product.packId))
        }
        return outcome
    }

    /**
     * 기기 변경·재설치 복원.
     *
     * 실패를 삼키지 않고 [RestoreOutcome.Failed] 로 올린다 — 예전에는 스토어 조회
     * 실패도 빈 집합이었고, 화면은 그것을 "복원할 것이 없음" 과 똑같이 취급했다.
     *
     * **이미 가진 팩은 다시 지급하지 않는다.** 재지급은 획득 출처를 RESTORE 로 덮어써,
     * 무료로 받은 스타터가 복원된 구매인 것처럼 기록된다.
     */
    suspend fun restore(): RestoreOutcome {
        val before = repository.ownedPackIds().toSet()
        val restored = runCatching { billing.restore() }
            .getOrElse { return RestoreOutcome.Failed(it.message ?: "store unreachable") }

        val added = restored - before
        added.forEach { repository.grantPack(it, PackSource.RESTORE) }
        return RestoreOutcome.Restored(added)
    }
}
