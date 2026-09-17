package com.dogdduddy.almanac.billing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.data.PackSource
import com.dogdduddy.almanac.db.content.ContentDatabase
import com.dogdduddy.almanac.db.user.UserDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntitlementSyncTest {

    private lateinit var contentDriver: JdbcSqliteDriver
    private lateinit var userDriver: JdbcSqliteDriver
    private lateinit var repo: AlmanacRepository
    private lateinit var billing: FakeBilling
    private lateinit var sync: EntitlementSync

    private val fullPack = BillingProduct(
        packId = "core-2026",
        productId = "com.dogdduddy.almanac.core2026",
        title = "The 2026 Collection",
        description = "Every passage",
        displayPrice = "₩4,900",
    )

    @BeforeTest
    fun setUp() {
        contentDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ContentDatabase.Schema.create(contentDriver)
        userDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserDatabase.Schema.create(userDriver)

        // starter 는 무료(auto_grant), core 는 유료.
        contentDriver.execute(
            null,
            "INSERT INTO packs (id,title,subtitle,is_base,auto_grant,sort_order) VALUES " +
                "('starter-2026','Starter',NULL,1,1,0), ('core-2026','Core',NULL,1,0,1)",
            0,
        )

        repo = AlmanacRepository(ContentDatabase(contentDriver), UserDatabase(userDriver)) { 1_800_000_000L }
        billing = FakeBilling()
        sync = EntitlementSync(repo, billing)
    }

    @AfterTest
    fun tearDown() {
        contentDriver.close(); userDriver.close()
    }

    @Test
    fun syncGrantsFreeStarterEvenWithNoPurchases() = runTest {
        assertEquals(setOf("starter-2026"), sync.sync())
    }

    @Test
    fun entitlementFromStoreGrantsThePack() = runTest {
        billing.entitled = setOf("core-2026")
        assertEquals(setOf("starter-2026", "core-2026"), sync.sync())
    }

    /** 환불·기기 변경으로 결제가 사라지면 회수해야 한다. */
    @Test
    fun losingEntitlementRevokesThePack() = runTest {
        billing.entitled = setOf("core-2026")
        sync.sync()

        billing.entitled = emptySet()
        assertEquals(setOf("starter-2026"), sync.sync())
    }

    /**
     * 무료 팩은 절대 회수하지 않는다.
     * 회수하면 무료 유저의 화면이 통째로 빈다 — 결제와 무관한 사고다.
     */
    @Test
    fun autoGrantPackIsNeverRevoked() = runTest {
        sync.sync()
        billing.entitled = emptySet()
        assertTrue("starter-2026" in sync.sync())
    }

    /**
     * 스토어 조회 실패(오프라인 등)에 회수하면 비행기 안에서 유료 유저가 콘텐츠를 잃는다.
     * 캐시를 그대로 둬야 한다.
     */
    @Test
    fun storeFailureKeepsCachedEntitlements() = runTest {
        billing.entitled = setOf("core-2026")
        sync.sync()

        billing.failEntitlements = true
        assertEquals(setOf("starter-2026", "core-2026"), sync.sync())
    }

    @Test
    fun purchaseGrantsImmediately() = runTest {
        sync.sync()
        val outcome = sync.purchase(fullPack)
        assertTrue(outcome is PurchaseOutcome.Purchased)
        assertTrue("core-2026" in repo.ownedPackIds())
    }

    @Test
    fun cancelledPurchaseGrantsNothing() = runTest {
        sync.sync()
        billing.cancelNext = true
        assertEquals(PurchaseOutcome.Cancelled, sync.purchase(fullPack))
        assertTrue("core-2026" !in repo.ownedPackIds())
    }

    /** 거래 응답이 와도 약속한 entitlement 가 없으면 성공으로 말하면 안 된다. */
    @Test
    fun purchaseWithoutExpectedEntitlementFailsWithoutGranting() = runTest {
        sync.sync()
        billing.purchaseOutcome = PurchaseOutcome.Purchased(emptySet())

        assertTrue(sync.purchase(fullPack) is PurchaseOutcome.Failed)
        assertTrue("core-2026" !in repo.ownedPackIds())
    }

    /** 현재 활성 entitlement 전체가 와도 이번에 산 팩 외에는 로컬에 지급하지 않는다. */
    @Test
    fun purchaseGrantsOnlyThePurchasedPack() = runTest {
        sync.sync()
        billing.purchaseOutcome = PurchaseOutcome.Purchased(setOf("core-2026", "other-app-pro"))

        assertEquals(
            PurchaseOutcome.Purchased(setOf("core-2026")),
            sync.purchase(fullPack),
        )
        assertEquals(setOf("starter-2026", "core-2026"), repo.ownedPackIds().toSet())
    }

    /** 스토어 정책상 복원은 반드시 제공해야 한다. */
    @Test
    fun restoreGrantsPreviousPurchases() = runTest {
        sync.sync()
        billing.restorable = setOf("core-2026")

        assertEquals(RestoreOutcome.Restored(setOf("core-2026")), sync.restore())
        assertTrue("core-2026" in repo.ownedPackIds())
    }

    /**
     * **복원할 것이 없는 것과 스토어에 못 물어본 것은 다르다.**
     *
     * 예전에는 둘 다 빈 집합이었다. 그 상태로 화면에 결과를 붙이면, 비행기 안에서
     * 복원을 누른 유료 유저가 "구매 내역이 없습니다" 를 보게 된다.
     */
    @Test
    fun restoreFailureIsNotAnEmptyRestore() = runTest {
        billing.entitled = setOf("core-2026")
        sync.sync()
        billing.failRestore = true

        assertTrue(sync.restore() is RestoreOutcome.Failed)
        assertTrue("core-2026" in repo.ownedPackIds(), "실패가 보유 팩을 지우면 안 된다")
    }

    /** 복원할 구매가 없었다. 실패가 아니라 빈 성공이다. */
    @Test
    fun restoreWithNothingToRestoreSucceedsEmpty() = runTest {
        sync.sync()
        billing.restorable = emptySet()

        assertEquals(RestoreOutcome.Restored(emptySet()), sync.restore())
    }

    /** 이미 가진 팩을 다시 지급하면 획득 출처 기록이 RESTORE 로 덮인다. */
    @Test
    fun restoreDoesNotRegrantPacksAlreadyOwned() = runTest {
        billing.entitled = setOf("core-2026")
        sync.sync()
        billing.restorable = setOf("core-2026")

        assertEquals(RestoreOutcome.Restored(emptySet()), sync.restore())
    }

    /** 키가 없어도 앱은 돌아야 한다 — 무료 콘텐츠는 그대로. */
    @Test
    fun unconfiguredBillingStillGrantsFreeContent() = runTest {
        val offline = EntitlementSync(repo, NoBilling)
        assertEquals(setOf("starter-2026"), offline.sync())
    }
}

private class FakeBilling : Billing {
    var entitled: Set<String> = emptySet()
    var restorable: Set<String> = emptySet()
    var failEntitlements = false
    var failRestore = false
    var cancelNext = false
    var purchaseOutcome: PurchaseOutcome? = null

    override suspend fun products() = emptyList<BillingProduct>()

    override suspend fun entitledPackIds(): Set<String> {
        if (failEntitlements) error("store unreachable")
        return entitled
    }

    override suspend fun purchase(product: BillingProduct): PurchaseOutcome {
        if (cancelNext) {
            cancelNext = false
            return PurchaseOutcome.Cancelled
        }
        purchaseOutcome?.let { return it }
        entitled = entitled + product.packId
        return PurchaseOutcome.Purchased(setOf(product.packId))
    }

    override suspend fun restore(): Set<String> {
        if (failRestore) error("store unreachable")
        return restorable
    }
}
