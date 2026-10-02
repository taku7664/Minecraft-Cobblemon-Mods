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
import jbro.cobblemon.policy.JbroPolicy

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
 * @property verifiedRoleId the role `/verify` gives a member who linked their Minecraft account; blank turns linking off.
 * @property syncNickname whether `/verify` also sets the member's server nickname to their Minecraft nickname.
 */
data class DiscordSettings(
    val webhookUrl: String = "",
    val botToken: String = "",
    val inquiryChannelId: String = "",
    val statusChannelId: String = "",
    val newsChannelId: String = "",
    val adminChannelId: String = "",
    val adminAccess: Map<String, Set<String>> = emptyMap(),
    val verifiedRoleId: String = "",
    val syncNickname: Boolean = true,
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
        require(verifiedRoleId.isBlank() || verifiedRoleId.all(Char::isDigit)) { "The verified role ID is the role's number" }
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
                text("newsChannelId"), text("adminChannelId"), access, text("verifiedRoleId"),
                root.get("syncNickname")?.asBoolean ?: true)
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

    /** Where the bot put a card: the channel or thread, and the message. */
    data class Card(val channelId: String, val messageId: String)

    /**
     * Posts the card. The bot opens a private thread for it in the inquiry channel and adds [memberId], the player's
     * linked Discord account, so each player sees their own inquiries alone; without the thread permission it posts
     * in the channel. The bot answers with where the card is, a webhook with nothing.
     */
    fun send(route: DiscordSettings.InquiryRoute, inquiry: Inquiry, memberId: String? = null): Card? = when (route) {
        is DiscordSettings.InquiryRoute.Webhook -> { send(route.url, inquiry); null }
        is DiscordSettings.InquiryRoute.Bot -> {
            val thread = openThread(route, inquiry)
            val channel = thread ?: route.channelId
            val message = post(bot(route, "/channels/$channel/messages"), payload(inquiry, webhook = false))
            if (thread != null && memberId != null) addMember(route, thread, memberId)
            message?.let { Card(channel, it) }
        }
    }

    fun send(url: String, inquiry: Inquiry): String? = post(HttpRequest.newBuilder(URI.create(url)), payload(inquiry, webhook = true))

    private fun bot(route: DiscordSettings.InquiryRoute.Bot, path: String) =
        HttpRequest.newBuilder(URI.create(apiBase + path)).header("Authorization", "Bot ${route.token}")

    /** A private thread only its members and the operators see, or null when Discord refused one. */
    private fun openThread(route: DiscordSettings.InquiryRoute.Bot, inquiry: Inquiry): String? {
        val body = JsonObject().apply {
            addProperty("name", threadName(inquiry))
            addProperty("type", PRIVATE_THREAD)
            // Members cannot invite others; the operators still can.
            addProperty("invitable", false)
            addProperty("auto_archive_duration", WEEK_MINUTES)
        }
        val response = request(bot(route, "/channels/${route.channelId}/threads"), "POST", body)
        if (response.statusCode() !in 200..299) {
            JbroPolicy.LOGGER.warn("Discord refused a private inquiry thread ({}), so the card goes in the channel: {}",
                response.statusCode(), response.body().take(200))
            return null
        }
        return runCatching { JsonParser.parseString(response.body()).asJsonObject.get("id")?.asString }.getOrNull()
    }

    private fun addMember(route: DiscordSettings.InquiryRoute.Bot, thread: String, memberId: String) {
        val response = request(bot(route, "/channels/$thread/thread-members/$memberId"), "PUT", null)
        if (response.statusCode() !in 200..299) {
            JbroPolicy.LOGGER.warn("Could not add {} to inquiry thread {} ({}): {}", memberId, thread, response.statusCode(), response.body().take(200))
        }
    }

    internal fun threadName(inquiry: Inquiry) = "문의 ${inquiry.id} · ${inquiry.nickname}".take(100)

    private const val PRIVATE_THREAD = 12
    private const val WEEK_MINUTES = 10080

    private fun request(request: HttpRequest.Builder, method: String, body: JsonObject?): HttpResponse<String> =
        client.send(request.timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", DiscordBot.USER_AGENT)
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
            .build(), HttpResponse.BodyHandlers.ofString())

    private fun post(request: HttpRequest.Builder, body: JsonObject): String? {
        val response = request(request, "POST", body)
        check(response.statusCode() in 200..299) { "Discord answered ${response.statusCode()}: ${response.body().take(200)}" }
        return runCatching { JsonParser.parseString(response.body()).asJsonObject.get("id")?.asString }.getOrNull()
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
                add("footer", JsonObject().apply { addProperty("text", "문의 번호 ${inquiry.id}") })
            })
        })
    }

    private fun field(name: String, value: String, inline: Boolean = true) = JsonObject().apply {
        addProperty("name", name)
        addProperty("value", value.ifBlank { "-" }.take(1024))
        addProperty("inline", inline)
    }
}
