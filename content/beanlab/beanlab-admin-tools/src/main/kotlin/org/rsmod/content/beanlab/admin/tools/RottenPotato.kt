package org.rsmod.content.beanlab.admin.tools

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.StatType
import dev.or2.central.account.Rights
import jakarta.inject.Inject
import org.rsmod.annotations.InternalApi
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.invtx.invClear
import org.rsmod.api.invtx.invDel
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.MiscOutput
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.PlayerSkillXP
import org.rsmod.api.player.stat.XpSource
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.player.ui.PlayerInterfaceUpdates
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.utils.system.SafeServiceExit
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private const val POTATO = "obj.rotten_potato"
private const val MAX_LEVEL = 99

/** One potato menu line, shown to players whose rights are at least [rights]. */
internal enum class PotatoAction(val label: String, val rights: Rights) {
    Teleports("Teleports", Rights.MOD),
    MyPosition("My position", Rights.MOD),
    KickMe("Kick me", Rights.MOD),
    SetAllStats("Set all stats", Rights.ADMINISTRATOR),
    WipeInventory("Wipe inventory", Rights.ADMINISTRATOR),
    SpawnItem("Spawn item", Rights.ADMINISTRATOR),
    SetMyRights("Set my modlevel", Rights.ADMINISTRATOR),
    Reboot("Reboot world (owners)", Rights.ADMINISTRATOR),
}

internal fun potatoActions(rights: Rights): List<PotatoAction> =
    PotatoAction.entries.filter { rights.isAtLeast(it.rights) }

/** Rights an admin may switch to: their own or lower, never higher. */
internal fun selectableRights(rights: Rights): List<Rights> =
    Rights.entries.filter { rights.isAtLeast(it) }

/**
 * The Beanscape admin "rotten potato", ported from the live server's admin-potato module: its
 * first op opens a staff menu filtered by Central rights (moderators: teleports, position, kick
 * me; administrators: stats, wipe, spawn, own modlevel). Rebooting is for owners, which Central
 * can't express, so it checks `beanlab.owners` first. A non-staff holder loses every potato.
 */
public class RottenPotato
@Inject
constructor(private val db: GameDbManager, private val playerList: PlayerList) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        onOpHeld1(POTATO) { openPotatoMenu() }
    }

    private suspend fun ProtectedAccess.openPotatoMenu() {
        if (!player.modLevel.isAtLeast(Rights.MOD)) {
            confiscate()
            return
        }
        val allowed = potatoActions(player.modLevel)
        val choice =
            menu("Beanscape Admin Potato", hotkeys = false, allowed.map { it.label } + "Done")
        ifClose()
        val picked = allowed.getOrNull(choice) ?: return
        if (!player.modLevel.isAtLeast(picked.rights)) {
            mes("You no longer have access to that.")
            return
        }
        logger.info { "[potato] ${player.displayName} chose ${picked.label}" }
        when (picked) {
            PotatoAction.Teleports -> openTeleportMenu()
            PotatoAction.MyPosition -> mes("You are at ${player.coords}.")
            PotatoAction.KickMe -> MiscOutput.logoutKicked(player)
            PotatoAction.SetAllStats -> setAllStats()
            PotatoAction.WipeInventory -> wipeInventory()
            PotatoAction.SpawnItem -> spawnItem()
            PotatoAction.SetMyRights -> setMyRights()
            PotatoAction.Reboot -> rebootIfOwner(player)
        }
    }

    private fun ProtectedAccess.confiscate() {
        logger.warn { "[potato] non-staff ${player.displayName} used a rotten potato; removing it" }
        val id = POTATO.asRSCM(RSCMType.OBJ)
        while (POTATO in player.inv) {
            if (!player.invDel(player.inv, id, 1, strict = false).success) break
        }
        mes("The potato crumbles to dust in your hands.")
    }

    private suspend fun ProtectedAccess.setAllStats() {
        val level = stringDialog("Set all stats to what level? (1-$MAX_LEVEL)").trim().toIntOrNull()
        if (level == null || level !in 1..MAX_LEVEL) {
            mes("Not a valid level.")
            return
        }
        player.setAllLevels(level)
        mes("All stats set to $level.")
    }

    private fun ProtectedAccess.wipeInventory() {
        player.invClear(player.inv)
        player.invAdd(player.inv, POTATO, 1, strict = false)
        mes("Inventory wiped.")
    }

    private suspend fun ProtectedAccess.spawnItem() {
        val picked = objDialog("Spawn which item?", stockMarketRestriction = false)
        val real =
            if (picked.isPlaceholder) ServerCacheManager.getItem(picked.placeholderLink) else picked
        if (real == null || real.isPlaceholder || real.name.equals("null", ignoreCase = true)) {
            mes("That entry isn't a real item (id ${picked.id}). Pick the other result.")
            return
        }
        val count = countDialog("How many ${real.name}?")
        if (count <= 0) {
            mes("Nothing spawned.")
            return
        }
        val result = player.invAdd(player.inv, real.id, count, strict = false)
        logger.info {
            "[potato] ${player.displayName} spawned ${real.name} (${real.id}) x$count " +
                "success=${result.success}"
        }
        mes(if (result.success) "Spawned $count x ${real.name}." else "No room for that.")
    }

    private suspend fun ProtectedAccess.setMyRights() {
        val levels = selectableRights(player.modLevel)
        val names = levels.map { it.name.lowercase() }
        val choice = menu("Set my modlevel", hotkeys = false, names + "Cancel")
        ifClose()
        val level = levels.getOrNull(choice) ?: return
        player.modLevel = level
        logger.info { "[potato] ${player.displayName} set own rights to $level" }
        mes("Modlevel now ${names[choice]} (until you log out).")
    }

    private fun rebootIfOwner(player: Player) {
        val accountId = player.accountId
        val uid = player.uid
        db.request(
            request = { conn ->
                val owner =
                    conn.prepareStatement("SELECT 1 FROM beanlab.owners WHERE account_id = ?").use {
                        it.setInt(1, accountId)
                        it.executeQuery().use { rs -> rs.next() }
                    }
                GameDbResult.Ok(owner)
            },
            response = response@{ result ->
                    val me = uid.resolve(playerList) ?: return@response
                    result.fold(
                        onOk = { owner -> if (owner) reboot(me) else me.mes(OWNERS_ONLY) },
                        onErr = { me.mes("Database error - try again.") },
                    )
                },
        )
    }

    private fun reboot(by: Player) {
        logger.info { "[potato] reboot by ${by.displayName}" }
        playerList.forEach { it.mes("[Broadcast] The world is restarting now.", ChatType.Broadcast) }
        SafeServiceExit.terminate()
    }
}

private const val OWNERS_ONLY = "Only owners can reboot the world."

private val releasedStats: List<StatType>
    get() = ServerCacheManager.getStats().values.filter { !it.unreleased }

/** Same approach as the admin API and OpenRune's `::master`: raise with xp, lower by reverting. */
@OptIn(InternalApi::class)
private fun Player.setAllLevels(level: Int) {
    val xp = PlayerSkillXPTable.getXPFromLevel(level)
    for (stat in releasedStats) {
        val key = RSCM.getReverseMapping(RSCMType.STAT, stat.id)
        val baseLevel = statMap.getBaseLevel(key)
        val targetLevel = maxOf(stat.minLevel, level)
        if (baseLevel > targetLevel) {
            statMap.setCurrentLevel(key, baseLevel)
            val delta = stat(key) - targetLevel
            statMap.setXP(key, xp)
            statMap.setBaseLevel(key, targetLevel.toByte())
            if (delta > 0) statSub(key, constant = delta, percent = 0)
        } else {
            val xpDelta = xp - statMap.getXP(key)
            statMap.setCurrentLevel(key, targetLevel.toByte())
            statAdvance(key, xpDelta.toDouble(), rate = 1.0, source = XpSource.Manual)
        }
    }
    appearance.combatLevel = PlayerSkillXP.calculateCombatLevel(this)
    PlayerInterfaceUpdates.updateCombatLevel(this)
}
