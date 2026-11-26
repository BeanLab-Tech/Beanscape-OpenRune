package org.rsmod.content.beanlab.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeleportCatalogTest {
    private val all = TeleportCatalog.categories.flatMap { it.destinations }

    @Test
    fun `every destination is a real tile`() {
        for (dest in all) {
            val onMap = dest.x in 1000..4000 && dest.z in 2000..13000
            assertTrue(onMap, "${dest.name}: ${dest.x}, ${dest.z}")
            assertTrue(dest.level in 0..3, "${dest.name}: level ${dest.level}")
        }
    }

    @Test
    fun `names are unique within a category`() {
        for (category in TeleportCatalog.categories) {
            val names = category.destinations.map { it.name.lowercase() }
            assertEquals(names.size, names.toSet().size, "duplicate name in ${category.name}")
        }
    }

    @Test
    fun `search ignores case and finds lumbridge`() {
        assertTrue(TeleportCatalog.search("LUMBRIDGE").any { it.name == "Lumbridge" })
        assertTrue(TeleportCatalog.search("no such place").isEmpty())
    }
}
