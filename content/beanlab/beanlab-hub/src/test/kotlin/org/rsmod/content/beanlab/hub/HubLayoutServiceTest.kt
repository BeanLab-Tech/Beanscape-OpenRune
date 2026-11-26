package org.rsmod.content.beanlab.hub

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.rsmod.content.beanlab.hub.pack.HubMap

class HubLayoutServiceTest {
    private fun service(dir: Path) = HubLayoutService(dir.resolve("hub-layout.json"))

    private fun backups(dir: Path) = dir.listDirectoryEntries("*.bak")

    @Test
    fun `reads the bundled layout until an override exists`() {
        val dir = Files.createTempDirectory("hub")
        val service = service(dir)
        val read = service.read()
        assertEquals("bundled", read.source)
        assertTrue(service.validate(read.text).isEmpty())
    }

    @Test
    fun `write validates first and leaves the file alone on problems`() {
        val dir = Files.createTempDirectory("hub")
        val service = service(dir)
        val problems = service.write("""{"version":2}""")
        assertTrue(problems.any { it.contains("version must be") })
        assertFalse(Files.exists(dir.resolve("hub-layout.json")))
    }

    @Test
    fun `write saves canonical json and backs up the previous override`() {
        val dir = Files.createTempDirectory("hub")
        val service = service(dir)
        val bundled = service.read().text
        assertTrue(service.write(bundled).isEmpty())
        assertEquals("override", service.read().source)
        assertTrue(backups(dir).isEmpty())

        assertTrue(service.write(service.read().text).isEmpty())
        assertEquals(1, backups(dir).size)
        assertEquals(HubMap.parse(bundled).toJson(), service.read().text)
        assertTrue(dir.listDirectoryEntries("*.tmp").isEmpty())
    }
}
