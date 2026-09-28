package com.dogdduddy.almanac.billing

import kotlin.test.Test
import kotlin.test.assertEquals

class StoreTitleTest {

    @Test
    fun `Play 가 붙인 앱 이름을 뗀다`() {
        assertEquals(
            "The 2026 Collection",
            storeTitle("The 2026 Collection (Almanac - Weathered Words)"),
        )
    }

    @Test
    fun `괄호가 없으면 그대로 둔다`() {
        assertEquals("The 2026 Collection", storeTitle("The 2026 Collection"))
    }

    @Test
    fun `앞이나 가운데의 괄호는 건드리지 않는다`() {
        assertEquals("Core 2026 (Base) Pack", storeTitle("Core 2026 (Base) Pack"))
    }

    @Test
    fun `괄호 하나만 뗀다`() {
        assertEquals("Core (2026)", storeTitle("Core (2026) (Almanac)"))
    }

    @Test
    fun `제목 전체가 괄호면 그대로 둔다`() {
        assertEquals("(Almanac)", storeTitle("(Almanac)"))
    }
}
