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
import org.junit.jupiter.api.assertThrows

class DiscordWebhookTest {
    private val id = UUID.fromString("f3d28cb0-7225-3cb1-baeb-2dadd2be89ae")
    private val inquiry = Inquiry("김빡주", "Park_JH", id, "@everyone 광장에 못 가요", Inquiries.Via.WIKI, 0)

    @Test
    fun `Discord stays off until a webhook URL is set, and takes only webhook URLs`() {
        assertFalse(DiscordSettings().configured)
        val url = "https://discord.com/api/webhooks/123456789/abc_DEF-1"
        assertTrue(DiscordSettings.parse("""{"webhookUrl": " $url "}""").configured)
        assertTrue(DiscordSettings.isWebhookUrl("https://canary.discord.com/api/webhooks/1/x"))
        assertThrows<IllegalArgumentException> { DiscordSettings.parse("""{"webhookUrl": "https://example.com/hook"}""") }
        assertThrows<IllegalArgumentException> { DiscordSettings("http://discord.com/api/webhooks/1/x") }
    }

    @Test
    fun `the invite is read from the config and takes only Discord invite links`() {
        assertEquals("", DiscordSettings().inviteUrl)
        assertEquals("https://discord.gg/HbKxTFeGB", DiscordSettings.parse("""{"inviteUrl": " https://discord.gg/HbKxTFeGB "}""").inviteUrl)
        DiscordSettings(inviteUrl = "https://discord.com/invite/HbKxTFeGB")
        assertThrows<IllegalArgumentException> { DiscordSettings(inviteUrl = "https://example.com/HbKxTFeGB") }
        assertThrows<IllegalArgumentException> { DiscordSettings(inviteUrl = "discord.gg/HbKxTFeGB") }
    }

    @Test
    fun `with a channel the bot posts inquiries, without one the webhook does`() {
        val url = "https://discord.com/api/webhooks/1/x"
        assertEquals(DiscordSettings.InquiryRoute.Bot("T", "42"), DiscordSettings(url, "T", "42").inquiryRoute)
        assertEquals(DiscordSettings.InquiryRoute.Webhook(url), DiscordSettings(url, "T", "").inquiryRoute)
        assertTrue(DiscordSettings(botToken = "T").botConfigured)
        assertFalse(DiscordSettings(botToken = "T").configured)
        assertThrows<IllegalArgumentException> { DiscordSettings(inquiryChannelId = "#문의") }
    }

    @Test
    fun `the embed names the player and mentions nobody`() {
        val payload = DiscordWebhook.payload(inquiry)
        assertEquals(0, payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size())
        val embed = payload.getAsJsonArray("embeds")[0].asJsonObject
        assertEquals("[빡켓몬 문의] 김빡주 (Park_JH)", embed.get("title").asString)
        assertEquals("@everyone 광장에 못 가요", embed.get("description").asString)
        val fields = embed.getAsJsonArray("fields").associate { it.asJsonObject.get("name").asString to it.asJsonObject.get("value").asString }
        assertEquals(mapOf("닉네임" to "김빡주", "아이디" to "Park_JH", "경로" to "위키", "UUID" to id.toString(),
            "시각" to "1970-01-01 09:00:00 (KST)"), fields)
    }

    @Test
    fun `a webhook and the bot receive the inquiry, and a refusal is an error`() {
        var received = ""
        var status = 204
        var authorization = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/webhooks/1/token") { exchange ->
            received = String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.createContext("/api/v10/channels/42/messages") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            received = String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/api/webhooks/1/token"
            DiscordWebhook.send(url, inquiry)
            val body = JsonParser.parseString(received).asJsonObject
            assertEquals("[빡켓몬 문의] 김빡주 (Park_JH)", body.getAsJsonArray("embeds")[0].asJsonObject.get("title").asString)
            status = 429
            assertThrows<IllegalStateException> { DiscordWebhook.send(url, inquiry) }
            status = 200
            DiscordWebhook.apiBase = "http://127.0.0.1:${server.address.port}/api/v10"
            DiscordWebhook.send(DiscordSettings.InquiryRoute.Bot("TOKEN", "42"), inquiry)
            assertEquals("Bot TOKEN", authorization)
            assertFalse(JsonParser.parseString(received).asJsonObject.has("username"))
        } finally {
            DiscordWebhook.apiBase = "https://discord.com/api/v10"
            server.stop(0)
        }
    }
}
