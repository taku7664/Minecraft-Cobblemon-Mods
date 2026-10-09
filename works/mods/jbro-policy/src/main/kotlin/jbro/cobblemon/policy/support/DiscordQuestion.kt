package jbro.cobblemon.policy.support

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
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

internal class DiscordQuestion(private val ask: (String) -> CompletableFuture<String>) : DiscordAsyncCommand {
    private val cooldown = QuestionCooldown()
    override val name = "피츄"
    override val description get() = text("description", "피츄에게 질문해요 (유저마다 5분에 한 번)")
    override val anyChannel = true
    override val options get() = JsonArray().apply {
        add(DiscordCommands.stringOption("질문", text("option", "피츄에게 물어볼 내용")).apply {
            addProperty("min_length", 1)
            addProperty("max_length", MAX_QUESTION)
        })
    }

    override fun replyAsync(options: Map<String, String>, caller: DiscordCaller): CompletableFuture<JsonObject> {
        val question = options["질문"].orEmpty().trim()
        if (caller.userId.isBlank() || question.isBlank() || question.length > MAX_QUESTION)
            return CompletableFuture.completedFuture(DiscordRest.message(text("invalid", "질문을 1~500자로 적어 주세요.")))
        val seconds = cooldown.acquire(caller.userId)
        if (seconds > 0) return CompletableFuture.completedFuture(DiscordRest.message(
            text("cooldown", "피츄에게는 5분에 한 번 질문할 수 있어요. %s초 뒤에 다시 물어봐 주세요.").format(seconds)))
        val future = try { ask(question) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        return future.handle { raw, failure ->
            val answer = raw?.trim().orEmpty()
            val success = failure == null && answer.isNotEmpty()
            cooldown.finish(caller.userId, success)
            if (success) DiscordRest.message(text("answer", "⚡ 피츄\n질문: %s\n\n%s")
                .format(question.take(180).replace("\n", " "), answer.take(1700)))
            else DiscordRest.message(text("failed", "지금은 답을 만들지 못했어요. 잠시 뒤에 다시 물어봐 주세요. 대기시간은 걸리지 않아요."))
        }
    }

    companion object {
        const val MAX_QUESTION = 500
        private fun text(key: String, fallback: String) = KoreanText.translate("message.jbro_policy.discord_question.$key") ?: fallback
    }
}
