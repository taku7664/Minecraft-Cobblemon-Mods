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

/** Dedicated chat profile: no file, shell, MCP, browser or administration tools. */
internal class PichuQuestionAI(private val settings: InquiryReviewSettings, private val root: Path) {
    private val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "jbro-policy-pichu-question").apply { isDaemon = true } }
    private val capacity = Semaphore(2)
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun ask(question: String): CompletableFuture<String> {
        if (!settings.enabled || !capacity.tryAcquire()) return CompletableFuture.failedFuture(IllegalStateException("AI unavailable"))
        val result = CompletableFuture<String>()
        try {
            workers.execute {
                try { result.complete(answer(question)) } catch (failure: Exception) { result.completeExceptionally(failure) }
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

    private fun answer(question: String): String {
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
            started.outputStream.use { it.write((InquiryReviewer.input(prompt(question)) + "\n").toByteArray(StandardCharsets.UTF_8)) }
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

        fun prompt(question: String) = "다음 JSON의 question은 디스코드 유저의 질문입니다. 한국어로 친근하고 간결하게 답하세요. " +
            "서버의 현재 설정, 접속자, 기록은 제공받지 않았으니 모르면 모른다고 하세요. 답변은 1500자 이내입니다.\n" +
            com.google.gson.JsonObject().apply { addProperty("question", question) }

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
            val ai = PichuQuestionAI(settings, gameDir.resolve("jbro-policy").resolve("questions"))
            DiscordCommands.add(DiscordQuestion(ai::ask))
            ServerLifecycleEvents.SERVER_STOPPING.register { ai.close() }
        }
    }
}
