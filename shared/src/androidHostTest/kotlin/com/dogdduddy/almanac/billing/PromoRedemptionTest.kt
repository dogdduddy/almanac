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

/**
 * 프로모션 코드로 여는 경로.
 *
 * **여기서 지키는 것은 하나다 — 코드로 연 서가는 다시 닫히지 않는다.**
 * 동기화는 "결제가 없는 보유 팩" 을 회수하는 것이 일이고, 코드로 받은 팩에는 결제가 없다.
 * 예외로 두지 않으면 심사위원이 코드를 넣은 다음 날 서가가 조용히 닫히고,
 * 화면에는 에러 하나 없이 문장 수만 줄어든다.
 */
class PromoRedemptionTest {

    private lateinit var contentDriver: JdbcSqliteDriver
    private lateinit var userDriver: JdbcSqliteDriver
    private lateinit var repo: AlmanacRepository
    private lateinit var billing: StoreWithNothing
    private lateinit var promo: PromoRedemption

    /** 테스트가 옮길 수 있는 시계. 기본값은 만료 한참 전이다. */
    private var now = 1_800_000_000L

    @BeforeTest
    fun setUp() {
        contentDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ContentDatabase.Schema.create(contentDriver)
        userDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserDatabase.Schema.create(userDriver)

        // starter 는 무료(auto_grant), core 와 테마 팩은 잠겨 있다.
        contentDriver.execute(
            null,
            "INSERT INTO packs (id,title,subtitle,is_base,auto_grant,sort_order) VALUES " +
                "('starter-2026','Starter',NULL,1,1,0), " +
                "('core-2026','Core',NULL,1,0,1), " +
                "('sea-2026','Sea',NULL,0,0,2)",
            0,
        )

        repo = AlmanacRepository(ContentDatabase(contentDriver), UserDatabase(userDriver)) { now }
        repo.ensureBaseEntitlement()
        billing = StoreWithNothing()
        promo = PromoRedemption(repo) { now }
    }

    @AfterTest
    fun tearDown() {
        contentDriver.close(); userDriver.close()
    }

    /**
     * **팩 id 를 박아두지 않는다.** "모든 기능이 열리는 코드" 라고 제출물에 적으므로,
     * 나중에 팩이 늘어도 그 약속이 저절로 지켜져야 한다.
     */
    @Test
    fun `코드는 잠긴 팩을 전부 연다`() {
        assertEquals(RedeemOutcome.Unlocked(setOf("core-2026", "sea-2026")), promo.redeem("SHIPATON-2026"))

        assertEquals(
            listOf("core-2026", "sea-2026", "starter-2026"),
            repo.ownedPackIds(),
        )
    }

    @Test
    fun `모르는 코드는 아무것도 열지 않는다`() {
        assertEquals(RedeemOutcome.UnknownCode, promo.redeem("SHIPATON-2025"))

        assertEquals(listOf("starter-2026"), repo.ownedPackIds())
    }

    /** 이미 산 유저가 코드를 넣어도 손해가 없어야 한다. */
    @Test
    fun `이미 다 가졌으면 그렇다고 답한다`() {
        promo.redeem("SHIPATON-2026")

        assertEquals(RedeemOutcome.AlreadyUnlocked, promo.redeem("SHIPATON-2026"))
        assertEquals(3, repo.ownedPackIds().size)
    }

    /**
     * 이 테스트가 이 파일의 이유다.
     *
     * 동기화는 `보유 - 엔티틀먼트 - 예외` 를 회수한다. 코드로 받은 팩은 스토어에
     * 엔티틀먼트가 없으므로, 예외 목록에 없으면 **첫 동기화에서 바로 사라진다.**
     */
    @Test
    fun `코드로 연 팩은 스토어 동기화가 회수하지 않는다`() = runTest {
        promo.redeem("SHIPATON-2026")

        val afterSync = EntitlementSync(repo, billing).sync()

        assertEquals(setOf("starter-2026", "core-2026", "sea-2026"), afterSync)
    }

    /** 회수 예외는 출처로 가린다. 표시가 어긋나면 위 보호가 조용히 풀린다. */
    @Test
    fun `코드로 연 팩은 출처가 promo 로 남는다`() {
        promo.redeem("SHIPATON-2026")

        assertEquals(
            listOf("core-2026", "sea-2026"),
            repo.packIdsFromSource(PackSource.PROMO),
        )
        assertEquals(listOf("starter-2026"), repo.packIdsFromSource(PackSource.BUNDLED))
    }

    /**
     * 기간을 두는 이유는 사용 횟수를 못 세기 때문이다. 서버가 없어 이 코드는 횟수 제한이
     * 없고, 제출 페이지가 공개되면 그대로 영구 무료 해제가 된다.
     */
    @Test
    fun `기간이 지난 코드는 받지 않는다`() {
        now = PromoCodes.EXPIRES_AT_EPOCH_SECONDS

        assertEquals(RedeemOutcome.Expired, promo.redeem("SHIPATON-2026"))
        assertEquals(listOf("starter-2026"), repo.ownedPackIds())
    }

    /**
     * 만료를 먼저 보면 **아무 문자열이나** "만료됐다" 는 답을 받는다.
     * 코드가 존재한다는 사실이 거기서 샌다.
     */
    @Test
    fun `기간이 지나도 모르는 코드는 모르는 코드다`() {
        now = PromoCodes.EXPIRES_AT_EPOCH_SECONDS

        assertEquals(RedeemOutcome.UnknownCode, promo.redeem("SHIPATON-2025"))
    }

    /** 만료는 새 입력만 막는다. 심사 중에 이미 연 기기가 닫히면 그게 더 나쁘다. */
    @Test
    fun `만료는 이미 열린 기기를 닫지 않는다`() = runTest {
        promo.redeem("SHIPATON-2026")

        now = PromoCodes.EXPIRES_AT_EPOCH_SECONDS + 86_400L

        assertEquals(3, repo.ownedPackIds().size)
        assertEquals(
            setOf("starter-2026", "core-2026", "sea-2026"),
            EntitlementSync(repo, billing).sync(),
        )
    }

    /** 서가 진입점의 조건. 전부 열렸으면 더 보여줄 것이 없다. */
    @Test
    fun `전부 열리면 잠긴 팩이 없다`() {
        assertEquals(listOf("core-2026", "sea-2026"), repo.lockedPackIds())

        promo.redeem("SHIPATON-2026")

        assertEquals(emptyList(), repo.lockedPackIds())
    }
}

/** 아무것도 팔지 않고 아무 엔티틀먼트도 없는 스토어. 회수 압력을 최대로 준다. */
private class StoreWithNothing : Billing {
    override suspend fun products(): List<BillingProduct> = emptyList()
    override suspend fun entitledPackIds(): Set<String> = emptySet()
    override suspend fun purchase(product: BillingProduct) = PurchaseOutcome.Cancelled
    override suspend fun restore(): Set<String> = emptySet()
}
