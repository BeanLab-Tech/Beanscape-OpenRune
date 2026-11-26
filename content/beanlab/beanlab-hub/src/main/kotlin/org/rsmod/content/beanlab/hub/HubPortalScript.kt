package org.rsmod.content.beanlab.hub

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.content.beanlab.hub.pack.HubMap
import org.rsmod.content.beanlab.hub.pack.HubSquares
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag
import org.rsmod.routefinder.loc.LocLayerConstants

/**
 * The Market Hub: a copy of the clan hall portal on the Grand Exchange plaza leads to it.
 *
 * The entrance is `loc.clan_hub_portal`, spawned at startup on the plaza bench's tile
 * ([EXCHANGE_PORTAL]); the bench (`loc.bench2`) is removed. OpenRune has no clan hall content, so
 * the portal's options are free, and the real clan hall portal next to it (3186, 3471) leads to the
 * hub too. A purple Soul Wars portal (`loc.soul_wars_leave_soulwars_portal`) in the hub returns to
 * the Exchange.
 *
 * The hub is a [HubMap] (`hub-layout.json`, or an override file, see [HubMapLoader]) of up to 128 x
 * 128 tiles from ([HubSquares.MIN_X], [HubSquares.MIN_Z]), outside every real map square. Its
 * floors are packed into the cache by the `beanlab-hub-pack` module during `buildCache`. Walls,
 * objects and NPCs are spawned from the layout at startup. Tiles of the hub's map squares outside
 * the layout are blocked.
 */
public class HubPortalScript
@Inject
constructor(
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        val loaded = HubMapLoader.load()
        for (problem in loaded.problems) logger.warn { "[hub] layout: $problem" }
        val map = loaded.map
        val entry = local(map.arrival.x, map.arrival.z)
        onOpLoc1("loc.clan_hub_portal") { teleport(entry) }
        onOpLoc2("loc.clan_hub_portal") { teleport(entry) }
        onOpLoc3("loc.clan_hub_portal") { teleport(entry) }
        onOpLoc1("loc.soul_wars_leave_soulwars_portal") { teleport(EXCHANGE_EXIT) }
        onOpLoc2("loc.soul_wars_leave_soulwars_portal") { teleport(EXCHANGE_EXIT) }
        onOpLoc3("loc.soul_wars_leave_soulwars_portal") { teleport(EXCHANGE_EXIT) }
        onEvent<GameLifecycle.Startup> {
            blockOutside(map.size)
            placeExchangePortal()
            locRepo.add(
                local(map.leavePortal.x, map.leavePortal.z),
                "loc.soul_wars_leave_soulwars_portal",
                Int.MAX_VALUE,
                LocAngle.South,
                LocShape.CentrepieceStraight,
            )
            val placed = buildLocs(map)
            val npcs = spawnNpcs(map)
            logger.info {
                "[hub] portal at $EXCHANGE_PORTAL -> hub at ${local(0, 0)} " +
                    "(layout: ${loaded.source}, $placed objects, $npcs npcs)"
            }
        }
    }

    /** Removes the plaza bench and puts the portal on its tile, facing the way the bench faced. */
    private fun placeExchangePortal() {
        val benchId = HubNames.loc("bench2")
        val seat =
            locRepo.findExact(EXCHANGE_PORTAL, LocShape.CentrepieceStraight)?.takeIf {
                it.id == benchId
            }
        if (seat != null) {
            locRepo.del(seat, Int.MAX_VALUE)
        } else {
            logger.warn { "[hub] no bench at $EXCHANGE_PORTAL; portal placed anyway" }
        }
        locRepo.add(
            EXCHANGE_PORTAL,
            "loc.clan_hub_portal",
            Int.MAX_VALUE,
            seat?.angle ?: LocAngle.South,
            LocShape.CentrepieceStraight,
        )
    }

    /** Blocks the tiles of the hub's map squares that the layout does not cover. */
    private fun blockOutside(size: Int) {
        val span = HubSquares.SQUARE_SPAN * 64
        for (dx in 0 until span) {
            for (dz in 0 until span) {
                if (dx < size && dz < size) continue
                collision[HubSquares.MIN_X + dx, HubSquares.MIN_Z + dz, 0] =
                    CollisionFlag.BLOCK_WALK
            }
        }
    }

    private fun buildLocs(map: HubMap): Int {
        val missing = HashSet<String>()

        fun id(name: String): Int? =
            HubNames.loc(name)
                ?: run {
                    if (missing.add(name)) logger.warn { "[hub] loc $name missing; skipped" }
                    null
                }

        var placed = 0
        for (piece in map.wallPieces()) {
            val wall = id(piece.loc) ?: continue
            val shape = if (piece.corner) LocShape.WallL else LocShape.WallStraight
            add(local(piece.x, piece.z), wall, piece.angle, shape)
            placed++
        }
        for (loc in map.locs) {
            val type = id(loc.loc) ?: continue
            add(local(loc.x, loc.z), type, loc.angle, loc.shape)
            placed++
        }
        return placed
    }

    private fun add(coords: CoordGrid, id: Int, angle: LocAngle, shape: LocShape) {
        val entity = LocEntity(id, shape.id, angle.id)
        locRepo.add(LocInfo(LocLayerConstants.of(shape.id), coords, entity), Int.MAX_VALUE)
    }

    private fun spawnNpcs(map: HubMap): Int {
        var placed = 0
        for (spawn in map.npcs) {
            val type = HubNames.npc(spawn.npc)?.let { ServerCacheManager.getNpc(it) }
            if (type == null) {
                logger.warn { "[hub] npc ${spawn.npc} missing from this cache; skipped" }
                continue
            }
            npcRepo.add(Npc(type, local(spawn.x, spawn.z)), Int.MAX_VALUE)
            placed++
        }
        return placed
    }

    private fun local(x: Int, z: Int) = CoordGrid(HubSquares.MIN_X + x, HubSquares.MIN_Z + z, 0)

    private companion object {
        /** The plaza bench (`loc.bench2`, 5615) the portal replaces. */
        val EXCHANGE_PORTAL = CoordGrid(3182, 3468, 0)

        /** Where the leave portal puts you (the ::tp "Grand Exchange" entry). */
        val EXCHANGE_EXIT = CoordGrid(3164, 3486, 0)
    }
}
