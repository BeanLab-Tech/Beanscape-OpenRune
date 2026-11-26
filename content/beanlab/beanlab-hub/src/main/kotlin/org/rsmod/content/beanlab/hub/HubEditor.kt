package org.rsmod.content.beanlab.hub

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.rsmod.content.beanlab.hub.pack.HubMap
import org.rsmod.content.beanlab.hub.pack.HubMapException
import org.rsmod.content.beanlab.hub.pack.LocPlacement
import org.rsmod.content.beanlab.hub.pack.NpcPlacement
import org.rsmod.content.beanlab.hub.pack.Rect
import org.rsmod.content.beanlab.hub.pack.Room
import org.rsmod.content.beanlab.hub.pack.WallPiece
import org.rsmod.content.beanlab.hub.pack.WallRun
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape

/** What an edit did (or why it refused). [message] is meant for game chat. */
internal data class EditResult(val ok: Boolean, val message: String)

/** Spawns that differ between two layouts, as multisets: what to add and what to remove. */
internal class HubDiff(
    val addedNpcs: List<NpcPlacement>,
    val removedNpcs: List<NpcPlacement>,
    val addedLocs: List<LocPlacement>,
    val removedLocs: List<LocPlacement>,
    val addedWalls: List<WallPiece>,
    val removedWalls: List<WallPiece>,
) {
    val isEmpty: Boolean
        get() =
            addedNpcs.isEmpty() &&
                removedNpcs.isEmpty() &&
                addedLocs.isEmpty() &&
                removedLocs.isEmpty() &&
                addedWalls.isEmpty() &&
                removedWalls.isEmpty()

    companion object {
        fun between(before: HubMap, after: HubMap): HubDiff =
            HubDiff(
                minus(after.npcs, before.npcs),
                minus(before.npcs, after.npcs),
                minus(after.locs, before.locs),
                minus(before.locs, after.locs),
                minus(after.wallPieces(), before.wallPieces()),
                minus(before.wallPieces(), after.wallPieces()),
            )

        /** Multiset difference: [a] without one occurrence per element of [b]. */
        private fun <T> minus(a: List<T>, b: List<T>): List<T> {
            val counts = HashMap<T, Int>()
            for (e in b) counts.merge(e, 1, Int::plus)
            val out = ArrayList<T>()
            for (e in a) {
                val left = counts[e] ?: 0
                if (left > 0) counts[e] = left - 1 else out += e
            }
            return out
        }
    }
}

/** A copy of this layout with some spawn lists replaced. Floors, rooms and the rest are kept. */
internal fun HubMap.copyWith(
    walls: List<WallRun> = this.walls,
    locs: List<LocPlacement> = this.locs,
    npcs: List<NpcPlacement> = this.npcs,
): HubMap =
    HubMap(size, palette, ground, floors, rooms, detail, walls, locs, npcs, arrival, leavePortal)

/**
 * The live working copy of the hub layout and the edits made to it. Pure: it never touches the
 * world; callers apply [HubDiff.between] of [map] before and after each edit. Every edit pushes the
 * previous layout on an undo stack, shared by everyone who edits.
 */
internal class HubEditor(initial: HubMap) {
    private class Entry(val before: HubMap, val who: String, val what: String)

    private val history = ArrayDeque<Entry>()

    var map: HubMap = initial
        private set

    val canUndo: Boolean
        get() = history.isNotEmpty()

    fun placeNpc(who: String, name: String, x: Int, z: Int): EditResult {
        refuseOutside(x, z)?.let {
            return it
        }
        if (blocked(x, z)) return refuse("$x,$z is a wall tile")
        if (onPortal(x, z)) return refuse("$x,$z is the leave portal")
        if (x == map.arrival.x && z == map.arrival.z) return refuse("$x,$z is the arrival tile")
        val next = map.copyWith(npcs = map.npcs + NpcPlacement(name, x, z))
        return commit(who, "npc $name at $x,$z", next)
    }

    fun placeLoc(
        who: String,
        name: String,
        x: Int,
        z: Int,
        shape: LocShape,
        angle: LocAngle,
    ): EditResult {
        refuseOutside(x, z)?.let {
            return it
        }
        // Wall decor (noticeboards, torches) hangs on a wall piece, so it may share its tile.
        if (!shape.name.startsWith("Wall") && blocked(x, z)) return refuse("$x,$z is a wall tile")
        if (onPortal(x, z)) return refuse("$x,$z is the leave portal")
        val placement = LocPlacement(name, x, z, shape, angle)
        if (placement in map.locs) return refuse("$name is already there")
        return commit(who, "loc $name at $x,$z", map.copyWith(locs = map.locs + placement))
    }

    /**
     * A run of [length] pieces of [name] facing [facing], starting at ([x], [z]) and going east for
     * north/south facing walls and north for east/west facing ones.
     */
    fun placeWall(
        who: String,
        name: String,
        x: Int,
        z: Int,
        facing: LocAngle,
        length: Int,
    ): EditResult {
        if (length !in 1..MAX_WALL_LENGTH) return refuse("length must be 1..$MAX_WALL_LENGTH")
        val horizontal = facing == LocAngle.North || facing == LocAngle.South
        val endX = if (horizontal) x + length - 1 else x
        val endZ = if (horizontal) z else z + length - 1
        refuseOutside(x, z)?.let {
            return it
        }
        refuseOutside(endX, endZ)?.let {
            return it
        }
        val existing = map.wallPieces().filter { it.angle == facing }.map { it.x to it.z }.toSet()
        for (px in x..endX) {
            for (pz in z..endZ) {
                if ((px to pz) in existing) return refuse("wall already at $px,$pz")
                if (onPortal(px, pz)) return refuse("$px,$pz is the leave portal")
                if (map.npcs.any { it.x == px && it.z == pz }) return refuse("npc at $px,$pz")
                if (px == map.arrival.x && pz == map.arrival.z) {
                    return refuse("$px,$pz is the arrival tile")
                }
            }
        }
        val run = WallRun(name, Rect(x, z, endX, endZ), facing)
        val what = "wall $name x$length from $x,$z facing ${facing.name.lowercase()}"
        return commit(who, what, map.copyWith(walls = map.walls + run))
    }

    /**
     * Removes the first NPC, object or wall piece found on ([x], [z]), then on the four neighbours,
     * then on the diagonals. Wall pieces of a room outline cannot be removed one by one: they come
     * from the room, so those are reported instead.
     */
    fun remove(who: String, x: Int, z: Int): EditResult {
        refuseOutside(x, z)?.let {
            return it
        }
        var roomHit: String? = null
        for ((dx, dz) in SEARCH_ORDER) {
            val tx = x + dx
            val tz = z + dz
            map.npcs
                .firstOrNull { it.x == tx && it.z == tz }
                ?.let { npc ->
                    val next = map.copyWith(npcs = map.npcs - npc)
                    return commit(who, "removed npc ${npc.npc} at $tx,$tz", next)
                }
            map.locs
                .firstOrNull { it.x == tx && it.z == tz }
                ?.let { loc ->
                    val next = map.copyWith(locs = map.locs - loc)
                    return commit(who, "removed loc ${loc.loc} at $tx,$tz", next)
                }
            val run = map.walls.firstOrNull { it.line.contains(tx, tz) }
            if (run != null) {
                val next = map.copyWith(walls = splitRun(map.walls, run, tx, tz))
                return commit(who, "removed wall ${run.loc} at $tx,$tz", next)
            }
            if (roomHit == null) {
                roomHit = map.rooms.firstOrNull { onRoomWall(it, tx, tz) }?.name
            }
        }
        if (roomHit != null) {
            return refuse(
                "that wall belongs to room '$roomHit'; change the room in the layout file"
            )
        }
        return refuse("nothing the layout placed on or next to $x,$z")
    }

    fun undo(): EditResult {
        val entry = history.removeLastOrNull() ?: return refuse("nothing to undo")
        map = entry.before
        return EditResult(true, "undid ${entry.what} (by ${entry.who})")
    }

    /** Everything the validator objects to in the current layout, empty when it is saveable. */
    fun problems(): List<String> = validate(map)

    private fun commit(who: String, what: String, next: HubMap): EditResult {
        history.addLast(Entry(map, who, what))
        while (history.size > MAX_HISTORY) history.removeFirst()
        map = next
        return EditResult(true, what)
    }

    private fun refuse(message: String) = EditResult(false, message)

    private fun refuseOutside(x: Int, z: Int): EditResult? =
        if (x in 0 until map.size && z in 0 until map.size) null
        else refuse("$x,$z is outside the hub")

    private fun blocked(x: Int, z: Int) = map.wallPieces().any { it.x == x && it.z == z }

    private fun onPortal(x: Int, z: Int) =
        x in map.leavePortal.x..map.leavePortal.x + 2 &&
            z in map.leavePortal.z..map.leavePortal.z + 2

    private fun onRoomWall(room: Room, x: Int, z: Int): Boolean {
        val r = room.rect
        if (!r.contains(x, z)) return false
        return x == r.x0 || x == r.x1 || z == r.z0 || z == r.z1
    }

    companion object {
        const val MAX_WALL_LENGTH = 40
        const val MAX_HISTORY = 100

        private val SEARCH_ORDER =
            listOf(0 to 0, 0 to 1, 1 to 0, 0 to -1, -1 to 0, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

        /** Problems found by the layout parser, or none. Same check the loader runs. */
        fun validate(map: HubMap): List<String> =
            try {
                HubMap.parse(map.toJson())
                emptyList()
            } catch (e: HubMapException) {
                e.problems
            }

        /** [walls] with the piece at ([x], [z]) taken out of [run], which may split it in two. */
        private fun splitRun(walls: List<WallRun>, run: WallRun, x: Int, z: Int): List<WallRun> {
            val l = run.line
            val parts = ArrayList<WallRun>()
            if (l.z0 == l.z1) {
                if (x > l.x0) parts += run.copy(line = Rect(l.x0, l.z0, x - 1, l.z1))
                if (x < l.x1) parts += run.copy(line = Rect(x + 1, l.z0, l.x1, l.z1))
            } else {
                if (z > l.z0) parts += run.copy(line = Rect(l.x0, l.z0, l.x1, z - 1))
                if (z < l.z1) parts += run.copy(line = Rect(l.x0, z + 1, l.x1, l.z1))
            }
            val out = ArrayList<WallRun>()
            for (w in walls) {
                if (w === run) out += parts else out += w
            }
            return out
        }

        /**
         * Writes [map] to [path] without ever leaving a half-written file: the text goes to a
         * temporary file next to it, the old file is kept as `<name>.bak`, then the new one is
         * moved into place. Throws [HubMapException] (nothing written) when the layout does not
         * validate.
         */
        fun save(map: HubMap, path: Path) {
            val text = map.toJson()
            HubMap.parse(text)
            val dir = path.toAbsolutePath().parent
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, path.fileName.toString(), ".tmp")
            try {
                Files.writeString(tmp, text)
                if (Files.isRegularFile(path)) {
                    val backup = path.resolveSibling(path.fileName.toString() + ".bak")
                    Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
                }
                try {
                    Files.move(
                        tmp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                Files.deleteIfExists(tmp)
                throw e
            }
        }
    }
}
