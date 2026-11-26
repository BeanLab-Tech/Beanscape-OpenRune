package org.rsmod.content.beanlab.admin.api

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapEditLogTest {
    private fun log(): MapEditLog {
        val dir = Files.createTempDirectory("map-edits")
        return MapEditLog(dir.resolve("map-edits.json"))
    }

    private fun loc(op: String, name: String = "chair") =
        MapEditLog.LocEdit(op, 3200, 3200, 0, name, 10, 0)

    @Test
    fun `removing an added object drops the add`() {
        val log = log()
        log.recordLoc(loc(MapEditLog.OP_ADD))
        val dropped = log.recordLoc(loc(MapEditLog.OP_REMOVE))
        assertTrue(dropped)
        assertEquals(0, log.locs.size)
    }

    @Test
    fun `removing a cache object is kept and reloaded`() {
        val log = log()
        log.recordLoc(loc(MapEditLog.OP_REMOVE, "tree"))
        log.save()
        val again = MapEditLog(log.path)
        again.load()
        assertEquals(listOf("tree"), again.locs.map { it.name })
        assertEquals(MapEditLog.OP_REMOVE, again.locs.single().op)
    }

    @Test
    fun `adding again replaces a removal of the same object`() {
        val log = log()
        log.recordLoc(loc(MapEditLog.OP_REMOVE))
        log.recordLoc(loc(MapEditLog.OP_ADD))
        assertEquals(MapEditLog.OP_ADD, log.locs.single().op)
    }

}
