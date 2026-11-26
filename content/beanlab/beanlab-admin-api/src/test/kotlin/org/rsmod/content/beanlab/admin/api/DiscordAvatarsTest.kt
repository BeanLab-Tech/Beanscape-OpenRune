package org.rsmod.content.beanlab.admin.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordAvatarsTest {
    @Test
    fun `a stored hash becomes the user's own picture`() {
        val url = DiscordAvatars.url(80351110224678912L, "8342729096ea3675442027381ff50dfe")
        assertEquals(
            "https://cdn.discordapp.com/avatars/80351110224678912/8342729096ea3675442027381ff50dfe.png?size=128",
            url,
        )
    }

    @Test
    fun `animated hashes keep their prefix`() {
        val url = DiscordAvatars.url(80351110224678912L, "a_1269e74af4df7417b13759eae50c83dc")
        assertTrue(url.contains("/a_1269e74af4df7417b13759eae50c83dc.png"))
    }

    @Test
    fun `no stored hash falls back to the default avatar for that id`() {
        val id = 80351110224678912L
        val expected = "https://cdn.discordapp.com/embed/avatars/${(id shr 22) % 6}.png"
        assertEquals(expected, DiscordAvatars.url(id, null))
        assertEquals(expected, DiscordAvatars.url(id, ""))
    }

    @Test
    fun `accounts without Discord have no picture`() {
        assertEquals("", DiscordAvatars.url(null, "abc"))
        assertEquals("", DiscordAvatars.url(0L, null))
    }

    @Test
    fun `a hash that could change the link is not used`() {
        val url = DiscordAvatars.url(1234567L, "../../evil?x=1")
        assertFalse(url.contains("evil"))
        assertTrue(url.startsWith("https://cdn.discordapp.com/embed/avatars/"))
    }
}
