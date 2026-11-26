package org.rsmod.content.beanlab.admin.api

import com.github.michaelbull.logging.InlineLogger
import dev.or2.central.account.Rights
import jakarta.inject.Inject
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold
import org.rsmod.api.player.output.mes
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onGameStartup
import org.rsmod.content.beanlab.core.BeanlabSchema
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Starts the admin API once its tables exist and adds `::admintoken` (owners) for issuing and
 * revoking API tokens. Set `ADMIN_API_ENABLED=false` to keep the listener off, and `ADMIN_API_PORT`
 * to change the loopback port (default 8087; the VM's staging world uses 8088 next to live).
 */
public class AdminApiPlugin
@Inject
constructor(
    private val db: GameDbManager,
    private val access: GameAccess,
    private val backend: GameBackend,
    private val tokens: DbTokenStore,
    private val audit: DbAuditSink,
    private val playerList: PlayerList,
) : PluginScript() {
    private val logger = InlineLogger()
    private var server: AdminHttpServer? = null

    override fun ScriptContext.startup() {
        onGameStartup { migrateThenStart() }
        onCommand("admintoken") {
            requiredRights = Rights.ADMINISTRATOR
            desc = "Issue an API token for the admin console: ::admintoken [revoke] (owners)"
            cheat { handleToken(this) }
        }
    }

    private fun migrateThenStart() {
        if (System.getenv("ADMIN_API_ENABLED")?.equals("false", ignoreCase = true) == true) {
            logger.info { "[admin-api] disabled by ADMIN_API_ENABLED" }
            return
        }
        db.request(
            request = { connection ->
                BeanlabSchema.migrate(connection, MODULE, AdminApiPlugin::class.java, MIGRATIONS)
                GameDbResult.Ok(Unit)
            },
            response = { result ->
                result.fold(
                    onOk = { startServer() },
                    onErr = { logger.error { "[admin-api] migration failed: $it" } },
                )
            },
        )
    }

    private fun startServer() {
        val port = System.getenv("ADMIN_API_PORT")?.toIntOrNull() ?: DEFAULT_PORT
        val created = AdminHttpServer(port, tokens, AdminRouter(backend, audit))
        try {
            created.start()
            server = created
            Runtime.getRuntime().addShutdownHook(Thread { created.stop() })
        } catch (error: java.io.IOException) {
            logger.error(error) { "[admin-api] could not listen on port $port" }
        }
    }

    private fun handleToken(cheat: Cheat) =
        with(cheat) {
            val accountId = player.accountId
            val uid = player.uid
            val revoke = args.firstOrNull().equals("revoke", ignoreCase = true)
            val raw = if (revoke) null else AdminTokens.generate()
            // Owner is checked against the database (Central's rights stop at ADMINISTRATOR).
            access.fireAndForget { connection ->
                val rank = AccountRanks.rank(connection, accountId)
                val message =
                    when {
                        rank != Rank.Owner -> "Only owners can use the admin console."
                        raw == null -> {
                            connection
                                .prepareStatement(
                                    "UPDATE beanlab.admin_tokens SET revoked_at = " +
                                        "CURRENT_TIMESTAMP WHERE account_id = ? AND revoked_at IS NULL"
                                )
                                .use {
                                    it.setInt(1, accountId)
                                    it.executeUpdate()
                                }
                            "All your admin tokens are revoked."
                        }
                        else -> {
                            connection
                                .prepareStatement(
                                    "INSERT INTO beanlab.admin_tokens (account_id, token_hash, label) " +
                                        "VALUES (?, ?, ?)"
                                )
                                .use {
                                    it.setInt(1, accountId)
                                    it.setString(2, AdminTokens.hash(raw))
                                    it.setString(3, "in-game")
                                    it.executeUpdate()
                                }
                            "Admin token (shown once, copy it now): $raw"
                        }
                    }
                notify(uid, message)
            }
        }

    /** DB work runs off the game thread; hop back to it before touching the player. */
    private fun notify(uid: org.rsmod.game.entity.player.PlayerUid, message: String) {
        db.request(
            request = { GameDbResult.Ok(Unit) },
            response = { uid.resolve(playerList)?.mes(message) },
        )
    }

    private companion object {
        private const val DEFAULT_PORT = 8087
        private const val MODULE = "admin-api"
        private val MIGRATIONS =
            listOf("migrations/V1__admin_api.sql")
    }
}
