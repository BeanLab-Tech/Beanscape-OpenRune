package org.rsmod.content.beanlab.hub

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.rsmod.content.beanlab.hub.pack.HubMap
import org.rsmod.content.beanlab.hub.pack.HubMapException
import org.rsmod.content.beanlab.hub.pack.NpcPlacement
import org.rsmod.content.beanlab.hub.pack.Rect
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape

class HubEditorTest {
    private fun editor(): HubEditor =
        HubEditor(
            HubMap.parse(
                """
                {"version":1,"size":50,"palette":{"g":1,"in":3},"ground":"g",
                 "floors":[],"detail":[],
                 "rooms":[{"name":"A","rect":[20,20,30,30],"floor":"in","wall":"w","doors":[]}],
                 "walls":[{"loc":"w","line":[2,10,6,10],"angle":"north"}],
                 "locs":[],"npcs":[{"npc":"zeke","x":25,"z":25}],
                 "arrival":{"x":5,"z":5},"leavePortal":{"x":40,"z":40}}
                """
                    .trimIndent()
            )
        )

    @Test
    fun `places and removes an npc`() {
        val e = editor()
        assertTrue(e.placeNpc("me", "bob", 8, 8).ok)
        assertTrue(NpcPlacement("bob", 8, 8) in e.map.npcs)
        assertTrue(e.remove("me", 8, 8).ok)
        assertFalse(e.map.npcs.any { it.npc == "bob" })
    }

    @Test
    fun `refuses npcs and objects on walls but lets wall decor share a wall tile`() {
        val e = editor()
        assertFalse(e.placeNpc("me", "bob", 3, 10).ok)
        assertFalse(
            e.placeLoc("me", "chair", 3, 10, LocShape.CentrepieceStraight, LocAngle.South).ok
        )
        assertTrue(
            e.placeLoc("me", "board", 3, 10, LocShape.WallDecorStraightNoOffset, LocAngle.North).ok
        )
        // Room outline tiles count as walls too.
        assertFalse(e.placeNpc("me", "bob", 20, 25).ok)
    }

    @Test
    fun `refuses tiles outside the hub and special tiles`() {
        val e = editor()
        assertFalse(e.placeNpc("me", "bob", -1, 3).ok)
        assertFalse(e.placeNpc("me", "bob", 50, 3).ok)
        assertFalse(e.placeNpc("me", "bob", 5, 5).ok, "arrival tile")
        assertFalse(e.placeNpc("me", "bob", 41, 41).ok, "leave portal footprint")
        assertFalse(e.placeWall("me", "w", 48, 3, LocAngle.North, 5).ok, "run leaves the hub")
        assertFalse(e.remove("me", 99, 99).ok)
    }

    @Test
    fun `wall runs go east for north south walls and north for east west walls`() {
        val e = editor()
        assertTrue(e.placeWall("me", "w", 10, 3, LocAngle.South, 3).ok)
        assertEquals(Rect(10, 3, 12, 3), e.map.walls.last().line)
        assertTrue(e.placeWall("me", "w", 15, 3, LocAngle.East, 4).ok)
        assertEquals(Rect(15, 3, 15, 6), e.map.walls.last().line)
        assertFalse(e.placeWall("me", "w", 11, 3, LocAngle.South, 1).ok, "already a wall piece")
        assertFalse(e.placeWall("me", "w", 10, 3, LocAngle.South, 0).ok)
    }

    @Test
    fun `removing a wall piece splits its run`() {
        val e = editor()
        // The run is x 2..6 at z 10; stand next to the middle piece and remove it.
        assertTrue(e.remove("me", 4, 9).ok)
        assertEquals(listOf(Rect(2, 10, 3, 10), Rect(5, 10, 6, 10)), e.map.walls.map { it.line })
        assertTrue(e.remove("me", 2, 10).ok)
        assertTrue(e.remove("me", 3, 10).ok)
        assertEquals(listOf(Rect(5, 10, 6, 10)), e.map.walls.map { it.line })
    }

    @Test
    fun `room wall pieces are reported rather than removed`() {
        val e = editor()
        val result = e.remove("me", 20, 25)
        assertFalse(result.ok)
        assertTrue("room 'A'" in result.message)
    }

    @Test
    fun `remove prefers the own tile before neighbours`() {
        val e = editor()
        e.placeNpc("me", "bob", 8, 8)
        e.placeLoc("me", "chair", 9, 8, LocShape.CentrepieceStraight, LocAngle.South)
        assertTrue(e.remove("me", 9, 8).message.startsWith("removed loc chair"))
        assertTrue(e.remove("me", 9, 8).message.startsWith("removed npc bob"))
    }

    @Test
    fun `undo reverts changes in reverse order`() {
        val e = editor()
        val start = e.map.toJson()
        e.placeNpc("me", "bob", 8, 8)
        e.placeWall("me", "w", 10, 3, LocAngle.South, 3)
        e.remove("me", 8, 8)
        assertTrue(e.undo().ok)
        assertTrue(e.map.npcs.any { it.npc == "bob" })
        assertTrue(e.undo().ok)
        assertTrue(e.undo().ok)
        assertEquals(start, e.map.toJson())
        assertFalse(e.undo().ok)
        assertFalse(e.canUndo)
    }

    @Test
    fun `diff lists only the spawns that changed`() {
        val e = editor()
        val before = e.map
        e.placeNpc("me", "bob", 8, 8)
        e.placeWall("me", "w", 10, 3, LocAngle.South, 2)
        val diff = HubDiff.between(before, e.map)
        assertEquals(listOf(NpcPlacement("bob", 8, 8)), diff.addedNpcs)
        assertEquals(2, diff.addedWalls.size)
        assertTrue(diff.removedNpcs.isEmpty() && diff.removedWalls.isEmpty())
        val back = HubDiff.between(e.map, before)
        assertEquals(2, back.removedWalls.size)
        assertTrue(HubDiff.between(before, before).isEmpty)
    }

    @Test
    fun `save writes a file that loads back to the same layout and keeps a backup`() {
        val e = editor()
        val dir = Files.createTempDirectory("hub-save")
        val file = dir.resolve("sub/hub-layout.json")
        e.placeNpc("me", "bob", 8, 8)
        HubEditor.save(e.map, file)
        assertEquals(e.map.toJson(), HubMap.parse(Files.readString(file)).toJson())

        e.placeLoc("me", "chair", 9, 9, LocShape.CentrepieceStraight, LocAngle.North)
        HubEditor.save(e.map, file)
        val backup = file.resolveSibling("hub-layout.json.bak")
        assertTrue(Files.isRegularFile(backup))
        assertFalse(HubMap.parse(Files.readString(backup)).locs.any { it.loc == "chair" })
        assertTrue(HubMap.parse(Files.readString(file)).locs.any { it.loc == "chair" })
        assertEquals(
            setOf("hub-layout.json", "hub-layout.json.bak"),
            Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList().toSet() },
        )
    }

    @Test
    fun `save refuses an invalid layout and writes nothing`() {
        val good = editor().map
        // An npc moved onto a room wall bypasses the editor checks the way a bad file would.
        val bad = good.copyWith(npcs = listOf(NpcPlacement("zeke", 20, 25)))
        val e = HubEditor(good)
        assertTrue(e.problems().isEmpty())
        assertTrue(HubEditor.validate(bad).isNotEmpty())
        val file = Files.createTempDirectory("hub-bad").resolve("hub-layout.json")
        assertFailsWith<HubMapException> { HubEditor.save(bad, file) }
        assertFalse(Files.exists(file))
    }

    @Test
    fun `the bundled layout survives edits and a save round trip`() {
        val e = HubEditor(HubMapLoader.bundled())
        val a = e.map.arrival
        assertTrue(e.placeNpc("me", "zeke", a.x + 1, a.z).ok)
        assertTrue(e.problems().isEmpty())
        val file = Files.createTempDirectory("hub-bundled").resolve("hub-layout.json")
        HubEditor.save(e.map, file)
        assertEquals(e.map.toJson(), HubMap.parse(Files.readString(file)).toJson())
    }
}
