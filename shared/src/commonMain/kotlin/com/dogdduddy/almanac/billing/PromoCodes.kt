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

    /** DB 쓰기 실패 등. 코드 탓이 아니므로 "틀린 코드" 로 안내하면 안 된다. */
    data class Failed(val message: String) : RedeemOutcome
}

/**
 * 프로모션 코드.
 *
 * **왜 스토어 프로모 코드가 아니라 앱 안에서 처리하는가.**
 * App Store 프로모 코드는 앱이 이미 심사를 통과해 판매 중이어야 발급되고 iOS 에만 있다.
 * Play 는 별도 체계이고 심사위원이 Play 계정으로 테스트 트랙에 들어와야 한다.
 * 제출물에 **한 줄로 적어 어느 기기에서든 즉시 열리는 코드**가 필요하므로 앱이 직접 받는다.
 * 스토어 프로모 코드는 그것대로 발급해 병행한다 — 둘은 배타적이지 않다.
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

    fun isAccepted(raw: String): Boolean {
        val normalized = normalize(raw)
        return normalized.isNotEmpty() && fnv1a64(normalized) in ACCEPTED
    }

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
class PromoRedemption(private val repository: AlmanacRepository) {

    fun redeem(rawCode: String): RedeemOutcome {
        if (!PromoCodes.isAccepted(rawCode)) return RedeemOutcome.UnknownCode

        return runCatching {
            val locked = repository.lockedPackIds()
            if (locked.isEmpty()) return@runCatching RedeemOutcome.AlreadyUnlocked
            locked.forEach { repository.grantPack(it, PackSource.PROMO) }
            RedeemOutcome.Unlocked(locked.toSet())
        }.getOrElse { RedeemOutcome.Failed(it.message ?: "redeem failed") }
    }
}
