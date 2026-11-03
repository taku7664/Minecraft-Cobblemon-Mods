package jbro.cobblemon.policy.support

import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.UUID
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

/**
 * Dedicated chat profile: no file, shell, MCP, browser or administration tools. What Pichu knows about the server is
 * the sections of the wiki under [wiki] that bear on the question, handed over in the prompt.
 */
internal class PichuQuestionAI(private val settings: InquiryReviewSettings, private val root: Path, private val wiki: Path) {
    private val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "jbro-policy-pichu-question").apply { isDaemon = true } }
    private val capacity = Semaphore(2)
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun ask(question: String, tally: QuestionStats.Tally): CompletableFuture<String> {
        if (!settings.enabled || !capacity.tryAcquire()) return CompletableFuture.failedFuture(IllegalStateException("AI unavailable"))
        val result = CompletableFuture<String>()
        try {
            workers.execute {
                try { result.complete(answer(question, tally)) } catch (failure: Exception) { result.completeExceptionally(failure) }
                finally { capacity.release() }
            }
        } catch (failure: RejectedExecutionException) {
            capacity.release()
            result.completeExceptionally(failure)
        }
        return result
    }

    fun close() {
        workers.shutdownNow()
        processes.forEach { it.destroyForcibly() }
    }

    private fun answer(question: String, tally: QuestionStats.Tally): String {
        val profile = Path.of(System.getProperty("user.home"), ".gemini", "config", "agents", "jbro-pichu-chat", "agent.md")
        val expected = requireNotNull(javaClass.getResourceAsStream("/pichu-chat-agent.md")).use {
            String(it.readAllBytes(), StandardCharsets.UTF_8).trim()
        }
        synchronized(PROFILE_LOCK) {
            Files.createDirectories(profile.parent)
            if (!Files.exists(profile)) Files.writeString(profile, expected)
            check(Files.readString(profile).trim() == expected) { "Pichu chat profile differs from the bundled restricted profile" }
        }
        Files.createDirectories(root)
        val workspace = Files.createTempDirectory(root, "question-")
        val args = buildList {
            add(settings.executable())
            addAll(listOf("--agent", "jbro-pichu-chat", "--mode", "plan", "--disable-slash-commands",
                "--input-format", "stream-json", "--output-format", "stream-json"))
            if (settings.model.isNotBlank()) addAll(listOf("--model", settings.model))
            add("-p=")
        }
        var process: Process? = null
        var output = ""
        try {
            val started = ProcessBuilder(args).directory(workspace.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process = started
            processes.add(started)
            val captured = CompletableFuture<String>()
            val reader = Thread({
                try {
                    val bytes = started.inputStream.readNBytes(OUTPUT_LIMIT + 1)
                    check(bytes.size <= OUTPUT_LIMIT) { "AI output too large" }
                    captured.complete(String(bytes, StandardCharsets.UTF_8))
                } catch (failure: Exception) {
                    captured.completeExceptionally(failure)
                    started.destroyForcibly()
                }
            }, "jbro-policy-pichu-output").apply { isDaemon = true; start() }
            val reference = runCatching { PichuWiki.reference(wiki, question) }.getOrDefault("")
            started.outputStream.use { it.write((InquiryReviewer.input(prompt(question, reference, tally)) + "\n").toByteArray(StandardCharsets.UTF_8)) }
            check(started.waitFor(settings.timeoutSeconds.coerceIn(1, 120), TimeUnit.SECONDS)) { "AI timed out" }
            output = captured.get(5, TimeUnit.SECONDS)
            reader.join(1_000)
            check(started.exitValue() == 0) { "AI process failed" }
            return parseOutput(output)
        } finally {
            process?.let { it.destroyForcibly(); processes.remove(it) }
            // Each question is independent; remove only its own saved CLI conversation.
            conversationId(output)?.let { id ->
                runCatching { InquiryReviewer(settings, workspace, workspace.resolve("unused-schema.json")).forget(id) }
            }
            runCatching { Files.deleteIfExists(workspace) }
        }
    }

    companion object {
        private val PROFILE_LOCK = Any()
        private const val OUTPUT_LIMIT = 128 * 1024
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        const val POINTLESS = "쓸데없음"
        const val PROPER = "제대로"

        /**
         * How sharp Pichu is with a tally, by the share of pointless questions alone: the highest tone in the rules whose
         * share it reaches.
         */
        internal fun tier(tally: QuestionStats.Tally): Int {
            val total = tally.pointless + tally.proper
            val share = if (total == 0) 0.0 else tally.pointless.toDouble() / total
            return PichuRules.current().tones("question.tones").indexOfLast { share >= it.first }.coerceAtLeast(0)
        }

        private fun toneOf(tier: Int) = PichuRules.current().tones("question.tones").getOrNull(tier)?.second.orEmpty()

        /** Only the Discord question command comes here. Pichu judges the question itself, so its own verdict picks the tone. */
        internal fun tone(tally: QuestionStats.Tally) = PichuRules.current().lines("question.tone",
            "proper" to PROPER, "pointless" to POINTLESS, "pointlessCount" to tally.pointless, "properCount" to tally.proper,
            "properTone" to toneOf(tier(tally.copy(proper = tally.proper + 1))),
            "pointlessTone" to toneOf(tier(tally.copy(pointless = tally.pointless + 1)))) + "\n"

        fun prompt(question: String, wiki: String = "", tally: QuestionStats.Tally = QuestionStats.Tally()) = buildString {
            val rules = PichuRules.current()
            append(rules.lines("question.intro")).append('\n')
            append(tone(tally))
            if (wiki.isNotBlank()) {
                append(rules.lines("question.wikiGuide")).append('\n')
                append("<wiki>\n").append(wiki.replace("</", "<\\/")).append("\n</wiki>\n")
            } else {
                append(rules.lines("question.noWiki")).append('\n')
            }
            append(rules.lines("question.closing")).append('\n')
            append(com.google.gson.JsonObject().apply { addProperty("question", question) })
        }

        fun parseOutput(output: String): String {
            val events = output.lineSequence().mapNotNull { line -> runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() }.toList()
            check(events.none { event -> event.getAsJsonObject("step_update")?.let {
                it.get("step_type")?.asString == "tool" && it.get("tool_name")?.asString != "finish"
            } == true }) { "Chat AI attempted an unexpected tool" }
            val result = events.lastOrNull { it.get("event")?.asString == "result" }?.getAsJsonObject("result")
                ?: error("AI returned no answer")
            check(result.get("status")?.asString == "SUCCESS") { "AI failed" }
            return result.get("response")?.asString.orEmpty().trim().take(1700).also { check(it.isNotBlank()) { "AI returned an empty answer" } }
        }

        private fun conversationId(output: String): String? = output.lineSequence().mapNotNull { line ->
            runCatching { JsonParser.parseString(line).asJsonObject.get("conversation_id")?.asString }.getOrNull()
        }.firstOrNull { UUID_PATTERN.matches(it) }

        fun register(settings: InquiryReviewSettings, gameDir: Path) {
            val ai = PichuQuestionAI(settings, gameDir.resolve("jbro-policy").resolve("questions"), PichuWiki.directory(gameDir))
            DiscordCommands.add(DiscordQuestion(ai::ask, stats = QuestionStats(gameDir.resolve("jbro-policy").resolve("pichu-question-stats.json"))))
            ServerLifecycleEvents.SERVER_STOPPING.register { ai.close() }
        }
    }
}
