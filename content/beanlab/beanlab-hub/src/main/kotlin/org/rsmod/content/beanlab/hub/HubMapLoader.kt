package org.rsmod.content.beanlab.hub

import java.nio.file.Files
import java.nio.file.Path
import org.rsmod.content.beanlab.hub.pack.HubMap
import org.rsmod.content.beanlab.hub.pack.HubMapException
import org.rsmod.content.beanlab.hub.pack.HubSquares

/**
 * Finds the hub layout. An override file next to the server data (`HUB_LAYOUT_FILE`, default
 * `.data/hub-layout.json`) wins over the layout bundled in the jar, so the editors can change
 * walls, objects and NPCs with only a restart. A bad override is reported and ignored: the hub
 * falls back to the bundled layout instead of failing to start.
 *
 * Floors are not read from here at runtime. The cache build (`:or-cache:buildCache`, through
 * `HubPluginPack`) generates them from the bundled layout, so a floor change needs a cache rebuild.
 */
internal object HubMapLoader {
    const val OVERRIDE_ENV = "HUB_LAYOUT_FILE"
    const val DEFAULT_OVERRIDE = ".data/hub-layout.json"

    class Loaded(val map: HubMap, val source: String, val problems: List<String>)

    fun bundledText(): String = HubSquares.bundledText()

    fun bundled(): HubMap = HubMap.parse(bundledText())

    fun load(): Loaded {
        val path = Path.of(System.getenv(OVERRIDE_ENV) ?: DEFAULT_OVERRIDE)
        if (!Files.isRegularFile(path)) return Loaded(bundled(), "bundled", emptyList())
        return try {
            Loaded(HubMap.parse(Files.readString(path)), path.toString(), emptyList())
        } catch (e: HubMapException) {
            Loaded(bundled(), "bundled", listOf("ignored $path:") + e.problems)
        } catch (e: java.io.IOException) {
            Loaded(bundled(), "bundled", listOf("could not read $path: ${e.message}"))
        }
    }
}
