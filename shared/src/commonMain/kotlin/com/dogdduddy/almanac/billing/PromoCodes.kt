package com.dogdduddy.almanac.billing

import com.dogdduddy.almanac.core.fnv1a64
import com.dogdduddy.almanac.data.AlmanacRepository
import com.dogdduddy.almanac.data.PackSource

/**
 * 코드 입력 결과. [PurchaseOutcome] 과 같은 모양을 따른다 —
 * 열렸다는 소식은 구매와 같은 경로(`PurchaseState`)로 화면에 가고,
 * 코드 자체의 문제만 입력란 옆에서 말한다.
 */
sealed interface RedeemOutcome {

    /** @param packIds 이번에 열린 팩. 확인 문구가 문장 수를 세는 데 쓴다 */
    data class Unlocked(val packIds: Set<String>) : RedeemOutcome

    /** 코드는 맞지만 이미 다 갖고 있다. 구매자가 코드를 넣어도 손해가 없어야 한다. */
    data object AlreadyUnlocked : RedeemOutcome

    data object UnknownCode : RedeemOutcome

    /** 코드는 맞지만 기간이 지났다. 대회가 끝난 뒤에도 무한정 열리지 않게 한다. */
    data object Expired : RedeemOutcome

    /** DB 쓰기 실패 등. 코드 탓이 아니므로 "틀린 코드" 로 안내하면 안 된다. */
    data class Failed(val message: String) : RedeemOutcome
}

/**
 * 프로모션 코드.
 *
 * **이것은 백업이다.** 심사위원에게 주는 정식 경로는 스토어 코드(iOS Offer Code /
 * Play 프로모션)이고, 그쪽만이 실제 구매 파이프라인을 타서 **RevenueCat 연동을 증명한다.**
 * 여기 있는 코드는 그 파이프라인을 **타지 않는다** — 엔티틀먼트 없이 로컬에서 팩을 지급한다.
 *
 * 그래도 두는 이유는 하나다. 스토어 코드는 **앱이 심사를 통과해 판매 중이어야** 나온다.
 * 마감까지 한쪽 스토어가 안 열리면 그쪽 심사위원에게는 줄 것이 없어지고,
 * 그러면 심사위원이 본 것은 무료 버전뿐이다. 그 경우에만 쓰는 비상구다.
 *
 * 자세한 판단과 스토어 설정은 docs/decisions/promo-code.md 참고.
 *
 * **평문을 소스에 두지 않는다.** APK/IPA 는 공개물이라 `strings` 한 번이면 드러난다.
 * 정규화한 코드의 FNV-1a 64 해시만 싣고, 평문은 (비공개인) 저장소 문서에 적는다.
 * 해시는 암호학적 방어가 아니라 **거저 줍는 난이도**다 — 클라이언트에서 판정하는 이상
 * 마음먹은 사람은 어차피 우회한다. 막으려는 것은 우연한 노출이다.
 *
 * 해시를 시드와 같은 [fnv1a64] 로 쓰는 이유는 이미 양 플랫폼에서 골든 벡터로 고정돼
 * 있기 때문이다. 코드 판정이 iOS 와 Android 에서 갈리면 심사위원 절반이 못 연다.
 */
object PromoCodes {

    /**
     * 정규화한 코드의 해시.
     *
     * 새 코드를 더하려면 `python3 scripts/promo_code.py <CODE>` 로 해시를 뽑아 여기 넣는다.
     * 평문은 docs/decisions/promo-code.md 에 있다.
     */
    private val ACCEPTED: Set<ULong> = setOf(
        6680091432544335221uL,   // 심사용 · Shipaton
        14525547026936660150uL,  // 심사용 · KMP Award
        11848185665498842550uL,  // 심사용 · Design Award
    )

    /**
     * 2027-01-31 00:00 UTC. 이 시각을 넘기면 코드를 더는 받지 않는다.
     *
     * **기간을 두는 이유는 사용 횟수를 못 세기 때문이다.** 서버가 없으므로 이 코드는
     * 횟수 제한이 없고, 제출 페이지가 공개되면 그대로 영구 무료 해제가 된다
     * (스토어 코드는 1회용이라 이 문제가 없다).
     *
     * 심사와 후속 발표가 끝나고도 한참 뒤로 잡았다. 짧게 잡아 심사 중에 막히는 쪽이
     * 훨씬 나쁘다 — 그때는 고칠 방법이 앱 업데이트뿐이다.
     *
     * **이미 열린 기기는 건드리지 않는다.** 만료는 새 입력만 막는다.
     */
    const val EXPIRES_AT_EPOCH_SECONDS: Long = 1_801_353_600L

    fun isAccepted(raw: String): Boolean {
        val normalized = normalize(raw)
        return normalized.isNotEmpty() && fnv1a64(normalized) in ACCEPTED
    }

    fun isExpired(nowEpochSeconds: Long): Boolean =
        nowEpochSeconds >= EXPIRES_AT_EPOCH_SECONDS

    /**
     * 대소문자·하이픈·공백을 지운다.
     *
     * 심사위원은 코드를 손으로 옮겨 적는다. `shipaton 2026`, `Shipaton-2026`,
     * `SHIPATON2026` 이 전부 같은 코드여야 한다. 반대로 A–Z0–9 밖의 문자는 전부 버리므로
     * 다른 문자 체계로 입력하면 빈 문자열이 되고, 그건 코드로 치지 않는다.
     */
    internal fun normalize(raw: String): String =
        raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
}

/**
 * 코드를 받아 잠긴 팩을 연다.
 *
 * **특정 팩 id 를 박아두지 않는다.** content.db 에 있는데 아직 보유하지 않은 팩을 전부 연다 —
 * 요구가 "모든 기능이 열리는 코드" 이므로, 나중에 테마 팩이 늘어도 문서와 코드가 어긋나지 않는다.
 *
 * 지급 출처는 [PackSource.PROMO] 다. 이 표시가 없으면 다음 스토어 동기화에서
 * "결제가 없는 보유 팩" 으로 보여 **바로 회수된다** ([EntitlementSync] 참고).
 */
class PromoRedemption(
    private val repository: AlmanacRepository,
    private val nowEpochSeconds: () -> Long,
) {

    fun redeem(rawCode: String): RedeemOutcome {
        // 순서가 중요하다. 만료를 먼저 보면 **아무 문자열이나** "만료됐다" 는 답을 받아,
        // 코드가 존재한다는 사실이 새어 나간다.
        if (!PromoCodes.isAccepted(rawCode)) return RedeemOutcome.UnknownCode
        if (PromoCodes.isExpired(nowEpochSeconds())) return RedeemOutcome.Expired

        return runCatching {
            val locked = repository.lockedPackIds()
            if (locked.isEmpty()) return@runCatching RedeemOutcome.AlreadyUnlocked
            locked.forEach { repository.grantPack(it, PackSource.PROMO) }
            RedeemOutcome.Unlocked(locked.toSet())
        }.getOrElse { RedeemOutcome.Failed(it.message ?: "redeem failed") }
    }
}
