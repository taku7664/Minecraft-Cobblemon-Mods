package jbro.cobblemon.policy.support

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The server's Discord, kept in `config/jbro-policy-discord.json` apart from the main config because the bot token and
 * the webhook URL each let their holder post to the channel.
 *
 * @property botToken the bot that stays online while the server runs; with [inquiryChannelId] it also posts inquiries.
 * @property inquiryChannelId the channel the bot posts inquiries to.
 * @property statusChannelId the channel where the bot keeps one message saying whether the server is open.
 * @property newsChannelId the channel the bot posts server news to: Champions, records, Legend and shiny catches,
 *   notices.
 * @property webhookUrl posts inquiries without a bot, used when the bot has no channel.
 * @property adminChannelId the only channel where the bot takes operator commands; blank turns them off.
 * @property adminAccess who may run which operator command: a Discord user or role ID to command names, `*` for all.
 */
data class DiscordSettings(
    val webhookUrl: String = "",
    val botToken: String = "",
    val inquiryChannelId: String = "",
    val statusChannelId: String = "",
    val newsChannelId: String = "",
    val adminChannelId: String = "",
    val adminAccess: Map<String, Set<String>> = emptyMap(),
) {
    val botConfigured: Boolean get() = botToken.isNotBlank()

    /** Where inquiries go on Discord: the bot's channel when it has one, else the webhook. */
    val inquiryRoute: InquiryRoute? get() = when {
        botConfigured && inquiryChannelId.isNotBlank() -> InquiryRoute.Bot(botToken, inquiryChannelId)
        webhookUrl.isNotBlank() -> InquiryRoute.Webhook(webhookUrl)
        else -> null
    }

    val configured: Boolean get() = inquiryRoute != null

    sealed interface InquiryRoute {
        data class Bot(val token: String, val channelId: String) : InquiryRoute
        data class Webhook(val url: String) : InquiryRoute
    }

    init {
        require(webhookUrl.isBlank() || isWebhookUrl(webhookUrl)) { "Not a Discord webhook URL" }
        require(inquiryChannelId.isBlank() || inquiryChannelId.all(Char::isDigit)) { "The inquiry channel ID is the channel's number" }
        require(statusChannelId.isBlank() || statusChannelId.all(Char::isDigit)) { "The status channel ID is the channel's number" }
        require(newsChannelId.isBlank() || newsChannelId.all(Char::isDigit)) { "The news channel ID is the channel's number" }
        require(adminChannelId.isBlank() || adminChannelId.all(Char::isDigit)) { "The admin channel ID is the channel's number" }
        require(adminAccess.keys.all { it.isNotEmpty() && it.all(Char::isDigit) }) { "Admin access is keyed by Discord user or role IDs" }
        require(botToken.none(Char::isWhitespace)) { "The bot token cannot contain spaces" }
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val WEBHOOK = Regex("https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[A-Za-z0-9_-]+")

        fun isWebhookUrl(value: String): Boolean = WEBHOOK.matches(value)

        fun parse(json: String): DiscordSettings {
            val root = JsonParser.parseString(json).asJsonObject
            fun text(key: String) = root.get(key)?.asString?.trim().orEmpty()
            val access = root.getAsJsonObject("adminAccess")?.entrySet()?.associate { (id, commands) ->
                id.trim() to commands.asJsonArray.map { it.asString.trim() }.toSet()
            }.orEmpty()
            return DiscordSettings(text("webhookUrl"), text("botToken"), text("inquiryChannelId"), text("statusChannelId"),
                text("newsChannelId"), text("adminChannelId"), access)
        }

        /** Writes an empty template when the file is missing; a broken file turns Discord off without being touched. */
        fun load(file: Path, warn: (String, Throwable?) -> Unit): DiscordSettings {
            if (!Files.exists(file)) {
                try {
                    Files.createDirectories(file.parent)
                    Files.writeString(file, gson.toJson(DiscordSettings()))
                } catch (failure: java.io.IOException) { warn("Could not write the Discord config template to $file", failure) }
                return DiscordSettings()
            }
            return try { parse(Files.readString(file)) } catch (failure: RuntimeException) {
                warn("Invalid Discord config at $file; Discord stays off until it is fixed", failure)
                DiscordSettings()
            }
        }
    }
}

/** Posts one inquiry to Discord as an embed, by webhook or by the bot. Nothing in it can mention anyone. */
internal object DiscordWebhook {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private const val COLOR = 0xF2B24B

    /** Discord's REST API; tests point it at a local server. */
    internal var apiBase = "https://discord.com/api/v10"

    fun send(route: DiscordSettings.InquiryRoute, inquiry: Inquiry) = when (route) {
        is DiscordSettings.InquiryRoute.Webhook -> send(route.url, inquiry)
        is DiscordSettings.InquiryRoute.Bot -> post(HttpRequest.newBuilder(URI.create("$apiBase/channels/${route.channelId}/messages"))
            .header("Authorization", "Bot ${route.token}"), payload(inquiry, webhook = false))
    }

    fun send(url: String, inquiry: Inquiry) = post(HttpRequest.newBuilder(URI.create(url)), payload(inquiry, webhook = true))

    private fun post(request: HttpRequest.Builder, body: JsonObject) {
        val response = client.send(request.timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", DiscordBot.USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Discord answered ${response.statusCode()}: ${response.body().take(200)}" }
    }

    internal fun payload(inquiry: Inquiry, webhook: Boolean = true) = JsonObject().apply {
        // A webhook takes its post's name here; the bot posts under its own.
        if (webhook) addProperty("username", "빡켓몬 문의")
        add("allowed_mentions", JsonObject().apply { add("parse", JsonArray()) })
        add("embeds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("title", inquiry.subject)
                addProperty("description", inquiry.reason)
                addProperty("color", COLOR)
                add("fields", JsonArray().apply {
                    add(field("닉네임", inquiry.nickname))
                    add(field("아이디", inquiry.accountName))
                    add(field("경로", inquiry.via.label))
                    add(field("UUID", inquiry.playerId.toString(), inline = false))
                    add(field("시각", "${inquiry.time} (KST)", inline = false))
                })
            })
        })
    }

    private fun field(name: String, value: String, inline: Boolean = true) = JsonObject().apply {
        addProperty("name", name)
        addProperty("value", value.ifBlank { "-" }.take(1024))
        addProperty("inline", inline)
    }
}
