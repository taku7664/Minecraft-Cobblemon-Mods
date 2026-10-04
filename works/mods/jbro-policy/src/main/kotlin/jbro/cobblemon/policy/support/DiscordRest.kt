package jbro.cobblemon.policy.support

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The bot's calls to Discord's REST API, authorised by its token. */
internal class DiscordRest(private val token: String) {
    data class Response(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    fun request(method: String, path: String, body: JsonElement? = null, authorized: Boolean = true): Response {
        val builder = HttpRequest.newBuilder(URI.create(DiscordWebhook.apiBase + path))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", DiscordBot.USER_AGENT)
        if (authorized) builder.header("Authorization", "Bot $token")
        if (body != null) builder.header("Content-Type", "application/json; charset=utf-8")
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 429) {
            // Rate limited: wait as told, up to five seconds, and try once more.
            val wait = Regex("\"retry_after\"\\s*:\\s*([0-9.]+)").find(response.body())?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            Thread.sleep((wait.coerceAtMost(5.0) * 1000).toLong() + 100)
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        return Response(response.statusCode(), response.body())
    }

    /** Posts [text] to [channelId]; nothing in it can mention anyone. */
    fun post(channelId: String, text: String): Response =
        request("POST", "/channels/$channelId/messages", message(text))

    companion object {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        fun message(text: String? = null, embed: JsonObject? = null) = JsonObject().apply {
            if (text != null) addProperty("content", text.take(2000))
            if (embed != null) add("embeds", JsonArray().apply { add(embed) })
            add("allowed_mentions", JsonObject().apply { add("parse", JsonArray()) })
        }
    }
}
