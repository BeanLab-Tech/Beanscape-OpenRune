package org.rsmod.content.beanlab.hub

import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType

/**
 * Resolves the cache internal names a hub layout uses (`blackpaintedbrickwall_1`, `zeke`) to ids.
 * Layout names come from a data file, so they are looked up at runtime and unknown ones are
 * skipped with a warning instead of failing startup.
 */
internal object HubNames {
    fun loc(name: String): Int? = RSCM.getRSCMOrNull("loc.$name", RSCMType.LOC)

    fun npc(name: String): Int? = RSCM.getRSCMOrNull("npc.$name", RSCMType.NPC)
}
