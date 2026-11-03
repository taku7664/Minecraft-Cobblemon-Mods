package jbro.cobblemon.policy.support

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What the reviewer made of one inquiry. */
data class InquiryVerdict(
    val verdict: Verdict,
    val evidence: List<String>,
    val operatorDetail: String,
    val suggestedActions: List<String>,
    val playerSummary: String,
    /** Commands to put to an operator; none runs without their approval or their own copy. */
    val suggestedCommands: List<SuggestedCommand> = emptyList(),
    val category: Category = Category.OTHER,
    /** Whether the reviewer thinks Pichu can settle it alone; the server still decides ([InquiryAutoResolve]). */
    val resolution: Resolution = Resolution.OPERATOR,
    /** Legacy model field. Any nonempty value forces operator review; these commands never run. */
    val autoCommands: List<String> = emptyList(),
) {
    data class SuggestedCommand(val command: String, val why: String)

    enum class Resolution { AUTO, OPERATOR }

    /** What the inquiry is about, as the reviewer sorted it; [key] is the schema's value. */
    enum class Category(val key: String) {
        REWARD("reward"),
        SHOP("shop"),
        BATTLE("battle"),
        LOSS("loss"),
        LEGEND("legend"),
        CONNECTION("connection"),
        REPORT("report"),
        QUESTION("question"),
        SUGGESTION("suggestion"),
        OTHER("other");

        val label: String get() = PichuRules.current().text("texts.category.$key")
    }

    enum class Verdict {
        MATCH, MISMATCH, UNKNOWN;

        val label: String get() = PichuRules.current().text("texts.verdict.${name.lowercase()}")
    }
}

/**
 * Asks the Antigravity CLI (`agy`) to check an inquiry against the log: one headless, read-only turn in plan mode,
 * fed on stdin, so the prompt is not bound by the command line's length and the model has nothing to run. Each
 * turn is its own conversation, which [forget] deletes once the inquiry is closed.
 */
internal class InquiryReviewer(private val settings: InquiryReviewSettings, private val workDir: Path, private val schemaFile: Path) {
    data class Answer(val conversationId: String?, val verdict: InquiryVerdict)

    @Volatile private var running: Process? = null

    fun review(prompt: String): Answer {
        InquiryAiProfile.ensure()
        Files.createDirectories(workDir)
        // Rewritten when an update changed the schema, not only when missing.
        if (!Files.exists(schemaFile) || Files.readString(schemaFile) != SCHEMA) Files.writeString(schemaFile, SCHEMA)
        val command = buildList {
            add(settings.executable())
            addAll(listOf("--input-format", "stream-json", "--output-format", "stream-json", "--json-schema", schemaFile.toString()))
            addAll(listOf("--agent", InquiryAiProfile.NAME, "--mode", "plan", "--disable-slash-commands"))
            if (settings.model.isNotBlank()) addAll(listOf("--model", settings.model))
            add("-p=")
        }
        val process = ProcessBuilder(command).directory(workDir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        running = process
        try {
            val output = StringBuilder()
            val reader = Thread({ output.append(String(process.inputStream.readNBytes(512 * 1024), StandardCharsets.UTF_8)) }, "jbro-policy-review-output")
                .apply { isDaemon = true; start() }
            process.outputStream.use { it.write((input(prompt) + "\n").toByteArray(StandardCharsets.UTF_8)) }
            if (!process.waitFor(settings.timeoutSeconds, TimeUnit.SECONDS)) error("agy took longer than ${settings.timeoutSeconds} seconds")
            reader.join(5_000)
            check(process.exitValue() == 0) { "agy process failed" }
            check(output.length < 512 * 1024) { "agy output exceeded limit" }
            return parseOutput(output.toString())
        } finally {
            process.destroyForcibly()
            running = null
        }
    }

    /** Stops a review in progress, for the server stopping. */
    fun cancel() {
        running?.destroyForcibly()
    }

    /** Deletes the conversation agy kept for [conversationId]: its database and its working notes. */
    fun forget(conversationId: String) {
        require(UUID_PATTERN.matches(conversationId)) { "Not a conversation ID: $conversationId" }
        val home = settings.home()
        val conversations = home.resolve("conversations")
        if (Files.isDirectory(conversations)) Files.list(conversations).use { files ->
            files.filter { it.fileName.toString().startsWith("$conversationId.db") }.forEach(Files::deleteIfExists)
        }
        val brain = home.resolve("brain").resolve(conversationId)
        if (Files.isDirectory(brain)) Files.walk(brain).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        /** The old fixed ending, still taken off a summary that carries it. */
        const val CLOSING = "운영자에게 세부 사항을 전달했어요."
        val HANDLED: String get() = PichuRules.current().text("texts.handled")
        val HANDED_OVER: String get() = PichuRules.current().text("texts.handedOver")
        private const val MAX_COMMAND_LENGTH = 256

        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Kept ASCII: agy reads the file in the system code page. */
        val SCHEMA = """
            {"type":"object","properties":{
              "category":{"type":"string","enum":["reward","shop","battle","loss","legend","connection","report","question","suggestion","other"]},
              "verdict":{"type":"string","enum":["match","mismatch","unknown"]},
              "evidence":{"type":"array","items":{"type":"string"}},
              "operatorDetail":{"type":"string"},
              "suggestedActions":{"type":"array","items":{"type":"string"}},
              "playerSummary":{"type":"string"},
              "suggestedCommands":{"type":"array","items":{"type":"object","properties":{
                "command":{"type":"string"},"why":{"type":"string"}},"required":["command","why"]}},
              "resolution":{"type":"string","enum":["auto","operator"]},
              "autoCommands":{"type":"array","items":{"type":"string"}}},
             "required":["category","verdict","evidence","operatorDetail","suggestedActions","playerSummary","suggestedCommands","resolution","autoCommands"]}
        """.trimIndent()

        fun input(prompt: String): String = JsonObject().apply {
            addProperty("event", "user")
            add("message", JsonObject().apply { addProperty("content", prompt) })
        }.toString()

        /** Text a player wrote, or a log line, kept from closing the tag it sits in. */
        private fun inert(text: String) = text.replace("</", "<\\/")

        fun prompt(inquiry: Inquiry, window: InquiryLogWindow.Window, minutesBefore: Long, minutesAfter: Long, playerData: String? = null, reference: String = "", livePlayer: String? = null, battles: String? = null): String {
            val rules = PichuRules.current()
            val guidance = rules.lines("inquiry.prompt",
                "minutesBefore" to minutesBefore, "minutesAfter" to minutesAfter, "accountName" to inquiry.accountName,
                "logNote" to if (window.truncated) " " + rules.text("inquiry.logTruncated") else "",
                "playerDataNote" to rules.text(if (playerData == null) "inquiry.playerDataMissing" else "inquiry.playerDataPresent"),
                // Indented like the guidance's own lines.
                "commands" to rules.list("inquiry.commands").joinToString("\n  ") { "- $it" })
            return guidance + "\n" + """
            <inquiry>
            플레이어 아이디: ${inquiry.accountName}
            닉네임: ${inert(inquiry.nickname)}
            UUID: ${inquiry.playerId}
            보낸 곳: ${inquiry.via.label}
            문의 시각: ${inquiry.time} (KST)
            내용: ${inert(inquiry.reason)}
            </inquiry>

            <logs>
        """.trimIndent() + "\n" + inert(window.text).ifEmpty { rules.text("inquiry.noLogs") } + "\n</logs>\n" +
            (if (playerData == null) "" else "\n<player_data>\n${inert(playerData)}\n</player_data>\n") +
            (if (battles == null) "" else "\n<battles>\n${inert(battles)}\n</battles>\n") +
            (if (reference.isBlank()) "" else "\n<reference>\n${inert(reference)}\n</reference>\n") +
            (if (livePlayer == null) "" else "\n<live_player>\n${inert(livePlayer)}\n</live_player>\n")
        }

        /** The verdict in agy's stream-json output: the `result` event's response, whose last complete answer wins. */
        fun parseOutput(output: String): Answer {
            var conversationId: String? = null
            var result: JsonObject? = null
            for (line in output.lineSequence().filter { it.startsWith("{") }) {
                val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                check(event.getAsJsonObject("step_update")?.let {
                    it.get("step_type")?.asString == "tool" && it.get("tool_name")?.asString != "finish"
                } != true) { "Inquiry review attempted an unexpected tool" }
                when (event.get("event")?.asString) {
                    "init" -> conversationId = event.get("conversation_id")?.asString
                    "result" -> result = event.getAsJsonObject("result")
                }
            }
            val final = result ?: error("agy gave no result")
            conversationId = final.get("conversation_id")?.asString ?: conversationId
            val status = final.get("status")?.asString
            check(status == "SUCCESS") { "agy answered $status: ${final.get("error")?.asString.orEmpty().take(200)}" }
            val response = final.get("response")?.asString.orEmpty()
            val answer = objects(response).lastOrNull { it.has("verdict") && it.has("playerSummary") }
                ?: error("agy's answer held no verdict: ${response.take(200)}")
            return Answer(conversationId, verdict(answer))
        }

        fun verdict(answer: JsonObject): InquiryVerdict {
            val kind = when (text(answer.get("verdict")).trim().lowercase()) {
                "match" -> InquiryVerdict.Verdict.MATCH
                "mismatch" -> InquiryVerdict.Verdict.MISMATCH
                else -> InquiryVerdict.Verdict.UNKNOWN
            }
            return InquiryVerdict(kind, list(answer.get("evidence")), text(answer.get("operatorDetail")).trim(),
                list(answer.get("suggestedActions")), playerSummary(text(answer.get("playerSummary"))), commands(answer.get("suggestedCommands")),
                text(answer.get("category")).trim().lowercase().let { key ->
                    InquiryVerdict.Category.entries.firstOrNull { it.key == key } ?: InquiryVerdict.Category.OTHER
                },
                if (text(answer.get("resolution")).trim().lowercase() == "auto") InquiryVerdict.Resolution.AUTO else InquiryVerdict.Resolution.OPERATOR,
                list(answer.get("autoCommands")).map { it.replace(Regex("\\s+"), " ").replace("`", "").trim().take(MAX_COMMAND_LENGTH) }.take(5))
        }

        /** Each command on one line starting with "/", kept from breaking out of the code block it is shown in. */
        fun commands(element: JsonElement?): List<InquiryVerdict.SuggestedCommand> {
            val items = element as? JsonArray ?: return emptyList()
            return items.mapNotNull { item ->
                val entry = item as? JsonObject
                val raw = if (entry != null) text(entry.get("command")) else text(item)
                val command = raw.replace(Regex("\\s+"), " ").replace("`", "").trim().take(MAX_COMMAND_LENGTH)
                if (command.length < 2) return@mapNotNull null
                val why = entry?.let { text(it.get("why")) }.orEmpty().replace(Regex("\\s+"), " ").trim().take(200)
                InquiryVerdict.SuggestedCommand(if (command.startsWith("/")) command else "/$command", why)
            }.take(10)
        }

        /** At most 600 characters, without an ending of its own: [reply] adds the one that fits the outcome. */
        fun playerSummary(raw: String): String {
            var body = raw.trim()
            for (ending in listOf(CLOSING, HANDLED, HANDED_OVER)) body = body.removeSuffix(ending).trim()
            return body.take(600)
        }

        /** The player's answer: the summary, then whether Pichu settled it or an operator will. */
        fun reply(summary: String, handedOver: Boolean, ranCommands: Boolean): String {
            val ending = when {
                handedOver -> HANDED_OVER
                ranCommands -> HANDLED
                else -> null
            }
            return listOfNotNull(summary.ifBlank { null }, ending).joinToString(" ").ifBlank { HANDLED }
        }

        // The model sometimes sends a field as a list of strings where one string was asked for, or the reverse.
        private fun text(element: JsonElement?): String = when {
            element == null || element.isJsonNull -> ""
            element is JsonArray -> element.joinToString("\n") { text(it) }
            element.isJsonPrimitive -> element.asString
            else -> element.toString()
        }

        private fun list(element: JsonElement?): List<String> = when {
            element == null || element.isJsonNull -> emptyList()
            element is JsonArray -> element.map { text(it).trim() }.filter { it.isNotEmpty() }
            else -> listOf(text(element).trim()).filter { it.isNotEmpty() }
        }

        /** Every JSON object written out in [text], in order, past any stray brace in the prose around them. */
        fun objects(text: String): List<JsonObject> {
            val found = mutableListOf<JsonObject>()
            var index = 0
            while (index < text.length) {
                val end = if (text[index] == '{') closing(text, index) else -1
                val parsed = if (end < 0) null
                    else runCatching { JsonParser.parseString(text.substring(index, end + 1)).asJsonObject }.getOrNull()
                if (parsed != null) {
                    found += parsed
                    index = end + 1
                } else index++
            }
            return found
        }

        /** Where the brace at [start] closes, minding strings, or -1 when it never does. */
        private fun closing(text: String, start: Int): Int {
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until text.length) {
                val char = text[index]
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> quoted = false
                    }
                    continue
                }
                when (char) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return index
                }
            }
            return -1
        }
    }
}
