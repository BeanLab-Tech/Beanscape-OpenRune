package org.rsmod.content.beanlab.admin.api

import dev.or2.central.account.Rights
import jakarta.inject.Inject
import org.rsmod.api.db.DatabaseConnection

/**
 * Beanscape ranks on OpenRune Central's accounts. Central stores `accounts.rights` (NONE, MOD,
 * ADMINISTRATOR); an account in `beanlab.owners` is an owner on top of ADMINISTRATOR.
 */
public object AccountRanks {
    /** SQL for the Beanscape rank label of `accounts a LEFT JOIN beanlab.owners o`. */
    public const val RANK_SQL: String =
        "CASE WHEN o.account_id IS NOT NULL THEN 'owner' " +
            "WHEN UPPER(COALESCE(a.rights, '')) LIKE '%ADMIN%' THEN 'admin' " +
            "WHEN UPPER(COALESCE(a.rights, '')) LIKE '%MOD%' THEN 'moderator' " +
            "ELSE NULL END"

    public const val FROM_SQL: String =
        "FROM accounts a LEFT JOIN beanlab.owners o ON o.account_id = a.id"

    /** What Central stores in `accounts.rights` for [rank]. */
    public fun rightsOf(rank: Rank): Rights =
        when (rank) {
            Rank.Player -> Rights.NONE
            Rank.Moderator -> Rights.MOD
            Rank.Admin,
            Rank.Owner -> Rights.ADMINISTRATOR
        }

    public fun rank(conn: DatabaseConnection, accountId: Int): Rank? =
        conn.prepareStatement("SELECT $RANK_SQL $FROM_SQL WHERE a.id = ?").use {
            it.setInt(1, accountId)
            it.executeQuery().use { rs -> if (rs.next()) Rank.parse(rs.getString(1)) else null }
        }

    public fun save(conn: DatabaseConnection, accountId: Int, rank: Rank) {
        val rights = rightsOf(rank)
        conn.prepareStatement("UPDATE accounts SET rights = ? WHERE id = ?").use {
            it.setString(1, if (rights == Rights.NONE) "" else rights.name)
            it.setInt(2, accountId)
            it.executeUpdate()
        }
        val sql =
            if (rank == Rank.Owner) {
                "INSERT INTO beanlab.owners (account_id) VALUES (?) ON CONFLICT DO NOTHING"
            } else {
                "DELETE FROM beanlab.owners WHERE account_id = ?"
            }
        conn.prepareStatement(sql).use {
            it.setInt(1, accountId)
            it.executeUpdate()
        }
    }
}

/**
 * Checks a bearer token against `beanlab.admin_tokens`. The account's rank is read fresh each time,
 * so a demoted or deleted account loses access on its next request. Only ranks of at least
 * [MIN_TOKEN_RANK] may use the API for now (owners only).
 */
public class DbTokenStore @Inject constructor(private val access: GameAccess) : TokenStore {
    override fun verify(rawToken: String): Actor? {
        val hash = AdminTokens.hash(rawToken)
        return access.query { connection ->
            val actor =
                connection
                    .prepareStatement(
                        "SELECT a.id, a.account_name, ${AccountRanks.RANK_SQL} " +
                            "FROM beanlab.admin_tokens t JOIN accounts a ON a.id = t.account_id " +
                            "LEFT JOIN beanlab.owners o ON o.account_id = a.id " +
                            "WHERE t.token_hash = ? AND t.revoked_at IS NULL"
                    )
                    .use { statement ->
                        statement.setString(1, hash)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                Actor(rs.getInt(1), rs.getString(2), Rank.parse(rs.getString(3)))
                            } else {
                                null
                            }
                        }
                    }
            if (actor != null && actor.rank.atLeast(MIN_TOKEN_RANK)) {
                connection
                    .prepareStatement(
                        "UPDATE beanlab.admin_tokens SET last_used_at = CURRENT_TIMESTAMP " +
                            "WHERE token_hash = ?"
                    )
                    .use { statement ->
                        statement.setString(1, hash)
                        statement.executeUpdate()
                    }
                actor
            } else {
                null
            }
        }
    }

    public companion object {
        /** Widen this to let moderators or admins connect. Owners only for now. */
        public val MIN_TOKEN_RANK: Rank = Rank.Owner
    }
}

/** Writes every admin action to `beanlab.admin_audit` without making the request wait for it. */
public class DbAuditSink @Inject constructor(private val access: GameAccess) : AuditSink {
    override fun record(entry: AuditEntry) {
        access.fireAndForget { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO beanlab.admin_audit " +
                        "(actor_account_id, actor, action, target, detail, ok) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
                )
                .use { statement ->
                    statement.setInt(1, entry.actor.accountId)
                    statement.setString(2, entry.actor.username)
                    statement.setString(3, entry.action)
                    statement.setString(4, entry.target)
                    statement.setString(5, entry.detail)
                    statement.setBoolean(6, entry.ok)
                    statement.executeUpdate()
                }
        }
    }
}
