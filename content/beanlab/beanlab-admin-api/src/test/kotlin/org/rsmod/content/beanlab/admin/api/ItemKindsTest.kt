package org.rsmod.content.beanlab.admin.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemKindsTest {
    @Test
    fun `names the cache uses for non-items are junk`() {
        assertTrue(ItemKinds.isJunkName("null"))
        assertTrue(ItemKinds.isJunkName("Abyssal dummy"))
        assertTrue(ItemKinds.isJunkName("Placeholder"))
        assertTrue(ItemKinds.isJunkName("Dragon claws (beta)"))
        assertTrue(ItemKinds.isJunkName("Twisted bow (unobtainable)"))
    }

    @Test
    fun `real items are not junk by name`() {
        assertFalse(ItemKinds.isJunkName("Abyssal whip"))
        assertFalse(ItemKinds.isJunkName("Shark"))
        assertFalse(ItemKinds.isJunkName("Saradomin brew(4)"))
    }
}
