package org.rsmod.content.beanlab.admin.tools

import dev.or2.central.account.Rights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RottenPotatoTest {
    @Test
    fun `players get no actions`() {
        assertTrue(potatoActions(Rights.NONE).isEmpty())
    }

    @Test
    fun `moderators get only the moderator actions`() {
        assertEquals(
            listOf(PotatoAction.Teleports, PotatoAction.MyPosition, PotatoAction.KickMe),
            potatoActions(Rights.MOD),
        )
    }

    @Test
    fun `administrators get every action`() {
        assertEquals(PotatoAction.entries, potatoActions(Rights.ADMINISTRATOR))
    }

    @Test
    fun `nobody can raise their own rights`() {
        assertEquals(listOf(Rights.NONE), selectableRights(Rights.NONE))
        assertEquals(listOf(Rights.NONE, Rights.MOD), selectableRights(Rights.MOD))
        assertEquals(Rights.entries.toList(), selectableRights(Rights.ADMINISTRATOR))
    }
}
