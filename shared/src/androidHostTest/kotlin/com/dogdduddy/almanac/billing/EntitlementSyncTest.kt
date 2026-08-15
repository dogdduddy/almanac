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

    /** 스토어 정책상 복원은 반드시 제공해야 한다. */
    @Test
    fun restoreGrantsPreviousPurchases() = runTest {
        sync.sync()
        billing.restorable = setOf("core-2026")
        assertTrue("core-2026" in sync.restore())
    }

    @Test
    fun restoreFailureDoesNotWipeExistingPacks() = runTest {
        billing.entitled = setOf("core-2026")
        sync.sync()
        billing.failRestore = true
        assertTrue("core-2026" in sync.restore())
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
        entitled = entitled + product.packId
        return PurchaseOutcome.Purchased(setOf(product.packId))
    }

    override suspend fun restore(): Set<String> {
        if (failRestore) error("store unreachable")
        return restorable
    }
}
