package com.dogdduddy.almanac.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillingKeySelectionTest {

    private val realAndroid = "goog_" + "a".repeat(27)
    private val realIos = "appl_" + "b".repeat(27)
    private val testStore = "test_" + "c".repeat(27)

    @Test
    fun realKeysAreUsable() {
        assertTrue(isUsableBillingKey(realAndroid))
        assertTrue(isUsableBillingKey(realIos))
        assertTrue(isUsableBillingKey(testStore))
    }

    @Test
    fun blankKeyIsNotUsable() {
        assertFalse(isUsableBillingKey(""))
        assertFalse(isUsableBillingKey("   "))
    }

    /** 접두사만 맞고 본문이 없는 값. 실제로 local.properties 에 이런 게 들어 있었다. */
    @Test
    fun prefixOnlyPlaceholderIsNotUsable() {
        assertFalse(isUsableBillingKey("appl_"))
        assertFalse(isUsableBillingKey("appl_XXXXX"))
        assertFalse(isUsableBillingKey("goog_TODO"))
    }

    @Test
    fun unknownPrefixIsNotUsable() {
        assertFalse(isUsableBillingKey("sk_live_" + "d".repeat(27)))
        assertFalse(isUsableBillingKey("a".repeat(40)))
    }

    @Test
    fun realKeyWinsOverTestStore() {
        assertEquals(realAndroid, selectBillingKey(realAndroid, testStore))
    }

    @Test
    fun emptyPlatformKeyFallsBackToTestStore() {
        assertEquals(testStore, selectBillingKey("", testStore))
    }

    /**
     * 이 테스트가 이 파일의 존재 이유다.
     *
     * 자리표시자가 Test Store 폴백을 가리면 실키도 Test Store 도 아닌 키로
     * 초기화되어 페이월이 조용히 죽는다.
     */
    @Test
    fun placeholderDoesNotShadowTestStore() {
        assertEquals(testStore, selectBillingKey("appl_XXXXX", testStore))
    }

    @Test
    fun noUsableKeyYieldsNull() {
        assertNull(selectBillingKey("appl_XXXXX", ""))
        assertNull(selectBillingKey("", ""))
    }
}
