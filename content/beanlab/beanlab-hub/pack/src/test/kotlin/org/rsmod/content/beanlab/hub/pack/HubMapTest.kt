package org.rsmod.content.beanlab.hub.pack

import dev.openrune.map.loc.MapLocListDecoder
import dev.openrune.map.tile.MapTileDecoder
import dev.openrune.map.util.InlineByteBuf
import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HubMapTest {
    private fun bundled(): HubMap = HubMap.parse(HubSquares.bundledText())

    /** A tiny valid layout to break one field at a time. */
    private fun layout(
        floors: String = """[{"rect":[0,0,9,9],"floor":"path"}]""",
        rooms: String =
            """[{"name":"A","rect":[20,20,30,30],"floor":"in","wall":"w","doors":[{"side":"west","from":24,"to":26}]}]""",
        npcs: String = """[{"npc":"zeke","x":25,"z":25}]""",
        arrival: String = """{"x":5,"z":5}""",
    ) =
        """
        {"version":1,"size":50,"palette":{"g":1,"path":2,"in":3},"ground":"g",
         "floors":$floors,"rooms":$rooms,"detail":[],"walls":[],"locs":[],"npcs":$npcs,
         "arrival":$arrival,"leavePortal":{"x":6,"z":6}}
        """
            .trimIndent()

    private fun problems(text: String): List<String> =
        assertFailsWith<HubMapException> { HubMap.parse(text) }.problems

    @Test
    fun `generated terrain decodes to coloured level zero tiles`() {
        val map = bundled()
        for ((x, z) in HubSquares.squares()) {
            val bytes = map.terrain(x, z)
            val buf = Unpooled.wrappedBuffer(bytes)
            val def = MapTileDecoder.decode(buf)
            assertEquals(64 * 64, def.underlays.size, "m${x}_$z underlays")
            assertTrue(def.overlays.isEmpty())
            assertEquals(0, buf.readableBytes(), "m${x}_$z has trailing bytes")
            MapTileDecoder.decode(InlineByteBuf(bytes))
        }
    }

    @Test
    fun `the packed loc list is empty`() {
        val def = MapLocListDecoder.decode(InlineByteBuf(byteArrayOf(0)))
        assertTrue(def.spawns.isEmpty())
    }

    @Test
    fun `bundled layout has the expected shape`() {
        val map = bundled()
        assertEquals(100, map.size)
        assertEquals(9, map.rooms.size)
        assertEquals(13, map.npcs.size)
        // 8 shops + hall = 9 rooms, 4 corners each.
        assertEquals(9 * 4, map.wallPieces().count { it.corner })
    }

    @Test
    fun `doorways are left open and the rest of the edge is walled`() {
        val map = bundled()
        val weapons = map.rooms.first { it.name == "Weapons" }
        val pieces = map.wallPieces().filter { it.loc == weapons.wall }
        val west = pieces.filter { it.x == weapons.rect.x0 && !it.corner }
        // West edge interior tiles are z 83..94 (12), minus the 3-tile door at 87..89.
        assertEquals(12 - 3, west.count { it.z in 83..94 })
        assertTrue(west.none { it.z in 87..89 })
    }

    @Test
    fun `writing a layout and reading it back gives the same map`() {
        val map = bundled()
        val again = HubMap.parse(map.toJson())
        assertEquals(map.rooms, again.rooms)
        assertEquals(map.walls, again.walls)
        assertEquals(map.locs, again.locs)
        assertEquals(map.npcs, again.npcs)
        assertEquals(map.arrival, again.arrival)
        assertEquals(map.leavePortal, again.leavePortal)
        for ((x, z) in HubSquares.squares()) {
            assertContentEquals(map.terrain(x, z), again.terrain(x, z))
        }
    }

    @Test
    fun `an override file can replace the layout`() {
        val map = HubMap.parse(layout())
        assertEquals(50, map.size)
        assertEquals(3, map.floorAt(25, 25))
        assertEquals(2, map.floorAt(3, 3))
        assertEquals(1, map.floorAt(40, 40))
    }

    @Test
    fun `invalid layouts report every problem`() {
        assertTrue(problems("{nope").first().startsWith("not valid JSON"))
        assertTrue(
            problems(layout(floors = """[{"rect":[0,0,9,9],"floor":"grass"}]""")).any {
                "unknown floor 'grass'" in it
            }
        )
        assertTrue(
            problems(layout(floors = """[{"rect":[0,0,99,9],"floor":"path"}]""")).any {
                "outside" in it
            }
        )
        assertTrue(
            problems(layout(floors = """[{"rect":[5,5,1,1],"floor":"path"}]""")).any {
                "south-west" in it
            }
        )
        assertTrue(
            problems(
                    layout(
                        rooms =
                            """[{"name":"A","rect":[20,20,30,30],"floor":"in","wall":"w","doors":[{"side":"west","from":20,"to":26}]}]"""
                    )
                )
                .any { "from/to" in it }
        )
        assertTrue(
            problems(layout(npcs = """[{"npc":"zeke","x":20,"z":22}]""")).any {
                "inside a wall" in it
            }
        )
        assertTrue(problems(layout(arrival = """{"x":500,"z":5}""")).any { "arrival.x" in it })
        val overlap =
            """[{"name":"A","rect":[20,20,30,30],"floor":"in","wall":"w","doors":[]},
                {"name":"B","rect":[25,25,40,40],"floor":"in","wall":"w","doors":[]}]"""
        assertTrue(problems(layout(rooms = overlap, npcs = "[]")).any { "overlap" in it })
        // Several mistakes at once are all listed.
        val many =
            problems(
                layout(
                    floors = """[{"rect":[0,0,9,9],"floor":"nope"}]""",
                    arrival = """{"x":-1,"z":5}""",
                )
            )
        assertTrue(many.size >= 2)
    }
}
