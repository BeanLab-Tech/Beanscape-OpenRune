package org.rsmod.content.beanlab.core

import com.github.michaelbull.logging.InlineLogger
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Marker for the Beanscape modules under `content/beanlab`. Deploys and CI look for the
 * `[beanlab] loaded` line to confirm our code is on the classpath of the running server.
 */
class BeanlabCoreScript : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        logger.info { "[beanlab] loaded (Beanscape on OpenRune)" }
    }
}
