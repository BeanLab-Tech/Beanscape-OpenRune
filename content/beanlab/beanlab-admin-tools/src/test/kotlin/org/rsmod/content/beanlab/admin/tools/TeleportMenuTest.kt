package org.rsmod.content.beanlab.admin.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeleportMenuTest {
    private fun coords(raw: String) = parseCoords(raw)?.let { Triple(it.x, it.z, it.level) }

    @Test
    fun `parses x z and optional level`() {
        assertEquals(Triple(3222, 3218, 0), coords("3222 3218"))
        assertEquals(Triple(3222, 3218, 1), coords(" 3222, 3218, 1 "))
    }

    @Test
    fun `rejects anything else`() {
        assertNull(parseCoords(""))
        assertNull(parseCoords("3222"))
        assertNull(parseCoords("a b"))
        assertNull(parseCoords("3222 3218 0 9"))
        assertNull(parseCoords("3222 3218 4"))
        assertNull(parseCoords("-1 3218"))
    }
}
