package com.dogdduddy.almanac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallIdTest {

    /**
     * 설치 ID 가 겹치면 콘텐츠 순서까지 겹친다.
     * 초 단위 시각을 시드로 쓰던 시절, 같은 초에 처음 실행한 설치들이 같은 값을 받았다.
     */
    @Test
    fun everyInstallIdIsDistinct() {
        val ids = List(1_000) { newInstallId() }
        assertEquals(ids.size, ids.toSet().size, "설치 ID 가 겹쳤다")
    }

    /** 시드 입력이므로 형식이 흔들리면 안 된다 — 소문자 UUID 고정. */
    @Test
    fun installIdIsLowercaseUuidShaped() {
        val id = newInstallId()
        assertEquals(id.lowercase(), id)
        assertEquals(36, id.length)
        assertEquals(listOf(8, 13, 18, 23), id.indices.filter { id[it] == '-' })
        assertTrue(id.all { it == '-' || it in "0123456789abcdef" }, "16진수와 하이픈만 남아야 한다: $id")
    }
}
