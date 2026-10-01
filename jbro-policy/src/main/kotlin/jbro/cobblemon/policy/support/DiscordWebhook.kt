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
 * Where inquiries are posted on Discord, kept in `config/jbro-policy-discord.json` apart from the main config because
 * anyone holding the webhook URL can post to that channel. Off until [webhookUrl] is set.
 */
data class DiscordSettings(val webhookUrl: String = "") {
    val configured: Boolean get() = webhookUrl.isNotBlank()

    init {
        require(webhookUrl.isBlank() || isWebhookUrl(webhookUrl)) { "Not a Discord webhook URL" }
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val WEBHOOK = Regex("https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[A-Za-z0-9_-]+")

        fun isWebhookUrl(value: String): Boolean = WEBHOOK.matches(value)

        fun parse(json: String): DiscordSettings =
            DiscordSettings(JsonParser.parseString(json).asJsonObject.get("webhookUrl")?.asString?.trim().orEmpty())

        /** Writes an empty template when the file is missing; a broken file turns Discord posting off without being touched. */
        fun load(file: Path, warn: (String, Throwable?) -> Unit): DiscordSettings {
            if (!Files.exists(file)) {
                try {
                    Files.createDirectories(file.parent)
                    Files.writeString(file, gson.toJson(DiscordSettings()))
                } catch (failure: java.io.IOException) { warn("Could not write the Discord config template to $file", failure) }
                return DiscordSettings()
            }
            return try { parse(Files.readString(file)) } catch (failure: RuntimeException) {
                warn("Invalid Discord config at $file; Discord inquiries stay off until it is fixed", failure)
                DiscordSettings()
            }
        }
    }
}

/** Posts one inquiry to a Discord webhook as an embed. Nothing in it can mention anyone. */
internal object DiscordWebhook {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private const val COLOR = 0xF2B24B

    fun send(url: String, inquiry: Inquiry) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload(inquiry).toString()))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Discord answered ${response.statusCode()}: ${response.body().take(200)}" }
    }

    internal fun payload(inquiry: Inquiry) = JsonObject().apply {
        addProperty("username", "빡켓몬 문의")
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
