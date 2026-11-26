package org.rsmod.content.beanlab.hub

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.or2.central.account.Rights
import jakarta.inject.Inject
import java.io.IOException
import java.nio.file.Path
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onCommand
import org.rsmod.content.beanlab.hub.pack.HubMap
import org.rsmod.content.beanlab.hub.pack.HubSquares
import org.rsmod.content.beanlab.hub.pack.NpcPlacement
import org.rsmod.content.beanlab.hub.pack.Point
import org.rsmod.content.beanlab.hub.pack.WallPiece
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.loc.LocLayerConstants

/**
 * Admin-only `::hub` commands that edit the Market Hub live. They keep a working copy of the layout
 * ([HubEditor], loaded the same way the portal script loads it) and mirror every change into the
 * world, so everyone sees it at once and `::hub save` writes exactly what the world shows. Floors
 * are not editable here: they live in the packed cache.
 */
public class HubCommands
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
) : PluginScript() {
    private val logger = InlineLogger()

    private lateinit var editor: HubEditor

    override fun ScriptContext.startup() {
        editor = HubEditor(HubMapLoader.load().map)
        onCommand("hub") {
            requiredRights = Rights.ADMINISTRATOR
            desc = "Edit the Market Hub live: ::hub help"
            cheat { handle(this) }
        }
    }

    private fun handle(cheat: Cheat) {
        val player = cheat.player
        fun say(text: String) = player.mes(text)

        val args = cheat.args
        val sub = args.firstOrNull()?.lowercase() ?: "help"
        val here = local(player.coords)
        when (sub) {
            "help" -> HELP.forEach(::say)
            "tp" -> {
                val a = editor.map.arrival
                val dest = local(a.x, a.z)
                if (!protectedAccess.launch(player) { telejump(dest) }) say("You are busy.")
            }
            "where" ->
                say(
                    if (here == null) "You are outside the hub."
                    else "Hub tile ${here.x}, ${here.z}"
                )
            "reload" -> {
                say("Edits already show live. A restart loads the saved file:")
                say(overridePath().toString())
            }
            "save" -> save(::say)
            "undo" -> {
                val before = editor.map
                val result = editor.undo()
                if (result.ok) mirror(before, editor.map)
                say(result.message)
            }
            "npc",
            "loc",
            "wall",
            "rm" -> {
                if (here == null) return say("Stand inside the hub first (::hub tp).")
                edit(sub, args.drop(1), here, player.displayName, ::say)
            }
            else -> say("Unknown ::hub command '$sub'. Try ::hub help")
        }
    }

    private fun edit(
        sub: String,
        args: List<String>,
        here: Point,
        who: String,
        say: (String) -> Unit,
    ) {
        val before = editor.map
        val result =
            when (sub) {
                "npc" -> {
                    val name = args.getOrNull(0) ?: return say("Use ::hub npc <internal_name>")
                    if (HubNames.npc(name) == null) return say("No npc named '$name' in this cache.")
                    editor.placeNpc(who, name, here.x, here.z)
                }
                "loc" -> {
                    val name = args.getOrNull(0) ?: return say(USAGE_LOC)
                    if (HubNames.loc(name) == null) return say("No object named '$name' in this cache.")
                    var shape = LocShape.CentrepieceStraight
                    var angle = LocAngle.South
                    val rest = args.drop(1)
                    for ((i, word) in rest.withIndex()) {
                        val s = parseShape(word)
                        val a = parseAngle(word)
                        when {
                            s != null && i == 0 -> shape = s
                            a != null -> angle = a
                            else -> return say("Unknown shape or angle '$word'. $USAGE_LOC")
                        }
                    }
                    editor.placeLoc(who, name, here.x, here.z, shape, angle)
                }
                "wall" -> {
                    val name = args.getOrNull(0) ?: return say(USAGE_WALL)
                    if (HubNames.loc(name) == null) return say("No object named '$name' in this cache.")
                    val facing = parseAngle(args.getOrNull(1)) ?: return say(USAGE_WALL)
                    val length =
                        args.getOrNull(2)?.let { it.toIntOrNull() ?: return say(USAGE_WALL) } ?: 1
                    editor.placeWall(who, name, here.x, here.z, facing, length)
                }
                else -> editor.remove(who, here.x, here.z)
            }
        if (result.ok) {
            mirror(before, editor.map)
            logger.info { "[hub] $who: ${result.message}" }
        }
        say(if (result.ok) "Done: ${result.message}" else "Refused: ${result.message}")
    }

    private fun save(say: (String) -> Unit) {
        val problems = editor.problems()
        if (problems.isNotEmpty()) {
            say("Not saved, the layout has ${problems.size} problem(s):")
            problems.take(MAX_LINES).forEach(say)
            if (problems.size > MAX_LINES) say("... and ${problems.size - MAX_LINES} more")
            return
        }
        val path = overridePath()
        try {
            HubEditor.save(editor.map, path)
            say("Saved to $path (previous file kept as .bak).")
        } catch (e: IOException) {
            logger.warn(e) { "[hub] save failed" }
            say("Could not save: ${e.message}")
        }
    }

    /** Applies the spawn differences between two layouts to the world: removals, then additions. */
    private fun mirror(before: HubMap, after: HubMap) {
        val diff = HubDiff.between(before, after)
        for (npc in diff.removedNpcs) removeNpc(npc)
        for (loc in diff.removedLocs) {
            val id = HubNames.loc(loc.loc) ?: continue
            removeLoc(id, local(loc.x, loc.z), loc.shape, loc.angle)
        }
        for (piece in diff.removedWalls) {
            val id = HubNames.loc(piece.loc) ?: continue
            removeLoc(id, local(piece.x, piece.z), wallShape(piece), piece.angle)
        }
        for (npc in diff.addedNpcs) {
            val type = HubNames.npc(npc.npc)?.let { ServerCacheManager.getNpc(it) } ?: continue
            npcRepo.add(Npc(type, local(npc.x, npc.z)), Int.MAX_VALUE)
        }
        for (loc in diff.addedLocs) {
            val id = HubNames.loc(loc.loc) ?: continue
            addLoc(id, local(loc.x, loc.z), loc.shape, loc.angle)
        }
        for (piece in diff.addedWalls) {
            val id = HubNames.loc(piece.loc) ?: continue
            addLoc(id, local(piece.x, piece.z), wallShape(piece), piece.angle)
        }
    }

    private fun removeNpc(placement: NpcPlacement) {
        val coords = local(placement.x, placement.z)
        // NPCs may have wandered off their spawn tile, so match on where they were spawned.
        val id = HubNames.npc(placement.npc) ?: return
        val found =
            npcRepo.findAll(ZoneKey.from(coords), NPC_SEARCH_ZONES).firstOrNull {
                it.spawnCoords == coords && it.type.id == id
            }
        if (found != null) npcRepo.del(found, Int.MAX_VALUE)
    }

    private fun addLoc(id: Int, coords: CoordGrid, shape: LocShape, angle: LocAngle) {
        val entity = LocEntity(id, shape.id, angle.id)
        locRepo.add(LocInfo(LocLayerConstants.of(shape.id), coords, entity), Int.MAX_VALUE)
    }

    private fun removeLoc(id: Int, coords: CoordGrid, shape: LocShape, angle: LocAngle) {
        val found =
            locRepo.findAll(coords).firstOrNull {
                it.id == id && it.shapeId == shape.id && it.angleId == angle.id
            }
        if (found != null) locRepo.del(found, Int.MAX_VALUE)
    }

    private fun wallShape(piece: WallPiece) =
        if (piece.corner) LocShape.WallL else LocShape.WallStraight

    private fun overridePath(): Path =
        Path.of(System.getenv(HubMapLoader.OVERRIDE_ENV) ?: HubMapLoader.DEFAULT_OVERRIDE)

    private fun local(x: Int, z: Int) = CoordGrid(HubSquares.MIN_X + x, HubSquares.MIN_Z + z, 0)

    /** Arena-local tile for [coords], or null when outside the hub. */
    private fun local(coords: CoordGrid): Point? {
        val x = coords.x - HubSquares.MIN_X
        val z = coords.z - HubSquares.MIN_Z
        val size = editor.map.size
        return if (coords.level == 0 && x in 0 until size && z in 0 until size) Point(x, z)
        else null
    }

    private companion object {
        const val NPC_SEARCH_ZONES = 3
        const val MAX_LINES = 6
        const val USAGE_LOC = "Use ::hub loc <internal_name> [shape] [angle]"
        const val USAGE_WALL = "Use ::hub wall <internal_name> <north|south|east|west> [length]"

        val HELP =
            listOf(
                "::hub tp - go to the hub arrival tile; ::hub where - your hub tile",
                "::hub npc <name> - place an npc on your tile",
                "::hub loc <name> [shape] [angle] - place an object (default shape " +
                    "CentrepieceStraight, angle south)",
                "::hub wall <name> <north|south|east|west> [length] - wall run from your tile, " +
                    "facing that way; north/south walls run east, east/west walls run north",
                "::hub rm - remove the npc, object or wall piece on or next to your tile",
                "::hub undo - revert the last change (anyone's, shared)",
                "::hub save - write the layout to the override file (backup kept)",
                "::hub reload - a restart loads the saved file; edits already show live",
                "Floors are not editable here: they need a cache rebuild (buildCache).",
            )

        fun parseAngle(word: String?): LocAngle? =
            word?.let { w -> LocAngle.entries.firstOrNull { it.name.equals(w, true) } }

        fun parseShape(word: String): LocShape? =
            LocShape.entries.firstOrNull { it.name.equals(word, true) }
    }
}
