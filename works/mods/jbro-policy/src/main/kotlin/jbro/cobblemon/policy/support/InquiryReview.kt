package jbro.cobblemon.policy.support

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.legend.LegendRestore
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
 * @property autoResolve whether supported read-only questions can close automatically. BP limit keys are legacy and do not enable payments.
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
    val autoResolve: Boolean = true,
    val autoMaxBpPerInquiry: Long = 300,
    val autoMaxBpPerDay: Long = 600,
) {
    init {
        require(minutesBefore in 0..1440 && minutesAfter in 0..60) { "Inquiry log window is out of bounds" }
        require(maxLogCharacters in 4096..500_000) { "Inquiry log character limit is out of bounds" }
        require(timeoutSeconds in 1..300) { "Inquiry timeout is out of bounds" }
    }

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
                root.get("autoResolve")?.asBoolean ?: defaults.autoResolve,
                defaults.autoMaxBpPerInquiry,
                defaults.autoMaxBpPerDay,
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
 * "조치·명령어 보기" button that shows the presser alone the suggested commands to copy. Commands [InquiryProposal] finds
 * ready to run are put to the operators as a question, and run only when one presses "승인하고 실행".
 *
 * Needs the bot, the inquiry channel and the admin channel. Open inquiries live in `jbro-policy/inquiries/`, so a
 * review cut short by a restart runs again and the button still works after one.
 */
internal object InquiryReview {
    const val BUTTON_PREFIX = "inquiry_resolve:"
    const val ACTIONS_PREFIX = "inquiry_actions:"
    const val APPROVE_PREFIX = "inquiry_approve:"
    const val REJECT_PREFIX = "inquiry_reject:"
    const val RESOLVE_PERMISSION = "resolve"
    /** An approval runs commands as the console, so it asks what the bot's console command asks. */
    const val APPROVE_PERMISSION = "console"
    private const val TENTATIVE_DAYS = 3L
    private const val BATTLE_CHARACTERS = 6_000
    private const val COLOR_MATCH = 0x57F287
    private const val COLOR_MISMATCH = 0xED4245
    private const val COLOR_UNKNOWN = 0x99AAB5
    private const val COLOR_DONE = 0x4F545C

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "jbro-policy-inquiry-review").apply { isDaemon = true } }

    private val closed = ConcurrentHashMap.newKeySet<String>()
    private var settings = InquiryReviewSettings()
    private var discord = DiscordSettings()
    private var rest: DiscordRest? = null
    private var reviewer: InquiryReviewer? = null
    private var withContents = false
    private lateinit var records: Path
    private lateinit var logs: Path
    private lateinit var gameDir: Path
    @Volatile private var server: MinecraftServer? = null

    val active: Boolean get() = reviewer != null

    private fun say(key: String, vararg values: Pair<String, Any?>) = PichuRules.current().text("texts.$key", *values)

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
        val followUps: List<String>? = null,
        /** Commands waiting for an operator's approval; null once decided. */
        val proposal: List<InquiryVerdict.SuggestedCommand>? = null,
        /** Answered, and kept open a few days so a follow-up can still reach an operator. */
        val tentative: Boolean = false,
        /** The despawned Legend's spawn record, when the proposal spawns it again. */
        val legendSource: String? = null,
    ) {
        fun acceptsFollowUp(owner: UUID, text: String): Boolean =
            playerId == owner.toString() && text.isNotBlank() &&
                text.codePointCount(0, text.length) <= Inquiries.MAX_REASON_LENGTH && followUps.orEmpty().size < 3

        fun inquiry() = Inquiry(nickname, accountName, UUID.fromString(playerId), reason + followUps.orEmpty().joinToString("", prefix = "") { "\n추가 설명: $it" }, Inquiries.Via.valueOf(via), at, id)
    }

    /** Whether [customId] names one of the inquiry reviews' buttons. */
    fun handles(customId: String) = listOf(BUTTON_PREFIX, ACTIONS_PREFIX, APPROVE_PREFIX, REJECT_PREFIX).any(customId::startsWith)

    fun register(discord: DiscordSettings, settings: InquiryReviewSettings, gameDir: Path, withContents: Boolean = false) {
        if (!settings.enabled) return
        this.withContents = withContents
        if (!discord.botConfigured || discord.inquiryChannelId.isBlank() || discord.adminChannelId.isBlank()) {
            JbroPolicy.LOGGER.warn("Inquiry reviews need botToken, inquiryChannelId and adminChannelId in config/jbro-policy-discord.json; they stay off")
            return
        }
        this.gameDir = gameDir
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
            val oldest = System.currentTimeMillis() - TENTATIVE_DAYS * 24 * 60 * 60 * 1000
            for (record in openRecords()) when {
                !record.reviewed -> worker.execute { review(record) }
                record.tentative && record.at < oldest -> resolve(record.id)
            }
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
        if (save(record)) worker.execute { review(record) }
    }

    /** Queued with reviews: only the owner may add up to three bounded follow-ups to an open inquiry. */
    fun followUp(playerId: UUID, id: String, text: String): CompletableFuture<Boolean> {
        if (!active || !ID.matches(id) || text.isBlank() || text.codePointCount(0, text.length) > Inquiries.MAX_REASON_LENGTH) {
            return CompletableFuture.completedFuture(false)
        }
        return CompletableFuture.supplyAsync({
            val record = load(records.resolve("$id.json"))
            if (record == null || !record.acceptsFollowUp(playerId, text)) false
            else {
                val updated = record.copy(followUps = record.followUps.orEmpty() + text, reviewed = false)
                if (!save(updated)) return@supplyAsync false
                post(record.cardChannelId ?: discord.inquiryChannelId,
                    DiscordRest.message(say("followUpPosted", "id" to id, "text" to text)))
                review(updated)
                true
            }
        }, worker)
    }

    private fun review(record: Record) {
        if (record.id in closed) return
        val reviewer = reviewer ?: return
        val inquiry = record.inquiry()
        val at = InquiryLogWindow.local(inquiry.at)
        val from = at.minusMinutes(settings.minutesBefore)
        val to = at.plusMinutes(settings.minutesAfter)
        var reference = ""
        val data = playerData(inquiry)
        val battles = runCatching {
            BattleRecords.forPlayer(inquiry.playerId, inquiry.at - settings.minutesBefore * 60_000, inquiry.at + settings.minutesAfter * 60_000, BATTLE_CHARACTERS)
        }.getOrDefault(emptyList())
        val outcome = runCatching {
            val collected = InquiryLogWindow.collect(InquiryLogWindow.read(logs, from, to), from, to, at,
                listOf(inquiry.accountName, inquiry.nickname, inquiry.playerId.toString()), settings.maxLogCharacters)
            val currentServer = server ?: error("Server unavailable")
            val wiki = runCatching { PichuWiki.reference(PichuWiki.directory(gameDir), inquiry.reason) }.getOrDefault("")
            val installed = runCatching { InquiryServerContext.reference(currentServer, gameDir, inquiry.reason) }
                .getOrElse { say("installedUnavailable") }
            reference = listOf(wiki, installed).filter { it.isNotBlank() }.joinToString("\n")
            val livePlayer = runCatching { InquiryServerContext.player(currentServer, inquiry.playerId, inquiry.reason) }
                .getOrElse { say("playerUnavailable") }
            reviewer.review(InquiryReviewer.prompt(inquiry, collected, settings.minutesBefore, settings.minutesAfter,
                data?.take(PLAYER_DATA_CHARACTERS), reference, livePlayer,
                battles.joinToString("\n\n") { "${it.summary()}\n${it.body}" }.ifEmpty { null }))
        }
        // The server stopped mid-review: it runs again on the next start.
        if (server == null || record.id in closed) return
        val answer = outcome.getOrElse { failure ->
            JbroPolicy.LOGGER.warn("Could not review inquiry {} from {}", record.id, record.accountName, failure)
            post(discord.adminChannelId, DiscordRest.message(embed = failedEmbed(inquiry, failure.message.orEmpty())).withButton(record.id))
            save(record.copy(reviewed = true))
            return
        }
        val followedUp = !record.followUps.isNullOrEmpty()
        val verdict = InquiryAutoResolve.guard(inquiry.reason, answer.verdict, strict = followedUp)
        // A vanished Legend gets the server's answer from its spawn record, not whatever the review came up with.
        val legend = server?.let { InquiryLegendRestore.proposal(it, inquiry) }
        val proposal = if (legend != null) listOfNotNull(legend.command) else InquiryProposal.executable(verdict.suggestedCommands, inquiry.accountName)
        val saved = record.copy(conversationId = answer.conversationId, reviewed = true, tentative = false,
            suggestedActions = verdict.suggestedActions, suggestedCommands = listOfNotNull(legend?.command) + verdict.suggestedCommands, proposal = proposal, legendSource = legend?.source?.toString())
        if (!save(saved)) return
        val handling = settle(inquiry, verdict, reference)
        val tentative = !handling.handedOver && handling.ran.isEmpty() && !handling.changed && InquiryAutoResolve.tentative(inquiry.reason)
        JbroPolicy.LOGGER.info("Reviewed inquiry {} from {}: {}, {}", record.id, record.accountName, verdict.verdict, handling.note)
        if (record.conversationId != null && record.conversationId != answer.conversationId) {
            runCatching { reviewer.forget(record.conversationId) }
        }
        val answerText = InquiryReviewer.reply(handling.answer ?: verdict.playerSummary, handling.handedOver, handling.changed) +
            when {
                handling.handedOver -> say("followUpHandedOver", "id" to record.id)
                tentative -> say("followUpTentative", "id" to record.id)
                else -> ""
            }
        server?.execute { OperatorWhisper.send(server ?: return@execute, inquiry.playerId, answerText, inquiry.reason) }
        post(record.cardChannelId ?: discord.inquiryChannelId, reply(record.cardMessageId, answerText))
        val asking = if (handling.handedOver) proposal else emptyList()
        val card = DiscordRest.message(embed = reviewEmbed(inquiry, verdict, handling, answerText, asking, battles.map { it.summary() }, legend?.note))
        if (handling.handedOver) {
            post(discord.adminChannelId, card.withButton(record.id, actions = true, approve = asking.isNotEmpty()))
        } else if (tentative) {
            // Nothing to press; the record stays only for the player's follow-up.
            post(discord.adminChannelId, card)
            save(saved.copy(tentative = true, proposal = null))
        } else {
            // Settled: kept in the admin channel as a record, with nothing left to press.
            post(discord.adminChannelId, card)
            resolve(record.id)
        }
    }

    /** How Pichu dealt with an inquiry: what it ran, and whether an operator still has to look. */
    internal data class Handling(val handedOver: Boolean, val ran: List<String>, val note: String,
        val answer: String? = null, val changed: Boolean = false)

    /** How much of the player data the prompt carries; the server's own checks read all of it. */
    private const val PLAYER_DATA_CHARACTERS = 20_000

    private fun settle(inquiry: Inquiry, verdict: InquiryVerdict, reference: String): Handling {
        if (!settings.autoResolve) return Handling(true, emptyList(), say("autoOff"))
        server?.let { InquiryLegendRestore.attempt(it, inquiry) }?.let { restored ->
            return Handling(false, listOfNotNull(restored.ran), restored.message, restored.message, restored.changed)
        }
        if (withContents && verdict.autoCommands.isEmpty() &&
            verdict.category in setOf(InquiryVerdict.Category.REWARD, InquiryVerdict.Category.SHOP)) {
            val activeServer = server ?: return Handling(true, emptyList(), say("serverNeeded"))
            val result = InquiryCompensation.attempt(activeServer, inquiry, verdict.category, settings.minutesBefore, settings.minutesAfter)
            return Handling(!result.handled, emptyList(), result.message, result.message, result.changed)
        }
        return when (val plan = InquiryAutoResolve.plan(verdict, reference)) {
            is InquiryAutoResolve.Plan.HandOver -> Handling(true, emptyList(), plan.why)
            InquiryAutoResolve.Plan.Explain -> Handling(false, emptyList(), say("answered"))
        }
    }

    /** The player's standing in the contents, or null without More Cobblemon Contents or when it cannot be read. */
    private fun playerData(inquiry: Inquiry): String? {
        val server = server ?: return null
        if (!withContents) return null
        return try { InquiryPlayerData.of(server, inquiry.playerId, Int.MAX_VALUE) } catch (failure: Exception) {
            JbroPolicy.LOGGER.warn("Could not read the player data of inquiry {}", inquiry.id, failure)
            null
        }
    }

    /**
     * The answer to a press of a "처리 완료" button: closes the inquiry for an operator allowed to, else says why not
     * to the presser alone.
     */
    fun press(customId: String, caller: DiscordCaller, message: JsonObject?): JsonObject {
        if (customId.startsWith(ACTIONS_PREFIX)) return showActions(customId.removePrefix(ACTIONS_PREFIX), caller)
        if (customId.startsWith(APPROVE_PREFIX)) return approve(customId.removePrefix(APPROVE_PREFIX), caller, message)
        if (customId.startsWith(REJECT_PREFIX)) return reject(customId.removePrefix(REJECT_PREFIX), caller, message)
        val id = customId.removePrefix(BUTTON_PREFIX)
        val refusal = DiscordAdminAccess.check(discord, caller, RESOLVE_PERMISSION) as? DiscordAdminAccess.Verdict.Refused
        JbroPolicy.LOGGER.info("Discord {} closing inquiry {} by {}", if (refusal == null) "ran" else "refused", id, caller)
        if (refusal != null) return ephemeral(refusal.reason)
        resolve(id)
        return updatedCard(message, say("fieldHandled"), say("closedBy", "user" to caller.userName), done = true, JsonArray())
    }

    /** The card with one more field and new buttons, as the answer to a press. */
    private fun updatedCard(message: JsonObject?, name: String, value: String, done: Boolean, components: JsonArray): JsonObject {
        val embed = message?.getAsJsonArray("embeds")?.firstOrNull()?.asJsonObject?.deepCopy() ?: JsonObject()
        if (done) embed.addProperty("color", COLOR_DONE)
        val fields = embed.getAsJsonArray("fields") ?: JsonArray().also { embed.add("fields", it) }
        fields.add(field(name, value, inline = false))
        return JsonObject().apply {
            addProperty("type", UPDATE_MESSAGE)
            add("data", JsonObject().apply {
                add("embeds", JsonArray().apply { add(embed) })
                add("components", components)
            })
        }
    }

    /** Runs what Pichu proposed, once, then closes the inquiry and tells the player. */
    private fun approve(id: String, caller: DiscordCaller, message: JsonObject?): JsonObject {
        val refusal = DiscordAdminAccess.check(discord, caller, APPROVE_PERMISSION) as? DiscordAdminAccess.Verdict.Refused
        JbroPolicy.LOGGER.info("Discord {} approving inquiry {} by {}", if (refusal == null) "ran" else "refused", id, caller)
        if (refusal != null) return ephemeral(refusal.reason)
        val activeServer = server ?: return ephemeral(say("serverOff"))
        // Taken off the record first, so a second press finds nothing to run.
        val (record, commands) = takeProposal(id) ?: return ephemeral(say("proposalGone"))
        val source = record.legendSource?.let(UUID::fromString)
        val settledSince = say("legendSettled")
        val future = CompletableFuture<String>()
        activeServer.execute {
            // The Legend may have been caught or spawned again while the card waited.
            if (source != null && !LegendRestore.stillGone(activeServer, source)) {
                future.complete(settledSince)
                return@execute
            }
            future.complete(commands.joinToString("\n") { command ->
                val text = command.command.removePrefix("/")
                JbroPolicy.LOGGER.info("Inquiry {} approved command: {}", id, text)
                "/$text → " + runCatching { DiscordAdminCommands.runCommand(activeServer, text) }.getOrElse { say("runFailed", "reason" to it.message) }
            })
            source?.let { LegendRestore.continued(activeServer, it) }
        }
        val result = try { future.get(APPROVAL_WAIT_SECONDS, TimeUnit.SECONDS) } catch (_: Exception) { say("stillRunning") }
        resolve(id)
        if (result != settledSince) {
            val notice = say("approvedNotice")
            activeServer.execute { OperatorWhisper.send(activeServer, UUID.fromString(record.playerId), notice, record.reason) }
            worker.execute { post(record.cardChannelId ?: discord.inquiryChannelId, reply(record.cardMessageId, notice)) }
        }
        return updatedCard(message, say("fieldHandled"), say("approvedBy", "user" to caller.userName) + "\n" + result.take(900), done = true, JsonArray())
    }

    /** Declines the proposal; the inquiry stays open for an operator to settle by hand. */
    private fun reject(id: String, caller: DiscordCaller, message: JsonObject?): JsonObject {
        val refusal = DiscordAdminAccess.check(discord, caller, RESOLVE_PERMISSION) as? DiscordAdminAccess.Verdict.Refused
        if (refusal != null) return ephemeral(refusal.reason)
        takeProposal(id) ?: return ephemeral(say("proposalGone"))
        return updatedCard(message, say("fieldProposalResult"), say("rejectedBy", "user" to caller.userName), done = false,
            JsonObject().withButton(id, actions = true).getAsJsonArray("components"))
    }

    @Synchronized
    private fun takeProposal(id: String): Pair<Record, List<InquiryVerdict.SuggestedCommand>>? {
        if (!ID.matches(id) || !::records.isInitialized) return null
        val record = load(records.resolve("$id.json")) ?: return null
        val commands = record.proposal.orEmpty().takeIf { it.isNotEmpty() } ?: return null
        return if (save(record.copy(proposal = null))) record to commands else null
    }

    /** The suggested actions and commands of inquiry [id], seen by the presser alone so they can copy the commands. */
    private fun showActions(id: String, caller: DiscordCaller): JsonObject {
        val refusal = DiscordAdminAccess.check(discord, caller, RESOLVE_PERMISSION) as? DiscordAdminAccess.Verdict.Refused
        if (refusal != null) return ephemeral(refusal.reason)
        val record = (if (ID.matches(id) && ::records.isInitialized) load(records.resolve("$id.json")) else null)
            ?: return ephemeral(say("inquiryGone"))
        return ephemeral(actionsText(id, record.suggestedActions.orEmpty(), record.suggestedCommands.orEmpty()))
    }

    /** Actions as bullets, then each command in its own code block, under Discord's 2000 characters. */
    internal fun actionsText(id: String, actions: List<String>, commands: List<InquiryVerdict.SuggestedCommand>): String {
        val text = StringBuilder(say("actionsTitle", "id" to id)).append('\n')
        text.append(if (actions.isEmpty()) "-\n" else actions.joinToString("\n", postfix = "\n") { "• $it" })
        if (commands.isEmpty()) {
            text.append('\n').append(say("actionsNoCommands"))
        } else {
            text.append('\n').append(say("actionsCommands")).append('\n')
            for (command in commands) {
                val block = (if (command.why.isBlank()) "" else "${command.why}\n") + "```\n${command.command}\n```\n"
                if (text.length + block.length > 1990) break
                text.append(block)
            }
        }
        return text.toString().take(2000)
    }

    /** Forgets inquiry [id]: its record and the conversation its review left in agy. */
    @Synchronized
    fun resolve(id: String) {
        if (!ID.matches(id) || !::records.isInitialized) return
        closed.add(id)
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
    /** Discord drops a button answer after three seconds. */
    private const val APPROVAL_WAIT_SECONDS = 2L

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

    private fun JsonObject.withButton(id: String, actions: Boolean = false, approve: Boolean = false) = apply {
        add("components", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", 1)
                add("components", JsonArray().apply {
                    if (approve) {
                        add(button(1, say("buttonApprove"), APPROVE_PREFIX + id))
                        add(button(4, say("buttonReject"), REJECT_PREFIX + id))
                    }
                    add(button(3, say("buttonDone"), BUTTON_PREFIX + id))
                    if (actions) add(button(2, say("buttonActions"), ACTIONS_PREFIX + id))
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

    internal fun reviewEmbed(inquiry: Inquiry, verdict: InquiryVerdict, handling: Handling? = null, answer: String = verdict.playerSummary,
        proposal: List<InquiryVerdict.SuggestedCommand> = emptyList(), battles: List<String> = emptyList(), legendNote: String? = null) = JsonObject().apply {
        val settled = handling != null && !handling.handedOver
        addProperty("title", say(if (settled) "cardTitleSettled" else "cardTitle", "nickname" to inquiry.nickname, "account" to inquiry.accountName).take(256))
        addProperty("description", "**${verdict.verdict.label}**\n\n${verdict.operatorDetail}".take(4096))
        addProperty("color", if (settled) COLOR_DONE else when (verdict.verdict) {
            InquiryVerdict.Verdict.MATCH -> COLOR_MATCH
            InquiryVerdict.Verdict.MISMATCH -> COLOR_MISMATCH
            InquiryVerdict.Verdict.UNKNOWN -> COLOR_UNKNOWN
        })
        add("fields", JsonArray().apply {
            add(field(say("fieldInquiry"), inquiry.reason, inline = false))
            add(field(say("fieldCategory"), verdict.category.label))
            if (handling != null) add(field(say("fieldPichu"), buildString {
                append(say(if (handling.handedOver) "handlingHandedOver" else "handlingSettled")).append(handling.note)
                if (handling.ran.isNotEmpty()) append("\n").append(handling.ran.joinToString("\n") { "`/$it`" })
            }, inline = false))
            add(field(say("fieldEvidence"), if (verdict.evidence.isEmpty()) "-" else codeBlock(verdict.evidence), inline = false))
            add(field(say("fieldActions"), verdict.suggestedActions.joinToString("\n") { "• $it" }, inline = false))
            if (proposal.isNotEmpty()) add(field(say("fieldProposal"), InquiryProposal.question(proposal), inline = false))
            if (legendNote != null) add(field(say("fieldProposal"), legendNote, inline = false))
            if (battles.isNotEmpty()) add(field(say("fieldBattles"), battles.joinToString("\n"), inline = false))
            add(field(say("fieldAnswer"), answer, inline = false))
            add(field("UUID", inquiry.playerId.toString()))
            add(field(say("fieldTime"), "${inquiry.time} (KST)"))
        })
        add("footer", JsonObject().apply {
            addProperty("text", say(if (settled) "footerSettled" else "footerOpen", "id" to inquiry.id))
        })
    }

    private fun failedEmbed(inquiry: Inquiry, reason: String) = JsonObject().apply {
        addProperty("title", say("failedTitle", "nickname" to inquiry.nickname, "account" to inquiry.accountName).take(256))
        addProperty("description", say("failedBody") + "\n\n`${reason.take(500)}`")
        addProperty("color", COLOR_UNKNOWN)
        add("fields", JsonArray().apply {
            add(field(say("fieldInquiry"), inquiry.reason, inline = false))
            add(field(say("fieldTime"), "${inquiry.time} (KST)"))
        })
        add("footer", JsonObject().apply { addProperty("text", say("footerFailed", "id" to inquiry.id)) })
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

    @Synchronized
    private fun save(record: Record): Boolean {
        if (record.id in closed) return false
        return try {
            Files.createDirectories(records)
            val file = records.resolve("${record.id}.json")
            val temporary = Files.createTempFile(records, record.id + "-", ".tmp")
            try {
                Files.writeString(temporary, gson.toJson(record))
                Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
            true
        } catch (failure: java.io.IOException) {
            JbroPolicy.LOGGER.warn("Could not save inquiry {}", record.id, failure)
            false
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
        return Files.list(records).use { files -> files.toList() }.filter { ID.matches(it.fileName.toString().removeSuffix(".json")) && it.toString().endsWith(".json") }.mapNotNull(::load)
    }
}
