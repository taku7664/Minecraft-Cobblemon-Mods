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
import java.time.Instant
import jbro.cobblemon.policy.JbroPolicy

/**
 * One message in a Discord channel that says whether the server is open: posted once, then edited in place, so it
 * still says "closed" after the server and its bot are gone. The message's ID is kept in [stateFile]; when that
 * message was deleted, or the channel changed, a new one is posted.
 */
internal class DiscordStatusMessage(private val token: String, private val channelId: String, private val stateFile: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var messageId: String? = loadMessageId()

    /** Shows the server [open] with [players] on, checked at [now]. Throws when Discord refuses. */
    fun show(open: Boolean, players: Int, now: Instant = Instant.now()) {
        val body = payload(open, players, now)
        messageId?.let { id ->
            val (status, _) = request("PATCH", "/channels/$channelId/messages/$id", body)
            if (status in 200..299) return
            // The message was deleted or is no longer ours to edit: post a fresh one.
            check(status == 404 || status == 403) { "Discord answered $status while editing the status message" }
        }
        val (status, response) = request("POST", "/channels/$channelId/messages", body)
        check(status in 200..299) { "Discord answered $status while posting the status message: ${response.take(200)}" }
        messageId = JsonParser.parseString(response).asJsonObject.get("id").asString
        saveMessageId()
    }

    private fun request(method: String, path: String, body: JsonObject): Pair<Int, String> {
        val response = client.send(HttpRequest.newBuilder(URI.create(DiscordWebhook.apiBase + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bot $token")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", DiscordBot.USER_AGENT)
            .method(method, HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun loadMessageId(): String? = try {
        if (!Files.exists(stateFile)) null else JsonParser.parseString(Files.readString(stateFile)).asJsonObject
            .takeIf { it.get("channelId")?.asString == channelId }?.get("messageId")?.asString
    } catch (failure: Exception) {
        JbroPolicy.LOGGER.warn("Could not read {}; a new status message will be posted", stateFile, failure)
        null
    }

    private fun saveMessageId() {
        try {
            Files.createDirectories(stateFile.parent)
            Files.writeString(stateFile, gson.toJson(JsonObject().apply {
                addProperty("channelId", channelId)
                addProperty("messageId", messageId)
            }))
        } catch (failure: java.io.IOException) {
            JbroPolicy.LOGGER.warn("Could not save the Discord status message ID to {}", stateFile, failure)
        }
    }

    companion object {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        private const val OPEN_COLOR = 0x57F287
        private const val CLOSED_COLOR = 0xED4245

        fun payload(open: Boolean, players: Int, now: Instant) = JsonObject().apply {
            addProperty("content", "")
            add("allowed_mentions", JsonObject().apply { add("parse", JsonArray()) })
            add("embeds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("title", if (open) "🟢 서버가 열려있어요!!!" else "🔴 서버가 닫혀있어요...")
                    addProperty("color", if (open) OPEN_COLOR else CLOSED_COLOR)
                    if (open) {
                        add("fields", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("name", "접속자")
                                addProperty("value", "${players}명")
                                addProperty("inline", true)
                            })
                        })
                    }
                    // Discord shows this in each reader's own time zone; a stale time means the server went down
                    // without closing (a crash), since an open server refreshes it every few minutes.
                    add("footer", JsonObject().apply { addProperty("text", if (open) "마지막 확인" else "닫힌 시각") })
                    addProperty("timestamp", now.toString())
                })
            })
        }
    }
}
