package org.rsmod.content.beanlab.names

import com.github.michaelbull.logging.InlineLogger
import dev.or2.central.account.Rights
import dev.or2.central.display.DisplayNamePlayerChangeResult
import dev.or2.central.display.DisplayNamePolicy
import jakarta.inject.Inject
import java.sql.Timestamp
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold
import org.rsmod.api.player.output.mes
import org.rsmod.api.script.onCommand
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

internal sealed interface RenameOutcome {
    data class Renamed(val name: String, val previous: String?, val atMillis: Long) : RenameOutcome

    data class Refused(val message: String) : RenameOutcome
}

/**
 * `::name <new name>`, for every player: changes your display name in Central's
 * `account_characters`, under Central's rename policy (see [checkRename]). The old name is held
 * for [DisplayNamePolicy.HOLD_RELEASE_MILLIS] so nobody else takes it, and a name can't match
 * another account's login name. Central's display-name trigger tells every world about the
 * change; this world also applies it at once.
 */
public class NameCommand
@Inject
constructor(private val db: GameDbManager, private val playerList: PlayerList) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        onCommand("name") {
            desc = "Change your display name: ::name <new name>"
            cheat { rename(this) }
        }
    }

    private fun rename(cheat: Cheat) {
        val player = cheat.player
        val raw = cheat.args.joinToString(" ").trim()
        if (raw.isEmpty()) {
            player.mes(USAGE)
            return
        }
        val accountId = player.accountId
        val characterId = player.characterId
        val staff = player.modLevel.isAtLeast(Rights.MOD)
        val uid = player.uid
        db.request(
            request = { conn ->
                GameDbResult.Ok(
                    rename(conn, accountId, characterId, raw, staff, System.currentTimeMillis())
                )
            },
            response = response@{ result ->
                    val me = uid.resolve(playerList) ?: return@response
                    result.fold(
                        onOk = { outcome ->
                            when (outcome) {
                                is RenameOutcome.Refused -> me.mes(outcome.message)
                                is RenameOutcome.Renamed -> {
                                    me.previousDisplayName = outcome.previous.orEmpty()
                                    me.displayName = outcome.name
                                    me.displayNameChangedAtMillis = outcome.atMillis
                                    me.rebuildAppearance()
                                    logger.info {
                                        "[name] account $accountId: ${outcome.previous} -> " +
                                            outcome.name
                                    }
                                    me.mes("Your display name is now ${outcome.name}.")
                                }
                            }
                        },
                        onErr = { me.mes("Database error - name not changed, try again.") },
                    )
                },
        )
    }

    private fun rename(
        conn: DatabaseConnection,
        accountId: Int,
        characterId: Int,
        raw: String,
        staff: Boolean,
        now: Long,
    ): RenameOutcome {
        val (current, changedAt) =
            currentName(conn, accountId, characterId)
                ?: return RenameOutcome.Refused("Character record not found - contact an admin.")
        val sanitized = DisplayNamePolicy.sanitize(raw)
        lockName(conn, sanitized)
        val held = activeHold(conn, sanitized, characterId)
        val result = checkRename(raw, current, changedAt, now, staff, held)
        refusal(result, changedAt, now)?.let {
            return RenameOutcome.Refused(it)
        }
        val name = (result as DisplayNamePlayerChangeResult.Accepted).sanitized
        if (taken(conn, name, accountId, characterId)) return RenameOutcome.Refused(TAKEN)

        conn
            .prepareStatement(
                "UPDATE account_characters SET display_name = ?, previous_display_name = ?, " +
                    "display_name_changed_at = ? WHERE id = ?"
            )
            .use {
                it.setString(1, name)
                it.setString(2, current)
                it.setLong(3, now)
                it.setInt(4, characterId)
                it.executeUpdate()
            }
        releaseHold(conn, name, characterId)
        if (!current.isNullOrBlank() && !DisplayNamePolicy.conflictsCaseInsensitive(current, name)) {
            holdName(conn, current, characterId, now + DisplayNamePolicy.HOLD_RELEASE_MILLIS)
        }
        return RenameOutcome.Renamed(name, current, now)
    }

    private fun currentName(
        conn: DatabaseConnection,
        accountId: Int,
        characterId: Int,
    ): Pair<String?, Long?>? =
        conn
            .prepareStatement(
                "SELECT display_name, display_name_changed_at FROM account_characters " +
                    "WHERE id = ? AND account_id = ? FOR UPDATE"
            )
            .use {
                it.setInt(1, characterId)
                it.setInt(2, accountId)
                it.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val name = rs.getString(1)
                    val changedAt = rs.getLong(2).takeIf { !rs.wasNull() }
                    name to changedAt
                }
            }

    /** Serialises renames to the same name until this transaction ends (the checks below race). */
    private fun lockName(conn: DatabaseConnection, name: String) {
        conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(lower(trim(?))))").use {
            it.setString(1, name)
            it.executeQuery().close()
        }
    }

    private fun activeHold(conn: DatabaseConnection, name: String, characterId: Int): Long? =
        conn
            .prepareStatement(
                "SELECT release_at, source_character_id FROM display_name_holds " +
                    "WHERE lower(trim(held_name)) = lower(trim(?)) AND release_at > CURRENT_TIMESTAMP"
            )
            .use {
                it.setString(1, name)
                it.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val until = rs.getTimestamp(1).time
                    val holder = rs.getInt(2).takeIf { !rs.wasNull() }
                    blockingHold(until, holder, characterId)
                }
            }

    private fun taken(conn: DatabaseConnection, name: String, accountId: Int, characterId: Int) =
        conn
            .prepareStatement(
                "SELECT 1 FROM account_characters " +
                    "WHERE lower(trim(display_name)) = lower(trim(?)) AND id <> ? " +
                    "UNION ALL SELECT 1 FROM accounts WHERE lower(account_name) = lower(?) AND id <> ?"
            )
            .use {
                it.setString(1, name)
                it.setInt(2, characterId)
                it.setString(3, name)
                it.setInt(4, accountId)
                it.executeQuery().use { rs -> rs.next() }
            }

    private fun releaseHold(conn: DatabaseConnection, name: String, characterId: Int) {
        conn
            .prepareStatement(
                "DELETE FROM display_name_holds WHERE lower(trim(held_name)) = lower(trim(?)) " +
                    "AND (source_character_id = ? OR release_at <= CURRENT_TIMESTAMP)"
            )
            .use {
                it.setString(1, name)
                it.setInt(2, characterId)
                it.executeUpdate()
            }
    }

    private fun holdName(conn: DatabaseConnection, name: String, characterId: Int, until: Long) {
        conn
            .prepareStatement(
                "DELETE FROM display_name_holds WHERE lower(trim(held_name)) = lower(trim(?))"
            )
            .use {
                it.setString(1, name)
                it.executeUpdate()
            }
        conn
            .prepareStatement(
                "INSERT INTO display_name_holds (held_name, release_at, source_character_id) " +
                    "VALUES (?, ?, ?)"
            )
            .use {
                it.setString(1, name)
                it.setTimestamp(2, Timestamp(until))
                it.setInt(3, characterId)
                it.executeUpdate()
            }
    }
}
