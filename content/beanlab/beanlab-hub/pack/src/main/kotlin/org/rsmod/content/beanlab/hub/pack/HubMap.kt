package org.rsmod.content.beanlab.hub.pack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape

/** Everything wrong with a layout file, so an editor can show all of it at once. */
public class HubMapException(val problems: List<String>) :
    IllegalArgumentException(problems.joinToString("; "))

public enum class Side {
    West,
    East,
    North,
    South,
}

public data class Rect(val x0: Int, val z0: Int, val x1: Int, val z1: Int) {
    fun contains(x: Int, z: Int) = x in x0..x1 && z in z0..z1

    fun overlaps(o: Rect) = x0 <= o.x1 && o.x0 <= x1 && z0 <= o.z1 && o.z0 <= z1
}

/** A doorway: tiles [from]..[to] of an edge (z for west/east edges, x for north/south). */
public data class Door(val side: Side, val from: Int, val to: Int)

public data class Room(
    val name: String,
    val rect: Rect,
    val floor: String,
    val wall: String,
    val doors: List<Door>,
)

public data class FloorPaint(val rect: Rect, val floor: String)

/** A straight run of wall pieces, all facing [angle], on the tiles of an axis-aligned line. */
public data class WallRun(val loc: String, val line: Rect, val angle: LocAngle)

public data class LocPlacement(
    val loc: String,
    val x: Int,
    val z: Int,
    val shape: LocShape,
    val angle: LocAngle,
)

public data class NpcPlacement(val npc: String, val x: Int, val z: Int)

public data class Point(val x: Int, val z: Int)

public data class WallPiece(
    val loc: String,
    val x: Int,
    val z: Int,
    val angle: LocAngle,
    val corner: Boolean,
)

/**
 * The Market Hub plan in arena-local tiles (0 until [size] in x and z, z grows north), loaded from
 * `hub-layout.json`. One source for the packed terrain ([terrain]) and for the walls, objects and
 * NPCs the script spawns. Floor ids are cache underlay ids, named through [palette].
 *
 * Floors are painted in this order, later wins: [ground], [floors], each room's floor, [detail].
 */
public class HubMap(
    val size: Int,
    val palette: Map<String, Int>,
    val ground: String,
    val floors: List<FloorPaint>,
    val rooms: List<Room>,
    val detail: List<FloorPaint>,
    val walls: List<WallRun>,
    val locs: List<LocPlacement>,
    val npcs: List<NpcPlacement>,
    val arrival: Point,
    val leavePortal: Point,
) {
    private fun id(name: String): Int = palette.getValue(name)

    /** Cache underlay id for the ground at arena-local ([x], [z]). */
    fun floorAt(x: Int, z: Int): Int {
        if (x !in 0 until size || z !in 0 until size) return id(ground)
        var floor = ground
        for (paint in floors) if (paint.rect.contains(x, z)) floor = paint.floor
        for (room in rooms) if (room.rect.contains(x, z)) floor = room.floor
        for (paint in detail) if (paint.rect.contains(x, z)) floor = paint.floor
        return id(floor)
    }

    /** Every wall piece: room outlines (doorways left open) plus the explicit runs. */
    fun wallPieces(): List<WallPiece> {
        val out = ArrayList<WallPiece>()
        for (room in rooms) out += roomWalls(room)
        for (run in walls) {
            for (x in run.line.x0..run.line.x1) {
                for (z in run.line.z0..run.line.z1) {
                    out += WallPiece(run.loc, x, z, run.angle, corner = false)
                }
            }
        }
        return out
    }

    private fun roomWalls(room: Room): List<WallPiece> {
        val r = room.rect
        val out = ArrayList<WallPiece>()

        fun open(side: Side, coord: Int) =
            room.doors.any { it.side == side && coord in it.from..it.to }

        fun piece(x: Int, z: Int, angle: LocAngle, corner: Boolean) {
            out += WallPiece(room.wall, x, z, angle, corner)
        }
        for (x in r.x0..r.x1) {
            for (z in r.z0..r.z1) {
                val west = x == r.x0
                val east = x == r.x1
                val south = z == r.z0
                val north = z == r.z1
                if (!(west || east || south || north)) continue
                when {
                    west && north -> piece(x, z, LocAngle.West, true)
                    north && east -> piece(x, z, LocAngle.North, true)
                    east && south -> piece(x, z, LocAngle.East, true)
                    south && west -> piece(x, z, LocAngle.South, true)
                    west -> if (!open(Side.West, z)) piece(x, z, LocAngle.West, false)
                    east -> if (!open(Side.East, z)) piece(x, z, LocAngle.East, false)
                    south -> if (!open(Side.South, x)) piece(x, z, LocAngle.South, false)
                    north -> if (!open(Side.North, x)) piece(x, z, LocAngle.North, false)
                }
            }
        }
        return out
    }

    /** One `m` file for map square ([squareX], [squareZ]): flat ground, level 0 coloured. */
    fun terrain(squareX: Int, squareZ: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val baseX = (squareX - HubSquares.FIRST_X) * 64
        val baseZ = (squareZ - HubSquares.FIRST_Z) * 64

        fun short(v: Int) {
            out.write(v shr 8)
            out.write(v and 0xFF)
        }
        for (level in 0 until 4) {
            for (x in 0 until 64) {
                for (z in 0 until 64) {
                    if (level == 0) {
                        short(UNDERLAY_BASE + floorAt(baseX + x, baseZ + z))
                        short(1)
                        out.write(0)
                    } else {
                        short(0)
                    }
                }
            }
        }
        return out.toByteArray()
    }

    /** The layout as pretty-printed JSON that [parse] reads back to an equal map. */
    fun toJson(): String {
        val m = ObjectMapper()
        val root = m.createObjectNode()
        root.put("version", VERSION)
        root.put("size", size)
        val pal = root.putObject("palette")
        for ((name, id) in palette) pal.put(name, id)
        root.put("ground", ground)

        fun rect(r: Rect) = m.createArrayNode().add(r.x0).add(r.z0).add(r.x1).add(r.z1)

        fun paints(key: String, list: List<FloorPaint>) {
            val arr = root.putArray(key)
            for (p in list) arr.addObject().apply {
                set<JsonNode>("rect", rect(p.rect))
                put("floor", p.floor)
            }
        }
        paints("floors", floors)
        val rms = root.putArray("rooms")
        for (r in rooms) {
            val o = rms.addObject()
            o.put("name", r.name)
            o.set<JsonNode>("rect", rect(r.rect))
            o.put("floor", r.floor)
            o.put("wall", r.wall)
            val ds = o.putArray("doors")
            for (d in r.doors) {
                ds.addObject().apply {
                    put("side", d.side.name.lowercase())
                    put("from", d.from)
                    put("to", d.to)
                }
            }
        }
        paints("detail", detail)
        val ws = root.putArray("walls")
        for (w in walls) ws.addObject().apply {
            put("loc", w.loc)
            set<JsonNode>("line", rect(w.line))
            put("angle", w.angle.name.lowercase())
        }
        val ls = root.putArray("locs")
        for (l in locs) ls.addObject().apply {
            put("loc", l.loc)
            put("x", l.x)
            put("z", l.z)
            put("shape", l.shape.name)
            put("angle", l.angle.name.lowercase())
        }
        val ns = root.putArray("npcs")
        for (n in npcs) ns.addObject().apply {
            put("npc", n.npc)
            put("x", n.x)
            put("z", n.z)
        }
        root.putObject("arrival").put("x", arrival.x).put("z", arrival.z)
        root.putObject("leavePortal").put("x", leavePortal.x).put("z", leavePortal.z)
        return m.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n"
    }

    companion object {
        /** Map-file opcode of underlay 0 (the file stores the underlay id plus one, past 81). */
        const val UNDERLAY_BASE = 82
        const val MAX_UNDERLAY = 173
        const val MAX_SIZE = 128
        const val VERSION = 1

        private val mapper = ObjectMapper()

        /** Parses and validates a layout file; throws [HubMapException] listing every problem. */
        fun parse(text: String): HubMap {
            val root =
                try {
                    mapper.readTree(text)
                } catch (e: Exception) {
                    throw HubMapException(listOf("not valid JSON: ${e.message}"))
                }
            return Parser(root).parse()
        }
    }

    private class Parser(private val root: JsonNode) {
        val problems = ArrayList<String>()

        fun parse(): HubMap {
            if (root.path("version").wholeInt() != VERSION) problems += "version must be $VERSION"
            val size = root.path("size").wholeInt()
            if (size !in 8..MAX_SIZE) problems += "size must be 8..$MAX_SIZE"
            val safeSize = if (size in 8..MAX_SIZE) size else 100

            val palette = LinkedHashMap<String, Int>()
            for ((name, node) in
                root.path("palette").fields().asSequence().map { it.key to it.value }) {
                val id = node.wholeInt()
                if (id !in 0..MAX_UNDERLAY) problems += "palette.$name must be 0..$MAX_UNDERLAY"
                palette[name] = id.coerceIn(0, MAX_UNDERLAY)
            }
            if (palette.isEmpty()) problems += "palette is empty"

            fun floorName(node: JsonNode, path: String): String {
                val name = node.asText("")
                if (name !in palette) problems += "$path: unknown floor '$name'"
                return name
            }

            val ground = floorName(root.path("ground"), "ground")
            val floors = paints("floors", safeSize, ::floorName)
            val detail = paints("detail", safeSize, ::floorName)

            val rooms = ArrayList<Room>()
            for ((i, node) in root.path("rooms").withIndex()) {
                val path = "rooms[$i]"
                val rect = rect(node.path("rect"), "$path.rect", safeSize)
                val name = node.path("name").asText("")
                if (name.isBlank()) problems += "$path: name is required"
                val wall = node.path("wall").asText("")
                if (wall.isBlank()) problems += "$path: wall is required"
                val floor = floorName(node.path("floor"), "$path.floor")
                val doors = ArrayList<Door>()
                for ((j, d) in node.path("doors").withIndex()) {
                    doors += door(d, rect, "$path.doors[$j]")
                }
                rooms += Room(name, rect, floor, wall, doors)
            }
            for (i in rooms.indices) {
                for (j in i + 1 until rooms.size) {
                    if (rooms[i].rect.overlaps(rooms[j].rect)) {
                        problems += "rooms '${rooms[i].name}' and '${rooms[j].name}' overlap"
                    }
                }
            }

            val walls = ArrayList<WallRun>()
            for ((i, node) in root.path("walls").withIndex()) {
                val path = "walls[$i]"
                val line = rect(node.path("line"), "$path.line", safeSize)
                if (line.x0 != line.x1 && line.z0 != line.z1)
                    problems += "$path: line is not straight"
                val loc = node.path("loc").asText("")
                if (loc.isBlank()) problems += "$path: loc is required"
                walls += WallRun(loc, line, angle(node.path("angle"), "$path.angle"))
            }

            val locs = ArrayList<LocPlacement>()
            for ((i, node) in root.path("locs").withIndex()) {
                val path = "locs[$i]"
                val loc = node.path("loc").asText("")
                if (loc.isBlank()) problems += "$path: loc is required"
                val shapeName = node.path("shape").asText("")
                val shape = LocShape.entries.firstOrNull { it.name.equals(shapeName, true) }
                if (shape == null) problems += "$path: unknown shape '$shapeName'"
                locs +=
                    LocPlacement(
                        loc,
                        coord(node.path("x"), "$path.x", safeSize),
                        coord(node.path("z"), "$path.z", safeSize),
                        shape ?: LocShape.CentrepieceStraight,
                        angle(node.path("angle"), "$path.angle"),
                    )
            }

            val npcs = ArrayList<NpcPlacement>()
            for ((i, node) in root.path("npcs").withIndex()) {
                val path = "npcs[$i]"
                val npc = node.path("npc").asText("")
                if (npc.isBlank()) problems += "$path: npc is required"
                npcs +=
                    NpcPlacement(
                        npc,
                        coord(node.path("x"), "$path.x", safeSize),
                        coord(node.path("z"), "$path.z", safeSize),
                    )
            }

            val arrival = point(root.path("arrival"), "arrival", safeSize)
            val leave = point(root.path("leavePortal"), "leavePortal", safeSize)

            val map =
                HubMap(
                    safeSize,
                    palette,
                    ground,
                    floors,
                    rooms,
                    detail,
                    walls,
                    locs,
                    npcs,
                    arrival,
                    leave,
                )
            if (problems.isEmpty()) checkBlocked(map)
            if (problems.isNotEmpty()) throw HubMapException(problems)
            return map
        }

        /** NPCs and the arrival tile must not stand on a wall piece. */
        private fun checkBlocked(map: HubMap) {
            val blocked = map.wallPieces().map { it.x to it.z }.toSet()
            for (n in map.npcs) {
                if ((n.x to n.z) in blocked)
                    problems += "npc ${n.npc} at ${n.x},${n.z} is inside a wall"
            }
            if ((map.arrival.x to map.arrival.z) in blocked) problems += "arrival is inside a wall"
        }

        private fun paints(
            key: String,
            size: Int,
            floorName: (JsonNode, String) -> String,
        ): List<FloorPaint> =
            root.path(key).mapIndexed { i, node ->
                FloorPaint(
                    rect(node.path("rect"), "$key[$i].rect", size),
                    floorName(node.path("floor"), "$key[$i].floor"),
                )
            }

        private fun coord(node: JsonNode, path: String, size: Int): Int {
            val v = node.asInt(-1)
            if (!node.isInt || v !in 0 until size) problems += "$path must be 0..${size - 1}"
            return v.coerceIn(0, size - 1)
        }

        private fun point(node: JsonNode, path: String, size: Int) =
            Point(coord(node.path("x"), "$path.x", size), coord(node.path("z"), "$path.z", size))

        private fun rect(node: JsonNode, path: String, size: Int): Rect {
            if (!node.isArray || node.size() != 4 || !node.all { it.isInt }) {
                problems += "$path must be [x0, z0, x1, z1]"
                return Rect(0, 0, 0, 0)
            }
            val r = Rect(node[0].asInt(), node[1].asInt(), node[2].asInt(), node[3].asInt())
            if (r.x0 > r.x1 || r.z0 > r.z1) problems += "$path: first corner must be south-west"
            if (r.x0 < 0 || r.z0 < 0 || r.x1 >= size || r.z1 >= size) {
                problems += "$path is outside 0..${size - 1}"
            }
            return r
        }

        private fun angle(node: JsonNode, path: String): LocAngle {
            val name = node.asText("")
            val found = LocAngle.entries.firstOrNull { it.name.equals(name, true) }
            if (found == null) problems += "$path: unknown angle '$name'"
            return found ?: LocAngle.North
        }

        private fun door(node: JsonNode, room: Rect, path: String): Door {
            val sideName = node.path("side").asText("")
            val side = Side.entries.firstOrNull { it.name.equals(sideName, true) }
            if (side == null) {
                problems += "$path: unknown side '$sideName'"
                return Door(Side.West, 0, 0)
            }
            val from = node.path("from").wholeInt()
            val to = node.path("to").wholeInt()
            val vertical = side == Side.West || side == Side.East
            val lo = if (vertical) room.z0 + 1 else room.x0 + 1
            val hi = if (vertical) room.z1 - 1 else room.x1 - 1
            if (from > to || from < lo || to > hi) {
                problems += "$path: from/to must sit within $lo..$hi on the ${side.name} wall"
            }
            return Door(side, from, to)
        }
    }
}

/**
 * A JSON integer, or -1. Unlike Jackson's `asInt`, booleans, decimals and numeric strings do not
 * count: `"version": true` would otherwise read as 1. The web panel's validator does the same.
 */
private fun JsonNode.wholeInt(): Int = if (isInt) intValue() else -1
