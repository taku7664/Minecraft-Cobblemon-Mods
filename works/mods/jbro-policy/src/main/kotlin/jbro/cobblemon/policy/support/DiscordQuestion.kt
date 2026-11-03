package jbro.cobblemon.policy.support

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import jbro.cobblemon.policy.JbroPolicy
import net.minecraft.server.MinecraftServer

/** A slow command whose work never runs on Minecraft's thread or the Discord REST worker. */
internal interface DiscordAsyncCommand : DiscordCommand {
    fun replyAsync(options: Map<String, String>, caller: DiscordCaller): CompletableFuture<JsonObject>
    override fun reply(server: MinecraftServer, options: Map<String, String>): JsonObject = error("/$name is asynchronous")
}

/** One reservation per Discord user, shared across channels; unsuccessful questions release it. */
internal class QuestionCooldown(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val at: Long, var pending: Boolean = true)
    private val entries = HashMap<String, Entry>()

    /** Zero admits the question; otherwise the number of seconds still to wait. */
    @Synchronized fun acquire(user: String): Long {
        val now = clock()
        entries.entries.removeIf { !it.value.pending && now - it.value.at >= INTERVAL }
        val previous = entries[user]
        if (previous != null) return if (previous.pending) maxOf(1, (INTERVAL - (now - previous.at) + 999) / 1000)
            else (INTERVAL - (now - previous.at) + 999) / 1000
        entries[user] = Entry(now)
        return 0
    }

    @Synchronized fun finish(user: String, success: Boolean) {
        if (success) entries[user]?.pending = false else entries.remove(user)
    }

    companion object { const val INTERVAL = 300_000L }
}

/**
 * Each Discord user's tally of pointless and proper questions, as Pichu judged them, kept across restarts. Pichu's tone
 * follows the share of pointless ones, so proper questions cool it down again.
 */
internal class QuestionStats(private val file: Path? = null) {
    data class Tally(val pointless: Int = 0, val proper: Int = 0)

    private val tallies = HashMap<String, Tally>()

    init {
        if (file != null && Files.isRegularFile(file)) runCatching {
            JsonParser.parseString(Files.readString(file)).asJsonObject.entrySet().forEach { (user, value) ->
                val tally = value.asJsonObject
                tallies[user] = Tally(tally.get("pointless").asInt, tally.get("proper").asInt)
            }
        }.onFailure { JbroPolicy.LOGGER.warn("Could not read Pichu's question tally", it) }
    }

    @Synchronized fun of(user: String) = tallies[user] ?: Tally()

    @Synchronized fun record(user: String, pointless: Boolean) {
        val tally = of(user)
        tallies[user] = if (pointless) tally.copy(pointless = tally.pointless + 1) else tally.copy(proper = tally.proper + 1)
        val target = file ?: return
        runCatching {
            Files.createDirectories(target.toAbsolutePath().parent)
            Files.writeString(target, JsonObject().apply {
                tallies.forEach { (id, value) ->
                    add(id, JsonObject().apply {
                        addProperty("pointless", value.pointless)
                        addProperty("proper", value.proper)
                    })
                }
            }.toString())
        }.onFailure { JbroPolicy.LOGGER.warn("Could not save Pichu's question tally", it) }
    }
}

/** [ask] takes the question and its asker's tally; its answer opens with Pichu's verdict on the question. */
internal class DiscordQuestion(
    private val ask: (String, QuestionStats.Tally) -> CompletableFuture<String>,
    private val cooldown: QuestionCooldown = QuestionCooldown(),
    private val stats: QuestionStats = QuestionStats(),
) : DiscordAsyncCommand {
    override val name = "피츄"
    override val description get() = DiscordText.say("questionDescription")
    override val anyChannel = true
    override val options get() = JsonArray().apply {
        add(DiscordCommands.stringOption("질문", DiscordText.say("questionOption")).apply {
            addProperty("min_length", 1)
            addProperty("max_length", MAX_QUESTION)
        })
    }

    override fun replyAsync(options: Map<String, String>, caller: DiscordCaller): CompletableFuture<JsonObject> {
        val question = options["질문"].orEmpty().trim()
        if (caller.userId.isBlank() || question.isBlank() || question.length > MAX_QUESTION)
            return CompletableFuture.completedFuture(DiscordRest.message(DiscordText.say("questionInvalid")))
        val seconds = cooldown.acquire(caller.userId)
        if (seconds > 0) return CompletableFuture.completedFuture(DiscordRest.message(
            DiscordText.say("questionCooldown", "seconds" to seconds)))
        val future = try { ask(question, stats.of(caller.userId)) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        return future.handle { raw, failure ->
            val verdict = VERDICT.find(raw.orEmpty())
            val answer = (if (verdict != null) raw.orEmpty().removeRange(verdict.range) else raw.orEmpty()).trim()
            val success = failure == null && answer.isNotEmpty()
            cooldown.finish(caller.userId, success)
            if (success && verdict != null) stats.record(caller.userId, verdict.groupValues[1] == PichuQuestionAI.POINTLESS)
            if (success) DiscordRest.message(DiscordText.say("questionAnswer",
                "question" to question.take(180).replace("\n", " "), "answer" to answer.take(1700)))
            else DiscordRest.message(DiscordText.say("questionFailed"))
        }
    }

    companion object {
        const val MAX_QUESTION = 500
        /** The verdict Pichu is asked to open with; the model sometimes puts a stray `{}` before its answer. */
        private val VERDICT = Regex("^\\s*(?:\\{\\}\\s*)?\\[(${PichuQuestionAI.POINTLESS}|${PichuQuestionAI.PROPER})\\]\\s*")
    }
}
