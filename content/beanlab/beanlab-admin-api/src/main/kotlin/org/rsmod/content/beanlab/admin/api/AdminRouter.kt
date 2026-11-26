package org.rsmod.content.beanlab.admin.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Maps requests to [AdminBackend] calls and owns every permission rule, so the rules are the same
 * whoever calls and can be tested without a running server:
 * - each route has a minimum rank, checked before anything else;
 * - nobody may act on a player or account ranked above them (self is always allowed);
 * - nobody may hand out a rank above their own;
 * - every write is audited, including refusals.
 */
public class AdminRouter(
    private val backend: AdminBackend,
    private val audit: AuditSink,
    private val hub: HubLayoutPort = FileHubLayoutPort(),
) {
    private class Route(
        val method: String,
        val pattern: Regex,
        val minimum: Rank,
        val handler: (Call) -> Map<String, Any?>,
    )

    private class Call(
        val actor: Actor,
        val groups: List<String>,
        val query: Map<String, String>,
        val body: JsonNode,
    )

    private val mapper = ObjectMapper()

    private val routes: List<Route> =
        listOf(
            route("GET", "/v1/me", Rank.Moderator) {
                mapOf("name" to it.actor.username, "modlevel" to it.actor.rank.label) +
                    backend.identity(it.actor.accountId)
            },
            route("GET", "/v1/players", Rank.Moderator) { backend.players() },
            route("GET", "/v1/players/([^/]+)", Rank.Moderator) { backend.player(it.groups[0]) },
            route("GET", "/v1/world", Rank.Moderator) { backend.world() },
            route("GET", "/v1/teleports", Rank.Moderator) { backend.teleports() },
            route("GET", "/v1/items/search", Rank.Admin) {
                backend.searchItems(it.query["q"] ?: "")
            },
            route("GET", "/v1/accounts", Rank.Admin) { backend.accounts(it.query["q"] ?: "") },
            route("GET", "/v1/audit", Rank.Admin) {
                backend.audit((it.query["limit"]?.toIntOrNull() ?: DEFAULT_AUDIT).coerceIn(1, 200))
            },
            route("POST", "/v1/players/([^/]+)/([a-z-]+)", Rank.Moderator) { playerAction(it) },
            route("POST", "/v1/teleport", Rank.Moderator) { teleport(it) },
            route("POST", "/v1/world/broadcast", Rank.Admin) { broadcast(it) },
            route("POST", "/v1/world/reboot", Rank.Owner) { reboot(it) },
            route("GET", "/v1/hub/layout", Rank.Owner) { hubLayout() },
            route("PUT", "/v1/hub/layout", Rank.Owner) { saveHubLayout(it) },
            route("POST", "/v1/accounts", Rank.Admin) { createAccount(it) },
            route("POST", "/v1/accounts/(\\d+)/modlevel", Rank.Admin) { setAccountRank(it) },
            route("POST", "/v1/accounts/(\\d+)/password", Rank.Admin) { resetPassword(it) },
            route("DELETE", "/v1/accounts/(\\d+)", Rank.Owner) { deleteAccount(it) },
        )

    public fun handle(
        actor: Actor,
        method: String,
        path: String,
        query: Map<String, String>,
        body: JsonNode,
    ): Map<String, Any?> {
        var pathMatched = false
        for (route in routes) {
            val match = route.pattern.matchEntire(path) ?: continue
            pathMatched = true
            if (route.method != method) continue
            if (!actor.rank.atLeast(route.minimum)) {
                if (method != "GET") {
                    audit.record(
                        AuditEntry(
                            actor,
                            "$method $path",
                            null,
                            "refused: needs ${route.minimum.label}",
                            false,
                        )
                    )
                }
                throw ApiError(403, "That needs ${route.minimum.label} level.")
            }
            return route.handler(Call(actor, match.groupValues.drop(1), query, body))
        }
        throw if (pathMatched) ApiError(405, "Method not allowed.") else ApiError(404, "Not found.")
    }

    private fun route(
        method: String,
        path: String,
        minimum: Rank,
        handler: (Call) -> Map<String, Any?>,
    ): Route = Route(method, Regex(path), minimum, handler)

    // ---------------------------------------------------------------- players

    private fun playerAction(call: Call): Map<String, Any?> {
        val name = decode(call.groups[0])
        val action = call.groups[1]
        val needed = PLAYER_ACTIONS[action] ?: throw ApiError(404, "Unknown action: $action.")
        if (!call.actor.rank.atLeast(needed)) {
            throw ApiError(403, "That needs ${needed.label} level.")
        }
        requireTargetRank(call.actor, name, call.body, action)
        return audited(call.actor, action, name, describe(call.body)) {
            mapOf(
                "ok" to true,
                "message" to backend.playerAction(call.actor, name, action, call.body),
            )
        }
    }

    /** Players can act on equals and below. Promoting is checked separately in [checkGrant]. */
    private fun requireTargetRank(actor: Actor, name: String, body: JsonNode, action: String) {
        val target = backend.playerRank(name) ?: throw ApiError(404, "$name isn't online.")
        if (!actor.username.equals(name, ignoreCase = true) && target > actor.rank) {
            throw ApiError(403, "You can't $action someone ranked above you.")
        }
        if (action == "modlevel") {
            checkGrant(actor, Rank.parse(body.path("modlevel").asText()))
        }
    }

    private fun teleport(call: Call): Map<String, Any?> {
        val target = call.body.path("target").asText("")
        if (target.isEmpty()) throw ApiError(400, "Who should be teleported?")
        requireTargetRank(call.actor, target, call.body, "teleport")
        val x = int(call.body, "x")
        val z = int(call.body, "z")
        val level = call.body.path("level").asInt(0)
        if (level !in 0..3) throw ApiError(400, "Level must be 0 to 3.")
        return audited(call.actor, "teleport", target, "$x, $z, $level") {
            mapOf("ok" to true, "message" to backend.teleport(call.actor, target, x, z, level))
        }
    }

    // ---------------------------------------------------------------- world

    private fun broadcast(call: Call): Map<String, Any?> {
        val message = call.body.path("message").asText("").trim()
        if (message.isEmpty() || message.length > MAX_BROADCAST) {
            throw ApiError(400, "Messages are 1 to $MAX_BROADCAST characters.")
        }
        return audited(call.actor, "broadcast", null, message) {
            mapOf("ok" to true, "message" to backend.broadcast(call.actor, message))
        }
    }

    private fun reboot(call: Call): Map<String, Any?> {
        val seconds = int(call.body, "seconds")
        if (seconds !in MIN_REBOOT..MAX_REBOOT) {
            throw ApiError(400, "Pick a countdown between $MIN_REBOOT and $MAX_REBOOT seconds.")
        }
        return audited(call.actor, "reboot", null, "in ${seconds}s") {
            mapOf("ok" to true, "message" to backend.reboot(call.actor, seconds))
        }
    }

    // ---------------------------------------------------------------- hub layout

    private fun hubLayout(): Map<String, Any?> {
        val (text, source) = hub.read()
        val layout =
            try {
                mapper.readTree(text)
            } catch (error: Exception) {
                throw ApiError(500, "The hub layout file isn't valid JSON. Save a new one over it.")
            }
        return mapOf(
            "source" to source,
            "layout" to layout,
            "floorsEditable" to false,
            "note" to
                "Walls, objects and NPCs apply after a server restart. Floors need a cache rebuild.",
        )
    }

    private fun saveHubLayout(call: Call): Map<String, Any?> {
        val text = call.body.toString()
        return audited(call.actor, "hub-layout", null, "${text.length} bytes") {
            val problems = hub.write(text)
            if (problems.isNotEmpty()) {
                throw ApiError(
                    422,
                    "The layout has ${problems.size} problem(s); nothing was saved.",
                    mapOf("problems" to problems),
                )
            }
            mapOf(
                "ok" to true,
                "message" to "Saved. Restart the server to apply walls, objects and NPCs.",
                "problems" to emptyList<String>(),
            )
        }
    }

    // ---------------------------------------------------------------- accounts

    private fun createAccount(call: Call): Map<String, Any?> {
        val username = call.body.path("username").asText("").trim().lowercase()
        if (!USERNAME.matches(username)) {
            throw ApiError(400, "Names are 2 to 12 letters, numbers or underscores.")
        }
        val rank = Rank.parse(call.body.path("modlevel").asText("player"))
        checkGrant(call.actor, rank)
        return audited(call.actor, "create-account", username, rank.label) {
            backend.createAccount(call.actor, username, rank) + ("ok" to true)
        }
    }

    private fun setAccountRank(call: Call): Map<String, Any?> {
        val id = call.groups[0].toInt()
        val rank = Rank.parse(call.body.path("modlevel").asText(""))
        checkGrant(call.actor, rank)
        requireAccountBelow(call.actor, id)
        return audited(call.actor, "account-modlevel", "#$id", rank.label) {
            mapOf("ok" to true, "message" to backend.setAccountRank(call.actor, id, rank))
        }
    }

    private fun resetPassword(call: Call): Map<String, Any?> {
        val id = call.groups[0].toInt()
        requireAccountBelow(call.actor, id)
        return audited(call.actor, "reset-password", "#$id", null) {
            backend.resetPassword(call.actor, id) + ("ok" to true)
        }
    }

    private fun deleteAccount(call: Call): Map<String, Any?> {
        val id = call.groups[0].toInt()
        requireAccountBelow(call.actor, id)
        return audited(call.actor, "delete-account", "#$id", null) {
            mapOf("ok" to true, "message" to backend.deleteAccount(call.actor, id))
        }
    }

    private fun requireAccountBelow(actor: Actor, id: Int) {
        val rank = backend.accountRank(id) ?: throw ApiError(404, "No such account.")
        if (rank > actor.rank) throw ApiError(403, "That account is ranked above you.")
    }

    private fun checkGrant(actor: Actor, granted: Rank) {
        if (granted > actor.rank) {
            throw ApiError(403, "You can't hand out ${granted.label}; you are ${actor.rank.label}.")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun audited(
        actor: Actor,
        action: String,
        target: String?,
        detail: String?,
        block: () -> Map<String, Any?>,
    ): Map<String, Any?> {
        try {
            val result = block()
            audit.record(AuditEntry(actor, action, target, detail, ok = true))
            return result
        } catch (error: ApiError) {
            audit.record(
                AuditEntry(
                    actor,
                    action,
                    target,
                    "${detail.orEmpty()} (${error.message})",
                    ok = false,
                )
            )
            throw error
        }
    }

    private fun int(body: JsonNode, key: String): Int {
        val node = body.path(key)
        if (!node.isInt) throw ApiError(400, "Missing or invalid number: $key.")
        return node.asInt()
    }

    private fun describe(body: JsonNode): String? =
        if (body.isMissingNode || body.isEmpty) null else body.toString().take(MAX_DETAIL)

    private fun decode(value: String): String = java.net.URLDecoder.decode(value, Charsets.UTF_8)

    public companion object {
        private const val DEFAULT_AUDIT = 50
        private const val MAX_BROADCAST = 200
        private const val MAX_DETAIL = 300
        private const val MIN_REBOOT = 30
        private const val MAX_REBOOT = 1800
        private val USERNAME = Regex("[a-z0-9_]{2,12}")

        /** Lowest rank allowed to run each per-player action. */
        public val PLAYER_ACTIONS: Map<String, Rank> =
            mapOf(
                "teleport-to" to Rank.Moderator,
                "summon" to Rank.Moderator,
                "heal" to Rank.Moderator,
                "kick" to Rank.Moderator,
                "give" to Rank.Admin,
                "stats" to Rank.Admin,
                "wipe-inventory" to Rank.Admin,
                "modlevel" to Rank.Admin,
            )
    }
}
