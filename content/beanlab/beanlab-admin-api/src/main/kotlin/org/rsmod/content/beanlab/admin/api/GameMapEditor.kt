package org.rsmod.content.beanlab.admin.api

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.NpcMode
import dev.openrune.types.NpcServerType
import dev.openrune.types.ObjectServerType
import jakarta.inject.Inject
import java.nio.file.Path
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.loc.LocLayerConstants

/**
 * Applies owner map edits to the live world and writes them to `.data/map-edits.json`. Objects,
 * walls and NPCs change immediately. Floors are left alone.
 */
public class GameMapEditor
@Inject
constructor(
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val access: GameAccess,
) : MapEditPort {
    private val logger = InlineLogger()
    private val log = MapEditLog(Path.of(".data", "map-edits.json"))
    private var applied = false

    /** Runs once, on a late game cycle, after the cache map and the normal spawns exist. */
    public fun applyOnce() {
        if (applied) return
        applied = true
        try {
            log.load()
        } catch (error: Exception) {
            logger.error(error) { "[map] could not read .data/map-edits.json" }
            return
        }
        for (edit in log.locs) {
            runCatching { applyLoc(edit) }.onFailure { logger.warn(it) { "[map] skipped loc $edit" } }
        }
        for (edit in log.npcs) {
            runCatching { applyNpc(edit) }.onFailure { logger.warn(it) { "[map] skipped npc $edit" } }
        }
        logger.info { "[map] applied ${log.locs.size} object edits and ${log.npcs.size} npc edits" }
    }

    override fun editLoc(
        op: String,
        x: Int,
        z: Int,
        level: Int,
        name: String,
        shape: Int,
        angle: Int,
    ): String =
        access.onGameThread {
            checkOp(op)
            checkTile(x, z, level)
            val shapeId = shapeOf(shape).id
            val angleId = angleOf(angle).id
            val type = objectType(name)
            val stored = shortName(type.internalName, name)
            val edit = MapEditLog.LocEdit(op, x, z, level, stored, shapeId, angleId)
            val droppedAdd = log.recordLoc(edit)
            log.save()
            if (applied) applyLoc(if (droppedAdd) edit.copy(op = MapEditLog.OP_REMOVE) else edit)
            if (op == MapEditLog.OP_ADD) "Placed ${type.name.ifBlank { stored }} at $x, $z."
            else "Removed ${type.name.ifBlank { stored }} at $x, $z."
        }

    override fun editNpc(op: String, x: Int, z: Int, level: Int, name: String): String =
        access.onGameThread {
            checkOp(op)
            checkTile(x, z, level)
            val type = npcType(name)
            val stored = shortName(type.internalName, name)
            val edit = MapEditLog.NpcEdit(op, x, z, level, stored)
            val droppedAdd = log.recordNpc(edit)
            log.save()
            if (applied) applyNpc(if (droppedAdd) edit.copy(op = MapEditLog.OP_REMOVE) else edit)
            if (op == MapEditLog.OP_ADD) "Placed ${type.name.ifBlank { stored }} at $x, $z."
            else "Removed ${type.name.ifBlank { stored }} at $x, $z."
        }

    private fun applyLoc(edit: MapEditLog.LocEdit) {
        val type = runCatching { objectType(edit.name) }.getOrNull() ?: return
        val coords = CoordGrid(edit.x, edit.z, edit.level)
        if (edit.op == MapEditLog.OP_ADD) {
            val shape = LocShape[edit.shape]
            val entity = LocEntity(type.id, shape.id, edit.angle)
            locRepo.add(LocInfo(LocLayerConstants.of(shape.id), coords, entity), Int.MAX_VALUE)
            return
        }
        val at = locRepo.findAll(coords).filter { it.coords == coords && it.id == type.id }.toList()
        val loc = at.firstOrNull { it.shapeId == edit.shape } ?: at.firstOrNull() ?: return
        locRepo.del(loc, Int.MAX_VALUE)
    }

    private fun applyNpc(edit: MapEditLog.NpcEdit) {
        val type = runCatching { npcType(edit.name) }.getOrNull() ?: return
        val coords = CoordGrid(edit.x, edit.z, edit.level)
        if (edit.op == MapEditLog.OP_ADD) {
            val npc = Npc(type, coords)
            npc.mode = NpcMode.None
            npcRepo.add(npc, Int.MAX_VALUE)
            return
        }
        val found =
            npcRepo.findAll(coords).firstOrNull {
                it.type.id == type.id && (it.spawnCoords == coords || it.coords == coords)
            } ?: return
        npcRepo.del(found, Int.MAX_VALUE)
    }

    private fun objectType(name: String): ObjectServerType = cacheType(name, "loc", "object", ServerCacheManager::getObject)

    private fun npcType(name: String): NpcServerType = cacheType(name, "npc", "npc", ServerCacheManager::getNpc)

    private fun <T> cacheType(name: String, prefix: String, label: String, lookup: (Int) -> T?): T {
        val raw = name.trim()
        if (raw.isEmpty()) throw ApiError(400, "Name the $label.")
        val id =
            raw.toIntOrNull()
                ?: run {
                    val key = if (raw.startsWith("$prefix.")) raw else "$prefix.$raw"
                    val resolved = key.asRSCM()
                    if (resolved == -1) throw ApiError(400, "No $label named $raw.")
                    resolved
                }
        return lookup(id) ?: throw ApiError(400, "No $label with id $id.")
    }

    private fun shortName(internal: String, fallback: String): String {
        val stripped = internal.removePrefix("loc.").removePrefix("npc.")
        return stripped.ifBlank { fallback.trim() }
    }

    private fun checkOp(op: String) {
        if (op != MapEditLog.OP_ADD && op != MapEditLog.OP_REMOVE) {
            throw ApiError(400, "op must be add or remove.")
        }
    }

    private fun shapeOf(shape: Int): LocShape =
        try {
            LocShape[shape]
        } catch (error: IllegalArgumentException) {
            throw ApiError(400, "Unknown object shape.")
        }

    private fun angleOf(angle: Int): LocAngle =
        try {
            LocAngle[angle]
        } catch (error: IllegalArgumentException) {
            throw ApiError(400, "Unknown facing.")
        }

    private fun checkTile(x: Int, z: Int, level: Int) {
        if (x !in 0..20000 || z !in 0..20000 || level !in 0..3) {
            throw ApiError(400, "That tile is outside the map.")
        }
    }
}
