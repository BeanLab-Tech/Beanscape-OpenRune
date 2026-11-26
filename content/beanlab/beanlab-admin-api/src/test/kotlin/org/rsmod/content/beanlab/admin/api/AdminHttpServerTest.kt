package org.rsmod.content.beanlab.admin.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the real HTTP server on a loopback port and talks to it like the plugin does. */
class AdminHttpServerTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private val audits = mutableListOf<AuditEntry>()
    private val ownerToken = "bsa_owner"
    private val adminToken = "bsa_admin"
    private lateinit var server: AdminHttpServer

    @BeforeTest
    fun start() {
        val tokens = TokenStore {
            when (it) {
                ownerToken -> Actor(1, "boss", Rank.Owner)
                adminToken -> Actor(2, "ada", Rank.Admin)
                else -> null
            }
        }
        server =
            AdminHttpServer(0, tokens, AdminRouter(StubBackend(), { audits += it }), RateLimiter(5))
        server.start()
    }

    @AfterTest fun stop() = server.stop()

    private fun send(
        method: String,
        path: String,
        token: String? = ownerToken,
        body: String? = null,
    ): Pair<Int, JsonNode> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.boundPort}$path"))
        if (token != null) builder.header("Authorization", "Bearer $token")
        builder.method(
            method,
            if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(body),
        )
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to mapper.readTree(response.body())
    }

    @Test
    fun `requests without a valid token are rejected`() {
        assertEquals(401, send("GET", "/v1/me", token = null).first)
        assertEquals(401, send("GET", "/v1/me", token = "bsa_wrong").first)
    }

    @Test
    fun `a valid token reaches the router and the reply is json`() {
        val (status, json) = send("GET", "/v1/me")
        assertEquals(200, status)
        assertEquals("boss", json["name"].asText())
        assertEquals("owner", json["modlevel"].asText())
    }

    @Test
    fun `the tailscale mount prefix is accepted`() {
        assertEquals(200, send("GET", "/adminapi/v1/me").first)
    }

    @Test
    fun `permissions are enforced and refusals carry a readable message`() {
        val (status, json) = send("GET", "/v1/audit", token = adminToken)
        assertEquals(200, status)
        val (denied, error) = send("POST", "/v1/world/reboot", adminToken, """{"seconds":60}""")
        assertEquals(403, denied)
        assertTrue(error["error"].asText().contains("owner"))
        assertFalse(json.has("error"))
        assertEquals("POST /v1/world/reboot", audits.last().action)
        assertFalse(audits.last().ok)
    }

    @Test
    fun `bodies and query strings are parsed`() {
        val (status, json) = send("GET", "/v1/items/search?q=whip%20x")
        assertEquals(200, status)
        assertEquals("whip x", json["query"].asText())
        assertEquals(400, send("POST", "/v1/world/broadcast", body = "not json").first)
    }

    @Test
    fun `callers are rate limited per account`() {
        repeat(5) { assertEquals(200, send("GET", "/v1/me").first) }
        assertEquals(429, send("GET", "/v1/me").first)
        assertEquals(200, send("GET", "/v1/me", token = adminToken).first)
    }

    private class StubBackend : AdminBackend {
        override fun players() = emptyMap<String, Any?>()

        override fun player(name: String) = emptyMap<String, Any?>()

        override fun playerRank(name: String): Rank? = null

        override fun playerAction(actor: Actor, name: String, action: String, body: JsonNode) = "ok"

        override fun world() = emptyMap<String, Any?>()

        override fun teleports() = emptyMap<String, Any?>()

        override fun teleport(actor: Actor, target: String, x: Int, z: Int, level: Int) = "ok"

        override fun broadcast(actor: Actor, message: String) = "sent"

        override fun reboot(actor: Actor, seconds: Int) = "ok"

        override fun searchItems(query: String) = mapOf<String, Any?>("query" to query)

        override fun accounts(query: String) = emptyMap<String, Any?>()

        override fun accountRank(id: Int): Rank? = null

        override fun createAccount(actor: Actor, username: String, rank: Rank) =
            emptyMap<String, Any?>()

        override fun setAccountRank(actor: Actor, id: Int, rank: Rank) = "ok"

        override fun resetPassword(actor: Actor, id: Int) = emptyMap<String, Any?>()

        override fun deleteAccount(actor: Actor, id: Int) = "ok"

        override fun audit(limit: Int) = emptyMap<String, Any?>()
    }
}
