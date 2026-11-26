package org.rsmod.content.beanlab.admin.api

import com.fasterxml.jackson.databind.JsonNode

/** Mod levels in ascending order. The API only ever compares ranks, never raw strings. */
public enum class Rank {
    Player,
    Moderator,
    Admin,
    Owner;

    public val label: String
        get() = name.lowercase()

    public fun atLeast(other: Rank): Boolean = ordinal >= other.ordinal

    public companion object {
        /** `accounts.modlevel` is NULL for ordinary players. Unknown values count as players. */
        public fun parse(value: String?): Rank =
            entries.firstOrNull { it.label == value?.trim()?.lowercase() } ?: Player
    }
}

/** Who is calling: the account behind a token and the mod level it has right now. */
public data class Actor(val accountId: Int, val username: String, val rank: Rank)

/** A refusal or failure with the HTTP status to send and a message fit to show an admin. */
public class ApiError(public val status: Int, message: String) : RuntimeException(message)

/** A write the API performed (or refused), recorded for the audit log. */
public data class AuditEntry(
    val actor: Actor,
    val action: String,
    val target: String?,
    val detail: String?,
    val ok: Boolean,
)

public fun interface AuditSink {
    public fun record(entry: AuditEntry)
}

/** Resolves a raw bearer token to the actor it belongs to, or null when it is not valid. */
public fun interface TokenStore {
    public fun verify(rawToken: String): Actor?
}

/**
 * Everything the API can do. The game implementation runs on the game thread; tests use a fake. All
 * permission checks live in [AdminRouter], so a backend only needs to do the work.
 */
public interface AdminBackend {
    public fun players(): Map<String, Any?>

    /** Discord picture link and name for an account, or an empty map when there is none. */
    public fun identity(accountId: Int): Map<String, Any?> = emptyMap()

    public fun player(name: String): Map<String, Any?>

    /** Rank of an online player, or null when they are not online. */
    public fun playerRank(name: String): Rank?

    public fun playerAction(actor: Actor, name: String, action: String, body: JsonNode): String

    public fun world(): Map<String, Any?>

    public fun teleports(): Map<String, Any?>

    public fun teleport(actor: Actor, target: String, x: Int, z: Int, level: Int): String

    public fun broadcast(actor: Actor, message: String): String

    public fun reboot(actor: Actor, seconds: Int): String

    public fun searchItems(query: String): Map<String, Any?>

    public fun accounts(query: String): Map<String, Any?>

    /** Rank of a stored account, or null when the id does not exist. */
    public fun accountRank(id: Int): Rank?

    public fun createAccount(actor: Actor, username: String, rank: Rank): Map<String, Any?>

    public fun setAccountRank(actor: Actor, id: Int, rank: Rank): String

    public fun resetPassword(actor: Actor, id: Int): Map<String, Any?>

    public fun deleteAccount(actor: Actor, id: Int): String

    public fun audit(limit: Int): Map<String, Any?>
}
