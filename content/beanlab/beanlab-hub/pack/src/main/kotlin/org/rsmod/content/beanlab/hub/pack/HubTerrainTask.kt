package org.rsmod.content.beanlab.hub.pack

import dev.openrune.cache.MAPS
import dev.openrune.cache.tools.TaskPriority
import dev.openrune.cache.tools.tasks.CacheTask
import dev.openrune.filesystem.Cache

/**
 * Writes the hub's map squares into the cache, from the layout bundled with this module.
 *
 * From revision 237 a map square is one group in the maps archive, `x shl 8 or z`, with the terrain
 * in file 0 and the loc list in file 1 (no XTEA keys). The hub's walls, objects and NPCs are
 * spawned by the server at startup, so the loc list is empty. Runs in both the client and the
 * server cache build, so the client draws the floors and the server reads the same squares.
 */
public class HubTerrainTask : CacheTask() {
    override val priority: TaskPriority
        get() = TaskPriority.MAPS

    override fun init(cache: Cache) {
        val map = HubMap.parse(HubSquares.bundledText())
        for ((x, z) in HubSquares.squares()) {
            val group = (x shl 8) or z
            cache.write(MAPS, group, TERRAIN_FILE, map.terrain(x, z), null)
            cache.write(MAPS, group, LOCS_FILE, EMPTY_LOCS, null)
        }
    }

    private companion object {
        const val TERRAIN_FILE = 0
        const val LOCS_FILE = 1

        /** A loc list with no entries: the first id delta is 0, which ends the list. */
        val EMPTY_LOCS = byteArrayOf(0)
    }
}
