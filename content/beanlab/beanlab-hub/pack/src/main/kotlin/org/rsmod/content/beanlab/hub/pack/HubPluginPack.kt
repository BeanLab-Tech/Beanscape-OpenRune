package org.rsmod.content.beanlab.hub.pack

import dev.openrune.cache.tools.tasks.CacheTask
import dev.openrune.pack.PluginPack

/** Packs the Market Hub's terrain, generated from the bundled `hub-layout.json`. */
public class HubPluginPack : PluginPack() {
    override fun extraTasks(): List<CacheTask> = listOf(HubTerrainTask())
}
