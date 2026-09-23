package com.dogdduddy.almanac.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 프로모션 코드 판정.
 *
 * **평문은 여기에만 있다.** 앱 바이너리에는 해시만 실린다 — 배포물에서 `strings` 한 번에
 * 드러나지 않게 하려는 것이다. 테스트 바이너리는 배포되지 않으므로 여기 두어도 된다.
 * (이 저장소는 비공개다. 공개로 돌린다면 코드를 새로 발급할 것)
 *
 * 판정이 양 플랫폼에서 같아야 하므로 commonTest 에 둔다. 갈리면 심사위원 절반이 못 연다.
 */
class PromoCodesTest {

    @Test
    fun `발급한 코드를 받는다`() {
        assertTrue(PromoCodes.isAccepted("SHIPATON-2026"))
        assertTrue(PromoCodes.isAccepted("KMP-AWARD-2026"))
        assertTrue(PromoCodes.isAccepted("DESIGN-AWARD-2026"))
    }

    /** 심사위원은 코드를 손으로 옮겨 적는다. 하이픈과 대소문자로 막히면 안 된다. */
    @Test
    fun `대소문자와 구분자는 무시한다`() {
        listOf(
            "shipaton-2026",
            "Shipaton 2026",
            "SHIPATON2026",
            "  shipaton—2026  ",
            "ship-a-ton-2026",
        ).forEach { typed ->
            assertTrue(PromoCodes.isAccepted(typed), "이 입력도 같은 코드여야 한다: $typed")
        }
    }

    @Test
    fun `모르는 코드는 거절한다`() {
        assertFalse(PromoCodes.isAccepted("SHIPATON-2025"))
        assertFalse(PromoCodes.isAccepted("almanac"))
    }

    /**
     * 빈 입력이 통과하면 안 된다.
     *
     * 정규화가 A–Z0–9 만 남기므로 다른 문자 체계로 입력하면 빈 문자열이 된다.
     * 빈 문자열의 해시는 상수 하나라, 검사를 빠뜨리면 그게 만능 코드가 된다.
     */
    @Test
    fun `비어 있거나 기호뿐인 입력은 코드가 아니다`() {
        assertEquals("", PromoCodes.normalize("--- ---"))
        assertFalse(PromoCodes.isAccepted(""))
        assertFalse(PromoCodes.isAccepted("--- ---"))
        assertFalse(PromoCodes.isAccepted("코드"))
    }
}
