package org.rsmod.content.beanlab.admin.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.michaelbull.logging.InlineLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Loopback-only HTTP front for [AdminRouter]. Reach it through `tailscale serve`; it never listens
 * on a public address. Every request needs a valid bearer token, and the token's account decides
 * what the caller may do.
 */
public class AdminHttpServer(
    private val port: Int,
    private val tokens: TokenStore,
    private val router: AdminRouter,
    private val limiter: RateLimiter = RateLimiter(RATE_LIMIT),
) {
    private val logger = InlineLogger()
    private val mapper = ObjectMapper()
    private var server: HttpServer? = null

    /** The port actually bound, which differs from the requested one when that was 0. */
    public val boundPort: Int
        get() = server?.address?.port ?: port

    public fun start() {
        val created =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        created.executor =
            Executors.newFixedThreadPool(WORKERS) { runnable ->
                Thread(runnable, "admin-api").also { it.isDaemon = true }
            }
        created.createContext("/") { exchange -> serve(exchange) }
        created.start()
        server = created
        logger.info { "[admin-api] listening on 127.0.0.1:$port" }
    }

    public fun stop() {
        server?.stop(0)
        server = null
    }

    private fun serve(exchange: HttpExchange) {
        exchange.use {
            try {
                val actor = authenticate(it)
                if (!limiter.allow(actor.accountId)) throw ApiError(429, "Slow down a little.")
                val body = readBody(it)
                val result = router.handle(actor, it.requestMethod, path(it), query(it), body)
                reply(it, 200, result)
            } catch (error: ApiError) {
                reply(it, error.status, mapOf("error" to error.message) + error.details)
            } catch (error: Exception) {
                logger.error(error) { "[admin-api] unexpected failure on ${it.requestURI.path}" }
                reply(it, 500, mapOf("error" to "The server hit an error."))
            }
        }
    }

    private fun authenticate(exchange: HttpExchange): Actor {
        val header = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        val raw = header.removePrefix("Bearer ").trim()
        if (!header.startsWith("Bearer ") || raw.isEmpty()) throw ApiError(401, "Token missing.")
        return tokens.verify(raw) ?: throw ApiError(401, "Token rejected.")
    }

    /** `tailscale serve` may or may not strip its mount path, so accept both forms. */
    private fun path(exchange: HttpExchange): String =
        exchange.requestURI.rawPath.removePrefix("/adminapi")

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery
            ?.split('&')
            ?.filter { it.contains('=') }
            ?.associate {
                val (key, value) = it.split('=', limit = 2)
                URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            } ?: emptyMap()

    private fun readBody(exchange: HttpExchange): JsonNode {
        val bytes = exchange.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) throw ApiError(413, "Request too large.")
        if (bytes.isEmpty()) return mapper.createObjectNode()
        return try {
            mapper.readTree(bytes)
        } catch (error: Exception) {
            throw ApiError(400, "Body isn't valid JSON.")
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, body: Map<String, Any?>) {
        val bytes = mapper.writeValueAsBytes(body)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        private const val WORKERS = 4
        private const val RATE_LIMIT = 30
        private const val MAX_BODY = 64 * 1024
    }
}
