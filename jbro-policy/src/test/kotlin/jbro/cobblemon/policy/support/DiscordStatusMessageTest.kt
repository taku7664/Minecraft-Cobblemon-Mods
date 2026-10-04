package jbro.cobblemon.policy.support

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordStatusMessageTest {
    private val korean = translations("ko_kr")

    private fun translations(locale: String): (String) -> String? {
        val entries = requireNotNull(javaClass.getResourceAsStream("/assets/jbro_policy/lang/$locale.json"))
            .reader(StandardCharsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        return { key -> entries.get(key)?.asString }
    }

    @Test
    fun `open and closed are ordinary chat messages without embeds or mentions`() {
        val open = DiscordStatusMessage.payload(true, korean)
        assertEquals("🟢 서버가 열렸어요!", open.get("content").asString)
        val closed = DiscordStatusMessage.payload(false, korean)
        assertEquals("🔴 서버가 닫혔어요.", closed.get("content").asString)
        for (message in listOf(open, closed)) {
            assertFalse(message.has("embeds"))
            assertEquals(0, message.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size())
        }
    }

    @Test
    fun `English translations and missing translation fallback are readable`() {
        for (translate in listOf(translations("en_us"), { _: String -> null })) {
            assertEquals("🟢 The server is open!", DiscordStatusMessage.payload(true, translate).get("content").asString)
            assertEquals("🔴 The server is closed.", DiscordStatusMessage.payload(false, translate).get("content").asString)
        }
    }

    @Test
    fun `each server opening and closing posts a new message across restarts`() {
        val calls = mutableListOf<String>()
        val messages = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v10/channels/42/messages") { exchange ->
            val body = JsonParser.parseString(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)).asJsonObject
            messages += body.get("content").asString
            calls += exchange.requestMethod + " " + exchange.requestURI.path.substringAfter("/messages")
            val bytes = """{"id":"${messages.size}"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            DiscordWebhook.apiBase = "http://127.0.0.1:${server.address.port}/api/v10"
            val rest = DiscordRest("TOKEN")
            repeat(2) {
                val status = DiscordStatusMessage(rest, "42", korean)
                status.show(true)
                status.show(false)
            }
            assertEquals(listOf("POST ", "POST ", "POST ", "POST "), calls)
            assertEquals(listOf("🟢 서버가 열렸어요!", "🔴 서버가 닫혔어요.", "🟢 서버가 열렸어요!", "🔴 서버가 닫혔어요."), messages)
        } finally {
            DiscordWebhook.apiBase = "https://discord.com/api/v10"
            server.stop(0)
        }
    }

    @Test
    fun `a rate limited notification retries and a permission failure is reported`() {
        var calls = 0
        var forbidden = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v10/channels/42/messages") { exchange ->
            exchange.requestBody.readAllBytes()
            calls++
            val (code, reply) = when {
                forbidden -> 403 to """{"message":"Missing Permissions"}"""
                calls == 1 -> 429 to """{"retry_after":0.0}"""
                else -> 200 to """{"id":"111"}"""
            }
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            DiscordWebhook.apiBase = "http://127.0.0.1:${server.address.port}/api/v10"
            val status = DiscordStatusMessage(DiscordRest("TOKEN"), "42", korean)
            status.show(true)
            assertEquals(2, calls)
            forbidden = true
            val failure = assertThrows(IllegalStateException::class.java) { status.show(false) }
            assertTrue(failure.message.orEmpty().contains("403"))
            assertEquals(3, calls)
        } finally {
            DiscordWebhook.apiBase = "https://discord.com/api/v10"
            server.stop(0)
        }
    }
}
