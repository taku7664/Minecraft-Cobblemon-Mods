package jbro.cobblemon.policy.support

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.api.OperatorWhisper
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer

/**
 * How inquiries are reviewed, kept in `config/jbro-policy-inquiry-review.json`.
 *
 * @property command the Antigravity CLI, by name on the PATH or by full path.
 * @property model one of `agy models`; blank leaves it to agy.
 * @property minutesBefore how far before the inquiry the log is read.
 * @property minutesAfter how far after it.
 * @property home where agy keeps its conversations; blank is `~/.gemini/antigravity-cli`.
 */
data class InquiryReviewSettings(
    val enabled: Boolean = false,
    val command: String = "agy",
    val model: String = "gemini-3.8-flash-low",
    val minutesBefore: Long = 30,
    val minutesAfter: Long = 5,
    val maxLogCharacters: Int = 150_000,
    val timeoutSeconds: Long = 300,
    val home: String = "",
) {
    /**
     * The CLI to run. A bare name is looked up on the PATH and then where agy installs itself
     * (`%LOCALAPPDATA%\agy\bin`), since a server started from a shell without the user's PATH cannot find it.
     */
    fun executable(env: Map<String, String> = System.getenv()): String {
        if (command.contains('/') || command.contains('\\')) return command
        val names = if (command.contains('.')) listOf(command) else listOf("$command.exe", "$command.cmd", command)
        val dirs = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value.orEmpty()
            .split(java.io.File.pathSeparatorChar).filter(String::isNotBlank).map { Path.of(it.trim('"')) } +
            listOfNotNull(env["LOCALAPPDATA"]?.let { Path.of(it, command, "bin") })
        for (dir in dirs) for (name in names) {
            val candidate = runCatching { dir.resolve(name) }.getOrNull() ?: continue
            if (Files.isRegularFile(candidate)) return candidate.toString()
        }
        return command
    }

    fun home(): Path = if (home.isBlank()) Path.of(System.getProperty("user.home"), ".gemini", "antigravity-cli") else Path.of(home)

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        fun parse(json: String): InquiryReviewSettings {
            val root = JsonParser.parseString(json).asJsonObject
            val defaults = InquiryReviewSettings()
            return InquiryReviewSettings(
                root.get("enabled")?.asBoolean ?: defaults.enabled,
                root.get("command")?.asString?.trim()?.ifEmpty { null } ?: defaults.command,
                root.get("model")?.asString?.trim() ?: defaults.model,
                root.get("minutesBefore")?.asLong ?: defaults.minutesBefore,
                root.get("minutesAfter")?.asLong ?: defaults.minutesAfter,
                root.get("maxLogCharacters")?.asInt ?: defaults.maxLogCharacters,
                root.get("timeoutSeconds")?.asLong ?: defaults.timeoutSeconds,
                root.get("home")?.asString?.trim() ?: defaults.home,
            )
        }

        /** Writes the defaults, switched off, when the file is missing; a broken file turns reviews off. */
        fun load(file: Path, warn: (String, Throwable?) -> Unit): InquiryReviewSettings {
            if (!Files.exists(file)) {
                try {
                    Files.createDirectories(file.parent)
                    Files.writeString(file, gson.toJson(InquiryReviewSettings()))
                } catch (failure: java.io.IOException) { warn("Could not write the inquiry review config template to $file", failure) }
                return InquiryReviewSettings()
            }
            return try { parse(Files.readString(file)) } catch (failure: RuntimeException) {
                warn("Invalid inquiry review config at $file; reviews stay off until it is fixed", failure)
                InquiryReviewSettings()
            }
        }
    }
}

/**
 * Reviews each inquiry against the server log once its card is up, with the Antigravity CLI. The player gets a short
 * summary as an operator whisper and as a reply under their card; the operators get the details, the evidence and
 * suggested actions in the admin channel, with a "처리 완료" button that deletes the review's conversation and a
 * "조치·명령어 보기" button that shows the presser alone the suggested commands to copy. Nothing the review suggests is
 * ever carried out by itself.
 *
 * Needs the bot, the inquiry channel and the admin channel. Open inquiries live in `jbro-policy/inquiries/`, so a
 * review cut short by a restart runs again and the button still works after one.
 */
internal object InquiryReview {
    const val BUTTON_PREFIX = "inquiry_resolve:"
    const val ACTIONS_PREFIX = "inquiry_actions:"
    const val RESOLVE_PERMISSION = "resolve"
    private const val COLOR_MATCH = 0x57F287
    private const val COLOR_MISMATCH = 0xED4245
    private const val COLOR_UNKNOWN = 0x99AAB5
    private const val COLOR_DONE = 0x4F545C

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "jbro-policy-inquiry-review").apply { isDaemon = true } }

    private var settings = InquiryReviewSettings()
    private var discord = DiscordSettings()
    private var rest: DiscordRest? = null
    private var reviewer: InquiryReviewer? = null
    private lateinit var records: Path
    private lateinit var logs: Path
    @Volatile private var server: MinecraftServer? = null

    val active: Boolean get() = reviewer != null

    /** One open inquiry, as kept on disk. */
    data class Record(
        val id: String,
        val nickname: String,
        val accountName: String,
        val playerId: String,
        val reason: String,
        val via: String,
        val at: Long,
        val cardMessageId: String,
        val conversationId: String? = null,
        val reviewed: Boolean = false,
        /** The inquiry's private thread; null for cards posted straight in the inquiry channel. */
        val cardChannelId: String? = null,
        /** What the review suggested, for the "조치·명령어 보기" button; null in records from before it. */
        val suggestedActions: List<String>? = null,
        val suggestedCommands: List<InquiryVerdict.SuggestedCommand>? = null,
    ) {
        fun inquiry() = Inquiry(nickname, accountName, UUID.fromString(playerId), reason, Inquiries.Via.valueOf(via), at, id)
    }

    /** Whether [customId] names one of the inquiry reviews' buttons. */
    fun handles(customId: String) = customId.startsWith(BUTTON_PREFIX) || customId.startsWith(ACTIONS_PREFIX)

    fun register(discord: DiscordSettings, settings: InquiryReviewSettings, gameDir: Path) {
        if (!settings.enabled) return
        if (!discord.botConfigured || discord.inquiryChannelId.isBlank() || discord.adminChannelId.isBlank()) {
            JbroPolicy.LOGGER.warn("Inquiry reviews need botToken, inquiryChannelId and adminChannelId in config/jbro-policy-discord.json; they stay off")
            return
        }
        this.settings = settings
        this.discord = discord
        rest = DiscordRest(discord.botToken)
        val root = gameDir.resolve(JbroPolicy.MOD_ID.replace('_', '-'))
        records = root.resolve("inquiries")
        logs = gameDir.resolve("logs")
        // An empty folder of its own, so the reviewer sees nothing of the server but what the prompt gives it.
        reviewer = InquiryReviewer(settings, root.resolve("inquiry-review"), root.resolve("inquiry-review-schema.json"))
        ServerLifecycleEvents.SERVER_STARTED.register { started ->
            server = started
            for (record in openRecords().filter { !it.reviewed }) worker.execute { review(record) }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            server = null
            reviewer?.cancel()
        }
    }

    /** Called once [inquiry]'s card is up as [cardMessageId] in [cardChannelId]; the review runs in the background. */
    fun enqueue(inquiry: Inquiry, cardMessageId: String, cardChannelId: String? = null) {
        if (!active) return
        val record = Record(inquiry.id, inquiry.nickname, inquiry.accountName, inquiry.playerId.toString(), inquiry.reason,
            inquiry.via.name, inquiry.at, cardMessageId, cardChannelId = cardChannelId)
        save(record)
        worker.execute { review(record) }
    }

    private fun review(record: Record) {
        val reviewer = reviewer ?: return
        val inquiry = record.inquiry()
        val at = InquiryLogWindow.local(inquiry.at)
        val from = at.minusMinutes(settings.minutesBefore)
        val to = at.plusMinutes(settings.minutesAfter)
        val outcome = runCatching {
            val window = InquiryLogWindow.collect(InquiryLogWindow.read(logs, from, to), from, to, at,
                listOf(inquiry.accountName, inquiry.nickname, inquiry.playerId.toString()), settings.maxLogCharacters)
            reviewer.review(InquiryReviewer.prompt(inquiry, window, settings.minutesBefore, settings.minutesAfter))
        }
        // The server stopped mid-review: it runs again on the next start.
        if (server == null) return
        val answer = outcome.getOrElse { failure ->
            JbroPolicy.LOGGER.warn("Could not review inquiry {} from {}", record.id, record.accountName, failure)
            post(discord.adminChannelId, DiscordRest.message(embed = failedEmbed(inquiry, failure.message.orEmpty())).withButton(record.id))
            save(record.copy(reviewed = true))
            return
        }
        val verdict = answer.verdict
        save(record.copy(conversationId = answer.conversationId, reviewed = true,
            suggestedActions = verdict.suggestedActions, suggestedCommands = verdict.suggestedCommands))
        JbroPolicy.LOGGER.info("Reviewed inquiry {} from {}: {}", record.id, record.accountName, verdict.verdict)
        server?.execute { OperatorWhisper.send(server ?: return@execute, inquiry.playerId, verdict.playerSummary, inquiry.reason) }
        post(record.cardChannelId ?: discord.inquiryChannelId, reply(record.cardMessageId, verdict.playerSummary))
        post(discord.adminChannelId, DiscordRest.message(embed = reviewEmbed(inquiry, verdict)).withButton(record.id, actions = true))
    }

    /**
     * The answer to a press of a "처리 완료" button: closes the inquiry for an operator allowed to, else says why not
     * to the presser alone.
     */
    fun press(customId: String, caller: DiscordCaller, message: JsonObject?): JsonObject {
        if (customId.startsWith(ACTIONS_PREFIX)) return showActions(customId.removePrefix(ACTIONS_PREFIX), caller)
        val id = customId.removePrefix(BUTTON_PREFIX)
        val refusal = DiscordAdminAccess.check(discord, caller, RESOLVE_PERMISSION) as? DiscordAdminAccess.Verdict.Refused
        JbroPolicy.LOGGER.info("Discord {} closing inquiry {} by {}", if (refusal == null) "ran" else "refused", id, caller)
        if (refusal != null) return ephemeral(refusal.reason)
        resolve(id)
        val embed = message?.getAsJsonArray("embeds")?.firstOrNull()?.asJsonObject?.deepCopy() ?: JsonObject()
        embed.addProperty("color", COLOR_DONE)
        val fields = embed.getAsJsonArray("fields") ?: JsonArray().also { embed.add("fields", it) }
        fields.add(field("처리", "✅ ${caller.userName}님이 처리 완료했어요.", inline = false))
        return JsonObject().apply {
            addProperty("type", UPDATE_MESSAGE)
            add("data", JsonObject().apply {
                add("embeds", JsonArray().apply { add(embed) })
                add("components", JsonArray())
            })
        }
    }

    /** The suggested actions and commands of inquiry [id], seen by the presser alone so they can copy the commands. */
    private fun showActions(id: String, caller: DiscordCaller): JsonObject {
        val refusal = DiscordAdminAccess.check(discord, caller, RESOLVE_PERMISSION) as? DiscordAdminAccess.Verdict.Refused
        if (refusal != null) return ephemeral(refusal.reason)
        val record = (if (ID.matches(id) && ::records.isInitialized) load(records.resolve("$id.json")) else null)
            ?: return ephemeral("이미 처리했거나 찾을 수 없는 문의예요.")
        return ephemeral(actionsText(id, record.suggestedActions.orEmpty(), record.suggestedCommands.orEmpty()))
    }

    /** Actions as bullets, then each command in its own code block, under Discord's 2000 characters. */
    internal fun actionsText(id: String, actions: List<String>, commands: List<InquiryVerdict.SuggestedCommand>): String {
        val text = StringBuilder("**문의 $id 권장 조치** · 나에게만 보여요\n")
        text.append(if (actions.isEmpty()) "-\n" else actions.joinToString("\n", postfix = "\n") { "• $it" })
        if (commands.isEmpty()) {
            text.append("\n제안할 명령어가 없어요.")
        } else {
            text.append("\n**명령어** · 꺾쇠(<>) 자리는 채워서 쓰세요. 자동으로 실행되지 않아요.\n")
            for (command in commands) {
                val block = (if (command.why.isBlank()) "" else "${command.why}\n") + "```\n${command.command}\n```\n"
                if (text.length + block.length > 1990) break
                text.append(block)
            }
        }
        return text.toString().take(2000)
    }

    /** Forgets inquiry [id]: its record and the conversation its review left in agy. */
    fun resolve(id: String) {
        if (!ID.matches(id) || !::records.isInitialized) return
        val file = records.resolve("$id.json")
        val record = load(file)
        record?.conversationId?.let { conversation ->
            try { reviewer?.forget(conversation) } catch (failure: Exception) {
                JbroPolicy.LOGGER.warn("Could not delete the review conversation {} of inquiry {}", conversation, id, failure)
            }
        }
        Files.deleteIfExists(file)
    }

    private val ID = Regex("[0-9a-f]{8}")
    private const val UPDATE_MESSAGE = 7
    private const val CHANNEL_MESSAGE = 4
    private const val EPHEMERAL = 64

    private fun ephemeral(text: String) = JsonObject().apply {
        addProperty("type", CHANNEL_MESSAGE)
        add("data", DiscordRest.message(text).apply { addProperty("flags", EPHEMERAL) })
    }

    private fun post(channelId: String, body: JsonObject) {
        val client = rest ?: return
        try {
            val response = client.request("POST", "/channels/$channelId/messages", body)
            if (!response.ok) JbroPolicy.LOGGER.warn("Discord refused an inquiry review message ({}): {}", response.status, response.body.take(300))
        } catch (failure: Exception) {
            JbroPolicy.LOGGER.warn("Could not post an inquiry review message", failure)
        }
    }

    /** A reply under the card that pings no one, the card's author included. */
    internal fun reply(cardMessageId: String, text: String) = DiscordRest.message(text).apply {
        add("message_reference", JsonObject().apply {
            addProperty("message_id", cardMessageId)
            addProperty("fail_if_not_exists", false)
        })
        getAsJsonObject("allowed_mentions").addProperty("replied_user", false)
    }

    private fun JsonObject.withButton(id: String, actions: Boolean = false) = apply {
        add("components", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", 1)
                add("components", JsonArray().apply {
                    add(button(3, "처리 완료", BUTTON_PREFIX + id))
                    if (actions) add(button(2, "조치·명령어 보기", ACTIONS_PREFIX + id))
                })
            })
        })
    }

    private fun button(style: Int, label: String, customId: String) = JsonObject().apply {
        addProperty("type", 2)
        addProperty("style", style)
        addProperty("label", label)
        addProperty("custom_id", customId)
    }

    internal fun reviewEmbed(inquiry: Inquiry, verdict: InquiryVerdict) = JsonObject().apply {
        addProperty("title", "문의 검토 · ${inquiry.nickname} (${inquiry.accountName})".take(256))
        addProperty("description", "**${verdict.verdict.label}**\n\n${verdict.operatorDetail}".take(4096))
        addProperty("color", when (verdict.verdict) {
            InquiryVerdict.Verdict.MATCH -> COLOR_MATCH
            InquiryVerdict.Verdict.MISMATCH -> COLOR_MISMATCH
            InquiryVerdict.Verdict.UNKNOWN -> COLOR_UNKNOWN
        })
        add("fields", JsonArray().apply {
            add(field("문의", inquiry.reason, inline = false))
            add(field("근거 로그", if (verdict.evidence.isEmpty()) "-" else codeBlock(verdict.evidence), inline = false))
            add(field("권장 조치", verdict.suggestedActions.joinToString("\n") { "• $it" }, inline = false))
            add(field("플레이어에게 보낸 답", verdict.playerSummary, inline = false))
            add(field("UUID", inquiry.playerId.toString()))
            add(field("시각", "${inquiry.time} (KST)"))
        })
        add("footer", JsonObject().apply { addProperty("text", "문의 번호 ${inquiry.id} · 권장 조치는 자동으로 실행되지 않아요") })
    }

    private fun failedEmbed(inquiry: Inquiry, reason: String) = JsonObject().apply {
        addProperty("title", "문의 검토 실패 · ${inquiry.nickname} (${inquiry.accountName})".take(256))
        addProperty("description", "자동 검토를 하지 못했어요. 플레이어에게는 아무것도 보내지 않았으니 직접 확인해 주세요.\n\n`${reason.take(500)}`")
        addProperty("color", COLOR_UNKNOWN)
        add("fields", JsonArray().apply {
            add(field("문의", inquiry.reason, inline = false))
            add(field("시각", "${inquiry.time} (KST)"))
        })
        add("footer", JsonObject().apply { addProperty("text", "문의 번호 ${inquiry.id}") })
    }

    /** Log lines in a code block, cut to fit one embed field. */
    private fun codeBlock(lines: List<String>): String {
        val body = StringBuilder()
        for (line in lines.map { it.replace("```", "'''") }) {
            if (body.length + line.length + 1 > 1000) break
            body.append(line).append('\n')
        }
        return "```\n$body```"
    }

    private fun field(name: String, value: String, inline: Boolean = true) = JsonObject().apply {
        addProperty("name", name)
        addProperty("value", value.ifBlank { "-" }.take(1024))
        addProperty("inline", inline)
    }

    private fun save(record: Record) {
        try {
            Files.createDirectories(records)
            Files.writeString(records.resolve("${record.id}.json"), gson.toJson(record))
        } catch (failure: java.io.IOException) {
            JbroPolicy.LOGGER.warn("Could not save inquiry {}", record.id, failure)
        }
    }

    private fun load(file: Path): Record? = try {
        if (Files.exists(file)) gson.fromJson(Files.readString(file), Record::class.java) else null
    } catch (failure: Exception) {
        JbroPolicy.LOGGER.warn("Could not read inquiry record {}", file, failure)
        null
    }

    private fun openRecords(): List<Record> {
        if (!Files.isDirectory(records)) return emptyList()
        return Files.list(records).use { files -> files.toList() }.filter { it.toString().endsWith(".json") }.mapNotNull(::load)
    }
}
