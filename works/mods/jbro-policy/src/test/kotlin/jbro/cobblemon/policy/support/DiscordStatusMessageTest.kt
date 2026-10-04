package jbro.cobblemon.policy.support

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordStatusMessageTest {
    private val at = Instant.parse("2026-10-01T13:00:00Z")

    @Test
    fun `open shows the player count, closed shows when it closed`() {
        val open = DiscordStatusMessage.payload(true, 3, at).getAsJsonArray("embeds")[0].asJsonObject
        assertEquals("🟢 서버가 열려있어요!!!", open.get("title").asString)
        assertEquals("3명", open.getAsJsonArray("fields")[0].asJsonObject.get("value").asString)
        assertEquals("2026-10-01T13:00:00Z", open.get("timestamp").asString)
        val closed = DiscordStatusMessage.payload(false, 0, at).getAsJsonArray("embeds")[0].asJsonObject
        assertEquals("🔴 서버가 닫혀있어요...", closed.get("title").asString)
        assertFalse(closed.has("fields"))
        assertEquals("닫힌 시각", closed.getAsJsonObject("footer").get("text").asString)
    }

    @Test
    fun `the message is posted once, then edited, and posted again when it was deleted`() {
        val calls = mutableListOf<String>()
        val titles = mutableListOf<String>()
        var nextId = 111
        var deleted = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v10/channels/42/messages") { exchange ->
            val body = JsonParser.parseString(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)).asJsonObject
            titles += body.getAsJsonArray("embeds")[0].asJsonObject.get("title").asString
            calls += exchange.requestMethod + " " + exchange.requestURI.path.substringAfter("/messages")
            val (status, reply) = when {
                exchange.requestMethod == "POST" -> 200 to """{"id":"${nextId++}"}"""
                deleted -> 404 to """{"message":"Unknown Message"}"""
                else -> 200 to "{}"
            }
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val state = Files.createTempDirectory("discord").resolve("status.json")
        try {
            DiscordWebhook.apiBase = "http://127.0.0.1:${server.address.port}/api/v10"
            DiscordStatusMessage("TOKEN", "42", state).show(true, 2, at)
            DiscordStatusMessage("TOKEN", "42", state).show(false, 0, at)
            deleted = true
            DiscordStatusMessage("TOKEN", "42", state).show(true, 0, at)
            assertEquals(listOf("POST ", "PATCH /111", "PATCH /111", "POST "), calls)
            assertEquals("🔴 서버가 닫혀있어요...", titles[1])
            assertTrue(Files.readString(state).contains("\"112\""))
        } finally {
            DiscordWebhook.apiBase = "https://discord.com/api/v10"
            server.stop(0)
        }
    }
}
