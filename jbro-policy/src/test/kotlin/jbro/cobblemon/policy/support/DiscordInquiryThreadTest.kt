package jbro.cobblemon.policy.support

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordInquiryThreadTest {
    private val inquiry = Inquiry("김빡주", "Park_JH", UUID.fromString("f3d28cb0-7225-3cb1-baeb-2dadd2be89ae"),
        "광장에 못 가요", Inquiries.Via.COMMAND, 0, "0a1b2c3d")

    @Test
    fun `settings read the verified role and keep nickname sync on unless told otherwise`() {
        val settings = DiscordSettings.parse("""{"botToken": "T", "verifiedRoleId": "77"}""")
        assertEquals("77", settings.verifiedRoleId)
        assertTrue(settings.syncNickname)
        assertFalse(DiscordSettings.parse("""{"syncNickname": false}""").syncNickname)
    }

    @Test
    fun `the bot opens a private thread, posts the card in it and adds the player's Discord account`() {
        val calls = mutableListOf<String>()
        var thread = ""
        var threadStatus = 201
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v10/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/api/v10")
            val body = String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            calls += "${exchange.requestMethod} $path"
            val answer = when {
                path == "/channels/42/threads" -> { thread = body; """{"id": "900"}""" }
                path.endsWith("/messages") -> """{"id": "555"}"""
                else -> null
            }
            val status = if (path.endsWith("/threads")) threadStatus else if (answer == null) 204 else 200
            if (answer == null || status !in 200..299) exchange.sendResponseHeaders(status, -1)
            else answer.toByteArray().let { bytes -> exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            DiscordWebhook.apiBase = "http://127.0.0.1:${server.address.port}/api/v10"
            val route = DiscordSettings.InquiryRoute.Bot("TOKEN", "42")
            assertEquals(DiscordWebhook.Card("900", "555"), DiscordWebhook.send(route, inquiry, "31337"))
            assertEquals(listOf("POST /channels/42/threads", "POST /channels/900/messages", "PUT /channels/900/thread-members/31337"), calls)
            val opened = JsonParser.parseString(thread).asJsonObject
            assertEquals(12, opened.get("type").asInt)
            assertFalse(opened.get("invitable").asBoolean)
            assertEquals("문의 0a1b2c3d · 김빡주", opened.get("name").asString)

            // No thread permission: the card goes in the channel, and nobody is added.
            calls.clear()
            threadStatus = 403
            assertEquals(DiscordWebhook.Card("42", "555"), DiscordWebhook.send(route, inquiry, "31337"))
            assertEquals(listOf("POST /channels/42/threads", "POST /channels/42/messages"), calls)
        } finally {
            DiscordWebhook.apiBase = "https://discord.com/api/v10"
            server.stop(0)
        }
    }
}
