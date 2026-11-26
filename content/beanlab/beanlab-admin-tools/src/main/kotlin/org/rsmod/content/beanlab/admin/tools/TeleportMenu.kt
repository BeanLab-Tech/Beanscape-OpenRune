package org.rsmod.content.beanlab.admin.tools

import com.github.michaelbull.logging.InlineLogger
import dev.or2.central.account.Rights
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onCommand
import org.rsmod.content.beanlab.core.TeleportCatalog
import org.rsmod.content.beanlab.core.TeleportDestination
import org.rsmod.game.cheat.Cheat
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/** Last destination per player (in memory; resets on server restart). */
private val lastTeleport = ConcurrentHashMap<String, TeleportDestination>()

/**
 * Beanscape teleport menu, ported from the live server's admin-potato module. Opened with
 * ::teleports / ::tp. The destinations are [TeleportCatalog], shared with the admin API.
 *
 * Every menu closes itself (ifClose) before the next prompt, so a follow-up stringDialog isn't
 * aborted.
 */
public suspend fun ProtectedAccess.openTeleportMenu() {
    while (true) {
        val cats = TeleportCatalog.categories
        val last = lastTeleport[player.username]
        val lines = buildList {
            cats.forEach { add("${it.name}  (${it.destinations.size})") }
            add("Search by name...")
            if (last != null) add("Previous: ${last.name}")
            add("Teleport to coordinates...")
            add("Close")
        }
        val choice = menu("Beanscape Teleports", hotkeys = false, choices = lines)
        ifClose()
        val label = lines.getOrNull(choice) ?: return
        when {
            choice < cats.size -> {
                val picked = pickDestination(cats[choice].name, cats[choice].destinations)
                if (picked != null) return teleportTo(picked)
            }
            label == "Search by name..." -> {
                val query = stringDialog("Search teleports (e.g. bank, slayer, guild):")
                val results = TeleportCatalog.search(query)
                if (results.isEmpty()) {
                    mes("No teleports match \"${query.trim()}\".")
                    continue
                }
                if (results.size == 1) return teleportTo(results.first())
                val picked = pickDestination("Results: ${query.trim()}", results)
                if (picked != null) return teleportTo(picked)
            }
            label.startsWith("Previous: ") && last != null -> return teleportTo(last)
            label == "Teleport to coordinates..." -> return teleportToTypedCoords()
            else -> return
        }
    }
}

/** Shows [destinations] with a Back entry. Returns null when the player goes back. */
private suspend fun ProtectedAccess.pickDestination(
    title: String,
    destinations: List<TeleportDestination>,
): TeleportDestination? {
    val lines = listOf("« Back") + destinations.map { it.name }
    val choice = menu(title, hotkeys = false, choices = lines)
    ifClose()
    return if (choice <= 0) null else destinations.getOrNull(choice - 1)
}

private fun ProtectedAccess.teleportTo(dest: TeleportDestination) {
    lastTeleport[player.username] = dest
    logger.info {
        "[teleport] ${player.displayName} -> ${dest.name} (${dest.x}, ${dest.z}, ${dest.level})"
    }
    // Staff tool: no wilderness or combat checks, like the admin API's teleports.
    telejump(dest.coords, TeleportType.Exempt)
    mes("Teleported to ${dest.name}.")
}

private suspend fun ProtectedAccess.teleportToTypedCoords() {
    val raw = stringDialog("x z [level]  (ex: 3222 3218 0)")
    val dest = parseCoords(raw)
    if (dest == null) {
        mes("Expected: x z [level]  (ex: 3222 3218 0)")
        return
    }
    teleportTo(dest)
}

/** `x z [level]`, separated by spaces or commas; null when it isn't that. */
internal fun parseCoords(raw: String): TeleportDestination? {
    val parts = raw.trim().split(Regex("[\s,]+")).map { it.toIntOrNull() }
    if (parts.size !in 2..3 || parts.any { it == null }) return null
    val (x, z) = parts[0]!! to parts[1]!!
    val level = parts.getOrNull(2) ?: 0
    if (x !in 0..16383 || z !in 0..16383 || level !in 0..3) return null
    return TeleportDestination("$x, $z", x, z, level)
}

/** ::teleports and ::tp open the same menu (moderators and up, as on live). */
public class TeleportCommands
@Inject
constructor(private val protectedAccess: ProtectedAccessLauncher) : PluginScript() {
    override fun ScriptContext.startup() {
        for (name in listOf("teleports", "tp")) {
            onCommand(name) {
                requiredRights = Rights.MOD
                desc = "Open the Beanscape teleport menu"
                cheat { open(this) }
            }
        }
    }

    private fun open(cheat: Cheat) {
        val launched = protectedAccess.launch(cheat.player) { openTeleportMenu() }
        if (!launched) cheat.player.mes("You're busy right now.")
    }
}
