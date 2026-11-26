package org.rsmod.content.beanlab.admin.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.rsmod.content.beanlab.hub.HubLayoutService

class AdminRouterTest {
    private val mapper = ObjectMapper()
    private val owner = Actor(1, "boss", Rank.Owner)
    private val admin = Actor(2, "ada", Rank.Admin)
    private val mod = Actor(3, "mo", Rank.Moderator)
    private val audits = mutableListOf<AuditEntry>()
    private val backend = FakeBackend()
    private val hubDir = java.nio.file.Files.createTempDirectory("hub")
    private val hubFile = hubDir.resolve("hub-layout.json")
    private val router =
        AdminRouter(backend, { audits += it }, FileHubLayoutPort(HubLayoutService(hubFile)))

    private fun json(text: String): JsonNode = mapper.readTree(text)

    private fun call(actor: Actor, method: String, path: String, body: String = "{}") =
        router.handle(actor, method, path, emptyMap(), json(body))

    private fun refusal(actor: Actor, method: String, path: String, body: String = "{}"): Int =
        assertFailsWith<ApiError> { call(actor, method, path, body) }.status

    @Test
    fun `moderators can read players but not use admin pages`() {
        assertEquals("mo", call(mod, "GET", "/v1/me")["name"])
        call(mod, "GET", "/v1/players")
        assertEquals(403, refusal(mod, "GET", "/v1/audit"))
        assertEquals(403, refusal(mod, "GET", "/v1/accounts"))
        assertEquals(403, refusal(mod, "GET", "/v1/items/search"))
    }

    @Test
    fun `each player action enforces its own minimum rank`() {
        call(mod, "POST", "/v1/players/Bob/heal")
        assertEquals(403, refusal(mod, "POST", "/v1/players/Bob/give", """{"id":4151,"qty":1}"""))
        call(admin, "POST", "/v1/players/Bob/give", """{"id":4151,"qty":1}""")
        assertEquals(404, refusal(admin, "POST", "/v1/players/Bob/explode"))
    }

    @Test
    fun `nobody can act on someone ranked above them except themselves`() {
        backend.online["Boss"] = Rank.Owner
        backend.online["Mo"] = Rank.Moderator
        assertEquals(403, refusal(admin, "POST", "/v1/players/Boss/kick"))
        call(mod, "POST", "/v1/players/Mo/heal")
        call(owner, "POST", "/v1/players/Mo/kick")
    }

    @Test
    fun `offline players are a 404`() {
        assertEquals(404, refusal(admin, "POST", "/v1/players/Ghost/heal"))
    }

    @Test
    fun `nobody can hand out a rank above their own`() {
        val toOwner = """{"username":"newbie","modlevel":"owner"}"""
        assertEquals(403, refusal(admin, "POST", "/v1/accounts", toOwner))
        call(admin, "POST", "/v1/accounts", """{"username":"newbie","modlevel":"admin"}""")
        val promote = """{"modlevel":"owner"}"""
        assertEquals(403, refusal(admin, "POST", "/v1/players/Bob/modlevel", promote))
        call(owner, "POST", "/v1/players/Bob/modlevel", promote)
    }

    @Test
    fun `account rank changes respect the targets rank`() {
        backend.accountRanks[7] = Rank.Owner
        val demote = """{"modlevel":"player"}"""
        assertEquals(403, refusal(admin, "POST", "/v1/accounts/7/modlevel", demote))
        assertEquals(403, refusal(admin, "POST", "/v1/accounts/7/password"))
        assertEquals(404, refusal(owner, "POST", "/v1/accounts/99/password"))
    }

    @Test
    fun `deleting accounts and rebooting are owner only`() {
        backend.accountRanks[5] = Rank.Player
        assertEquals(403, refusal(admin, "DELETE", "/v1/accounts/5"))
        assertEquals(403, refusal(admin, "POST", "/v1/world/reboot", """{"seconds":60}"""))
        call(owner, "DELETE", "/v1/accounts/5")
        call(owner, "POST", "/v1/world/reboot", """{"seconds":60}""")
    }

    @Test
    fun `bad input is rejected before reaching the backend`() {
        assertEquals(400, refusal(owner, "POST", "/v1/world/reboot", """{"seconds":5}"""))
        assertEquals(400, refusal(admin, "POST", "/v1/world/broadcast", """{"message":"  "}"""))
        assertEquals(400, refusal(admin, "POST", "/v1/accounts", """{"username":"x"}"""))
        val badLevel = """{"target":"Bob","x":1,"z":2,"level":9}"""
        assertEquals(400, refusal(mod, "POST", "/v1/teleport", badLevel))
        assertTrue(backend.calls.none { it.startsWith("reboot") || it.startsWith("broadcast") })
    }

    @Test
    fun `hub layout routes are owner only`() {
        assertEquals(403, refusal(admin, "GET", "/v1/hub/layout"))
        assertEquals(403, refusal(admin, "PUT", "/v1/hub/layout", "{}"))
        assertEquals(403, refusal(mod, "GET", "/v1/hub/layout"))
        assertFalse(java.nio.file.Files.exists(hubFile))
        assertEquals("PUT /v1/hub/layout", audits.last().action)
        assertFalse(audits.last().ok)
    }

    @Test
    fun `hub layout reads the bundled file and saves a valid edit as an override`() {
        val first = call(owner, "GET", "/v1/hub/layout")
        assertEquals("bundled", first["source"])
        assertEquals(false, first["floorsEditable"])
        val layout = first["layout"] as JsonNode
        assertEquals(1, layout["version"].asInt())

        val edited = layout.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        edited.putObject("arrival").put("x", 80).put("z", 12)
        val saved = call(owner, "PUT", "/v1/hub/layout", edited.toString())
        assertEquals(true, saved["ok"])
        assertEquals("hub-layout", audits.last().action)
        assertTrue(audits.last().ok)

        val again = call(owner, "GET", "/v1/hub/layout")
        assertEquals("override", again["source"])
        assertEquals(80, (again["layout"] as JsonNode)["arrival"]["x"].asInt())
    }

    @Test
    fun `hub layout problems come back as a list and nothing is written`() {
        val error =
            assertFailsWith<ApiError> {
                call(owner, "PUT", "/v1/hub/layout", """{"version":1,"size":100}""")
            }
        assertEquals(422, error.status)
        @Suppress("UNCHECKED_CAST") val problems = error.details["problems"] as List<String>
        assertTrue(problems.any { it == "palette is empty" })
        assertFalse(java.nio.file.Files.exists(hubFile))
        assertFalse(audits.last().ok)
    }

    @Test
    fun `unknown paths are 404 and wrong methods are 405`() {
        assertEquals(404, refusal(owner, "GET", "/v1/nope"))
        assertEquals(405, refusal(owner, "PUT", "/v1/players"))
    }

    @Test
    fun `writes are audited including refusals by the backend`() {
        call(admin, "POST", "/v1/players/Bob/give", """{"id":4151,"qty":2}""")
        assertEquals("give", audits.last().action)
        assertTrue(audits.last().ok)
    }

    @Test
    fun `backend failures are audited as not ok`() {
        backend.failNext = true
        assertFailsWith<ApiError> {
            call(admin, "POST", "/v1/world/broadcast", """{"message":"hi"}""")
        }
        assertFalse(audits.last().ok)
    }

    @Test
    fun `ranks parse like the database stores them`() {
        assertEquals(Rank.Player, Rank.parse(null))
        assertEquals(Rank.Admin, Rank.parse(" Admin "))
        assertEquals(Rank.Player, Rank.parse("superuser"))
        assertTrue(Rank.Owner.atLeast(Rank.Moderator))
        assertFalse(Rank.Moderator.atLeast(Rank.Admin))
    }

    @Test
    fun `skill names match the client where RsMod differs`() {
        assertEquals("Runecraft", SkillNames.toApi("Runecrafting"))
        assertEquals("Attack", SkillNames.toApi("Attack"))
    }

    @Test
    fun `tokens are hashed and unique`() {
        val a = AdminTokens.generate()
        assertNotEquals(a, AdminTokens.generate())
        assertTrue(a.startsWith("bsa_"))
        assertEquals(64, AdminTokens.hash(a).length)
        assertNotEquals(a, AdminTokens.hash(a))
    }

    @Test
    fun `rate limiter allows a burst then blocks until the window passes`() {
        var now = 0L
        val limiter = RateLimiter(3) { now }
        assertTrue(limiter.allow(1) && limiter.allow(1) && limiter.allow(1))
        assertFalse(limiter.allow(1))
        assertTrue(limiter.allow(2))
        now += 1_100_000_000L
        assertTrue(limiter.allow(1))
    }

    private class FakeBackend : AdminBackend {
        val online = mutableMapOf("Bob" to Rank.Player)
        val accountRanks = mutableMapOf<Int, Rank>()
        val calls = mutableListOf<String>()
        var failNext = false

        override fun players() = mapOf("players" to emptyList<Any>())

        override fun player(name: String) = mapOf("name" to name)

        override fun playerRank(name: String): Rank? =
            online.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        override fun playerAction(
            actor: Actor,
            name: String,
            action: String,
            body: JsonNode,
        ): String {
            calls += "$action $name"
            return "$action done"
        }

        override fun world() = emptyMap<String, Any?>()

        override fun teleports() = emptyMap<String, Any?>()

        override fun teleport(actor: Actor, target: String, x: Int, z: Int, level: Int) = "ok"

        override fun broadcast(actor: Actor, message: String): String {
            if (failNext) throw ApiError(503, "busy")
            calls += "broadcast"
            return "sent"
        }

        override fun reboot(actor: Actor, seconds: Int): String {
            calls += "reboot $seconds"
            return "rebooting"
        }

        override fun searchItems(query: String) = emptyMap<String, Any?>()

        override fun accounts(query: String) = emptyMap<String, Any?>()

        override fun accountRank(id: Int): Rank? = accountRanks[id]

        override fun createAccount(actor: Actor, username: String, rank: Rank) =
            mapOf<String, Any?>("password" to "pw")

        override fun setAccountRank(actor: Actor, id: Int, rank: Rank) = "ok"

        override fun resetPassword(actor: Actor, id: Int) = mapOf<String, Any?>("password" to "pw")

        override fun deleteAccount(actor: Actor, id: Int) = "deleted"

        override fun audit(limit: Int) = emptyMap<String, Any?>()
    }
}
