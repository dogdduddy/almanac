package com.dogdduddy.almanac.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 골든 벡터. 이 값들은 참조 구현(FNV-1a 64, UTF-8)에서 독립적으로 계산한 것이다.
 *
 * 이 테스트는 JVM(Android)과 Kotlin/Native(iOS) **양쪽에서 돌아간다.**
 * 한쪽이라도 깨지면 "iOS와 Android가 같은 문장을 낸다"는 심사 기준이 무너진 것이므로,
 * 절대 기대값을 실제값에 맞춰 수정하지 말 것 — 구현을 고쳐야 한다.
 */
class HashingTest {

    @Test
    fun emptyStringIsOffsetBasis() {
        assertEquals(14695981039346656037uL, fnv1a64(""))
    }

    @Test
    fun asciiGoldenVector() {
        assertEquals(11120692528075096692uL, fnv1a64("almanac"))
    }

    @Test
    fun seedInputGoldenVectors() {
        assertEquals(10957371043090967141uL, fnv1a64("2026-08-04|morning|rain|test-install"))
        assertEquals(18125747158860527040uL, fnv1a64("2026-08-04|day|clear|test-install"))
        assertEquals(17652242266243946143uL, fnv1a64("2026-08-04|evening_night|snow|test-install"))
    }

    /** 일본어 콘텐츠가 들어가므로 멀티바이트 UTF-8 처리가 플랫폼 간 같아야 한다. */
    @Test
    fun multibyteUtf8GoldenVector() {
        assertEquals(1099953915796777258uL, fnv1a64("비|눈|바람"))
    }
}
