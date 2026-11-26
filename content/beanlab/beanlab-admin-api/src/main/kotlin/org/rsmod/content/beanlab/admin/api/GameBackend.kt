package org.rsmod.content.beanlab.admin.api

import com.fasterxml.jackson.databind.JsonNode
import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import dev.openrune.types.StatType
import jakarta.inject.Inject
import java.lang.management.ManagementFactory
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.rsmod.annotations.InternalApi
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.invtx.invClear
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.MiscOutput
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.PlayerSkillXP
import org.rsmod.api.player.stat.XpSource
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.player.stat.statRestoreAll
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.player.ui.PlayerInterfaceUpdates
import org.rsmod.api.pw.hash.PasswordHashing
import org.rsmod.api.realm.Realm
import org.rsmod.api.server.config.ServerConfig
import org.rsmod.api.utils.system.SafeServiceExit
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.map.CoordGrid

/**
 * The real [AdminBackend] on OpenRune: reads and changes the running game. Anything that touches a
 * [Player] runs on the game thread through [GameAccess.onGameThread]; account work is SQL against
 * Central's `accounts` (ranks: see [AccountRanks]).
 *
 * Permission rules are not repeated here. [AdminRouter] has already checked rank, target rank and
 * input before any of these methods run.
 */
public class GameBackend
@Inject
constructor(
    private val access: GameAccess,
    private val playerList: PlayerList,
    private val launcher: ProtectedAccessLauncher,
    private val realm: Realm,
    private val hashing: PasswordHashing,
    private val config: ServerConfig,
) : AdminBackend {
    private val logger = InlineLogger()
    private val random = SecureRandom()
    private val rebootScheduled = AtomicBoolean(false)

    /** Account ids of owners, refreshed whenever ranks are read or changed. */
    @Volatile private var owners: Set<Int> = emptySet()

    private val stats: List<StatType>
        get() = ServerCacheManager.getStats().values.filter { !it.unreleased }

    private fun StatType.key(): String = RSCM.getReverseMapping(RSCMType.STAT, id)

    // ---------------------------------------------------------------- reads

    override fun players(): Map<String, Any?> {
        refreshOwners()
        val known = owners
        val rows =
            access.onGameThread {
                playerList.map { player ->
                    mapOf(
                        "name" to player.displayName,
                        "combat" to player.combatLevel,
                        "modlevel" to rankOf(player, known).label,
                        "coords" to coords(player),
                        "area" to "",
                    ) + identityFields()
                }
            }
        return mapOf("players" to rows)
    }

    @OptIn(InternalApi::class)
    override fun player(name: String): Map<String, Any?> {
        refreshOwners()
        val known = owners
        return access.onGameThread {
            val player = online(name)
            val levels =
                stats.associate { stat ->
                    SkillNames.toApi(stat.displayName) to
                        player.statMap.getBaseLevel(stat.key()).toInt()
                }
            mapOf(
                "name" to player.displayName,
                "combat" to player.combatLevel,
                "modlevel" to rankOf(player, known).label,
                "coords" to coords(player),
                "stats" to levels,
            )
        }
    }

    override fun playerRank(name: String): Rank? {
        val accountId = access.onGameThread { findOnline(name)?.accountId } ?: return null
        return accountRank(accountId)
    }

    override fun world(): Map<String, Any?> =
        access.onGameThread {
            mapOf(
                "online" to playerList.count(),
                "uptimeSeconds" to ManagementFactory.getRuntimeMXBean().uptime / MILLIS,
                "xpRate" to realm.config.baseXpRate,
            )
        }

    override fun teleports(): Map<String, Any?> =
        mapOf(
            "categories" to
                TeleportCatalog.categories.map { category ->
                    mapOf(
                        "name" to category.name,
                        "destinations" to
                            category.destinations.map {
                                mapOf(
                                    "name" to it.name,
                                    "x" to it.x,
                                    "z" to it.z,
                                    "level" to it.level,
                                )
                            },
                    )
                }
        )

    override fun searchItems(query: String): Map<String, Any?> {
        val needle = query.trim().lowercase()
        if (needle.length < MIN_SEARCH) throw ApiError(400, "Type at least two letters.")
        val items =
            ServerCacheManager.getItems()
                .values
                .asSequence()
                .filter { it.name.isNotBlank() && !it.name.equals("null", ignoreCase = true) }
                .filter { !it.isCert && !it.isPlaceholder }
                .filter { it.name.lowercase().contains(needle) }
                .map { it to ItemKinds.classify(it) }
                .sortedWith(
                    compareBy<Pair<ItemServerType, String>>({ it.second == ItemKinds.JUNK })
                        .thenBy { it.first.name.length }
                )
                .take(MAX_ITEMS)
                .map { (type, kind) ->
                    mapOf(
                        "id" to type.id,
                        "name" to type.name,
                        "kind" to kind,
                        "tradeable" to type.tradeable,
                        "members" to true,
                    )
                }
                .toList()
        return mapOf("items" to items)
    }

    // ---------------------------------------------------------------- player actions

    override fun playerAction(actor: Actor, name: String, action: String, body: JsonNode): String {
        val rank =
            if (action == "modlevel") Rank.parse(body.path("modlevel").asText()) else null
        val accountId = access.onGameThread { online(name).accountId }
        if (rank != null) {
            access.query { AccountRanks.save(it, accountId, rank) }
            refreshOwners()
        }
        return access.onGameThread {
            val target = online(name)
            when (action) {
                "teleport-to" -> teleportTo(actor, target)
                "summon" -> summon(actor, target)
                "heal" -> heal(target)
                "kick" -> kick(target)
                "give" -> give(target, body)
                "wipe-inventory" -> wipe(target)
                "stats" -> setStats(target, body)
                "modlevel" -> setOnlineRank(target, rank ?: Rank.Player)
                else -> throw ApiError(404, "Unknown action: $action.")
            }
        }
    }

    private fun teleportTo(actor: Actor, target: Player): String {
        val me = actorPlayer(actor)
        val destination = target.coords
        launch(me) { telejump(destination, TeleportType.Exempt) }
        return "Teleported to ${target.displayName}."
    }

    private fun summon(actor: Actor, target: Player): String {
        val me = actorPlayer(actor)
        val destination = me.coords
        launch(target) { telejump(destination, TeleportType.Exempt) }
        return "Summoned ${target.displayName}."
    }

    private fun heal(target: Player): String {
        target.statRestoreAll(stats.map { it.key() })
        return "Restored ${target.displayName}'s stats."
    }

    private fun kick(target: Player): String {
        MiscOutput.logoutKicked(target)
        return "Kicked ${target.displayName}."
    }

    private fun give(target: Player, body: JsonNode): String {
        val id = body.path("id").asInt(-1)
        val qty = body.path("qty").asInt(0)
        val type = ServerCacheManager.getItem(id) ?: throw ApiError(400, "No item with id $id.")
        if (qty < 1) throw ApiError(400, "Quantity must be at least 1.")
        val result = target.invAdd(target.inv, id, qty, strict = false)
        if (!result.success) throw ApiError(409, "${target.displayName} has no room for that.")
        return "Gave ${target.displayName} $qty x ${type.name}."
    }

    private fun wipe(target: Player): String {
        target.invClear(target.inv)
        return "Wiped ${target.displayName}'s inventory."
    }

    private fun setStats(target: Player, body: JsonNode): String {
        val requested = body.path("levels")
        if (!requested.isObject) throw ApiError(400, "Send levels as an object of skill to level.")
        val levels = mutableMapOf<String, Int>()
        requested.fields().forEach { (skill, value) ->
            val level = value.asInt(-1)
            if (level !in 1..MAX_LEVEL) throw ApiError(400, "$skill must be 1 to $MAX_LEVEL.")
            levels[skill] = level
        }
        target.applyLevels(levels)
        return "Updated ${levels.size} levels for ${target.displayName}."
    }

    /** Same approach as OpenRune's `::master`: raise with xp, lower by reverting xp and level. */
    @OptIn(InternalApi::class)
    private fun Player.applyLevels(levels: Map<String, Int>) {
        for (stat in stats) {
            val level = levels[SkillNames.toApi(stat.displayName)] ?: continue
            val key = stat.key()
            val xp = PlayerSkillXPTable.getXPFromLevel(level)
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

    private fun setOnlineRank(target: Player, rank: Rank): String {
        target.modLevel = AccountRanks.rightsOf(rank)
        return "${target.displayName} is now ${rank.label}."
    }

    // ---------------------------------------------------------------- teleport, broadcast, reboot

    override fun teleport(actor: Actor, target: String, x: Int, z: Int, level: Int): String =
        access.onGameThread {
            val player = online(target)
            launch(player) { telejump(CoordGrid(x, z, level), TeleportType.Exempt) }
            "Teleported ${player.displayName} to $x, $z."
        }

    override fun broadcast(actor: Actor, message: String): String =
        access.onGameThread {
            val text = "[Broadcast] $message"
            playerList.forEach { it.mes(text, ChatType.Broadcast) }
            "Sent to ${playerList.count()} players."
        }

    override fun reboot(actor: Actor, seconds: Int): String {
        if (!rebootScheduled.compareAndSet(false, true)) {
            throw ApiError(409, "A reboot is already counting down.")
        }
        val scheduler =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "admin-api-reboot").also { it.isDaemon = true }
            }
        val warnings = listOf(seconds, seconds / 2, 30, 10).filter { it in 1..seconds }.distinct()
        for (warning in warnings) {
            scheduler.schedule(
                { announce("The world restarts in ${describe(warning)}.") },
                (seconds - warning).toLong(),
                TimeUnit.SECONDS,
            )
        }
        scheduler.schedule(
            {
                logger.info { "[admin-api] reboot by ${actor.username}" }
                SafeServiceExit.terminate()
            },
            seconds.toLong(),
            TimeUnit.SECONDS,
        )
        announce("The world restarts in ${describe(seconds)}.")
        return "Reboot in ${describe(seconds)}."
    }

    private fun announce(text: String) {
        try {
            access.onGameThread {
                playerList.forEach { it.mes("[Broadcast] $text", ChatType.Broadcast) }
            }
        } catch (error: ApiError) {
            logger.warn { "[admin-api] could not announce reboot: ${error.message}" }
        }
    }

    private fun describe(seconds: Int): String =
        if (seconds >= SECONDS_PER_MINUTE && seconds % SECONDS_PER_MINUTE == 0) {
            "${seconds / SECONDS_PER_MINUTE} minute(s)"
        } else {
            "$seconds seconds"
        }

    // ---------------------------------------------------------------- accounts

    override fun accounts(query: String): Map<String, Any?> {
        val onlineIds = access.onGameThread { playerList.map { it.accountId }.toSet() }
        val needle = query.trim().lowercase().replace("\\", "\\\\").replace("%", "\\%")
        val rows =
            access.query { connection ->
                connection
                    .prepareStatement(
                        "SELECT a.id, a.account_name, ${AccountRanks.RANK_SQL} " +
                            "${AccountRanks.FROM_SQL} WHERE LOWER(a.account_name) LIKE ? " +
                            "ORDER BY a.id DESC LIMIT $MAX_ACCOUNTS"
                    )
                    .use { statement ->
                        statement.setString(1, "%$needle%")
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        mapOf(
                                            "id" to rs.getInt(1),
                                            "username" to rs.getString(2),
                                            "modlevel" to Rank.parse(rs.getString(3)).label,
                                        ) + identityFields()
                                    )
                                }
                            }
                        }
                    }
            }
        return mapOf("accounts" to rows.map { it + ("online" to (it["id"] in onlineIds)) })
    }

    override fun accountRank(id: Int): Rank? = access.query { AccountRanks.rank(it, id) }

    override fun createAccount(actor: Actor, username: String, rank: Rank): Map<String, Any?> {
        val password = generatePassword()
        val hash = hashing.hash(password.toCharArray())
        val world = config.world
        val created =
            access.query { connection ->
                val id =
                    connection
                        .prepareStatement(
                            "INSERT INTO accounts (account_name, password_hash) VALUES (?, ?) " +
                                "ON CONFLICT ((lower(account_name))) DO NOTHING RETURNING id"
                        )
                        .use {
                            it.setString(1, username)
                            it.setString(2, hash)
                            it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
                        } ?: return@query false
                AccountRanks.save(connection, id, rank)
                // Worlds with the whitelist gate only admit listed accounts.
                connection
                    .prepareStatement(
                        "INSERT INTO world_login_whitelist (world_id, account_name) " +
                            "SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM world_login_whitelist " +
                            "WHERE world_id = ? AND LOWER(account_name) = LOWER(?))"
                    )
                    .use {
                        it.setInt(1, world)
                        it.setString(2, username)
                        it.setInt(3, world)
                        it.setString(4, username)
                        it.executeUpdate()
                    }
                true
            }
        if (!created) throw ApiError(409, "That name is already taken.")
        refreshOwners()
        return mapOf("message" to "Created $username.", "password" to password)
    }

    override fun setAccountRank(actor: Actor, id: Int, rank: Rank): String {
        access.query { AccountRanks.save(it, id, rank) }
        refreshOwners()
        access.onGameThread {
            playerList.firstOrNull { it.accountId == id }?.modLevel = AccountRanks.rightsOf(rank)
        }
        return "Mod level is now ${rank.label}."
    }

    override fun resetPassword(actor: Actor, id: Int): Map<String, Any?> {
        val password = generatePassword()
        val hash = hashing.hash(password.toCharArray())
        access.query { connection ->
            connection.prepareStatement("UPDATE accounts SET password_hash = ? WHERE id = ?").use {
                it.setString(1, hash)
                it.setInt(2, id)
                it.executeUpdate()
            }
        }
        return mapOf("message" to "Password reset.", "password" to password)
    }

    override fun deleteAccount(actor: Actor, id: Int): String {
        if (id == actor.accountId) throw ApiError(409, "You can't delete your own account.")
        access.onGameThread {
            playerList.firstOrNull { it.accountId == id }?.let(MiscOutput::logoutKicked)
        }
        // Central's tables reference accounts with ON DELETE CASCADE.
        access.query { connection ->
            connection.prepareStatement("DELETE FROM accounts WHERE id = ?").use {
                it.setInt(1, id)
                it.executeUpdate()
            }
        }
        return "Account deleted."
    }

    override fun audit(limit: Int): Map<String, Any?> {
        val entries =
            access.query { connection ->
                connection
                    .prepareStatement(
                        "SELECT to_char(created_at, 'HH24:MI:SS'), actor, action, target, " +
                            "detail, ok FROM beanlab.admin_audit ORDER BY id DESC LIMIT ?"
                    )
                    .use { statement ->
                        statement.setInt(1, limit)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    val detail =
                                        listOfNotNull(rs.getString(4), rs.getString(5))
                                            .joinToString(": ")
                                    add(
                                        mapOf(
                                            "time" to rs.getString(1).orEmpty(),
                                            "actor" to rs.getString(2),
                                            "action" to
                                                rs.getString(3) +
                                                    if (!rs.getBoolean(6)) " (refused)" else "",
                                            "detail" to detail,
                                        )
                                    )
                                }
                            }
                        }
                    }
            }
        return mapOf("entries" to entries)
    }

    // ---------------------------------------------------------------- helpers

    private fun refreshOwners() {
        try {
            owners =
                access.query { connection ->
                    connection.prepareStatement("SELECT account_id FROM beanlab.owners").use {
                        it.executeQuery().use { rs ->
                            buildSet { while (rs.next()) add(rs.getInt(1)) }
                        }
                    }
                }
        } catch (error: ApiError) {
            logger.debug { "[admin-api] owner refresh skipped: ${error.message}" }
        }
    }

    private fun rankOf(player: Player, owners: Set<Int>): Rank =
        when {
            player.accountId in owners -> Rank.Owner
            player.modLevel.name.contains("ADMIN") -> Rank.Admin
            player.modLevel.name.contains("MOD") -> Rank.Moderator
            else -> Rank.Player
        }

    /** Central keeps no Discord name or picture yet, so the console shows initials. */
    private fun identityFields(): Map<String, Any?> = mapOf("avatar" to null, "discord" to "")

    private fun coords(player: Player): String =
        "${player.coords.x}, ${player.coords.z}, ${player.coords.level}"

    private fun findOnline(name: String): Player? =
        playerList.firstOrNull { it.displayName.equals(name, ignoreCase = true) }

    private fun online(name: String): Player =
        findOnline(name) ?: throw ApiError(404, "$name isn't online.")

    /** Your own character; actions like teleport-to need you logged in. */
    private fun actorPlayer(actor: Actor): Player =
        playerList.firstOrNull { it.accountId == actor.accountId }
            ?: throw ApiError(409, "Log in to the game first, then try again.")

    private fun launch(player: Player, block: suspend ProtectedAccess.() -> Unit) {
        if (!launcher.launch(player, block = block)) {
            throw ApiError(409, "${player.displayName} is busy right now. Try again in a moment.")
        }
    }

    private fun generatePassword(): String = buildString {
        repeat(PASSWORD_LENGTH) { append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)]) }
    }

    private companion object {
        private const val MILLIS = 1000L
        private const val MIN_SEARCH = 2
        private const val MAX_ITEMS = 40
        private const val MAX_ACCOUNTS = 60
        private const val MAX_LEVEL = 99
        private const val SECONDS_PER_MINUTE = 60
        private const val PASSWORD_LENGTH = 16
        private const val PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}
