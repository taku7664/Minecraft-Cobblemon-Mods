package jbro.cobblemon.policy.support

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer

/**
 * One Discord gateway connection, as the messages it answers: Hello starts the heartbeat and the login, a missed
 * heartbeat acknowledgement or a reconnect request starts over, and a refused token stops for good. No socket here,
 * so tests drive it with plain messages.
 */
internal class DiscordGatewaySession(private val token: String) {
    sealed interface Action {
        data class Send(val payload: JsonObject) : Action
        data class Heartbeat(val intervalMillis: Long) : Action
        data object Reconnect : Action
        data class Stop(val reason: String) : Action
        data class Ready(val name: String, val applicationId: String, val guildIds: List<String>) : Action
        data class Interaction(val data: JsonObject) : Action
    }

    private var sequence: Long? = null
    private var acknowledged = true

    fun onMessage(text: String): List<Action> {
        val message = JsonParser.parseString(text).asJsonObject
        message.get("s")?.takeUnless { it.isJsonNull }?.let { sequence = it.asLong }
        return when (message.get("op")?.asInt) {
            HELLO -> {
                acknowledged = true
                listOf(Action.Heartbeat(message.getAsJsonObject("d").get("heartbeat_interval").asLong), Action.Send(identify()))
            }
            HEARTBEAT_ACK -> { acknowledged = true; emptyList() }
            HEARTBEAT -> listOf(Action.Send(heartbeatPayload()))
            RECONNECT, INVALID_SESSION -> listOf(Action.Reconnect)
            DISPATCH -> dispatch(message.get("t")?.takeUnless { it.isJsonNull }?.asString, message.getAsJsonObject("d"))
            else -> emptyList()
        }
    }

    private fun dispatch(type: String?, data: JsonObject?): List<Action> = when {
        data == null -> emptyList()
        type == "READY" -> listOf(Action.Ready(
            data.getAsJsonObject("user").get("username").asString,
            data.getAsJsonObject("application").get("id").asString,
            data.getAsJsonArray("guilds")?.map { it.asJsonObject.get("id").asString }.orEmpty(),
        ))
        type == "INTERACTION_CREATE" -> listOf(Action.Interaction(data))
        else -> emptyList()
    }

    /** The next heartbeat, or a reconnect when the last one was never acknowledged (a dead connection). */
    fun heartbeat(): Action {
        if (!acknowledged) return Action.Reconnect
        acknowledged = false
        return Action.Send(heartbeatPayload())
    }

    /** Close codes Discord uses for a token or setup that will never work; anything else is worth reconnecting. */
    fun onClose(code: Int): Action = when (code) {
        4004 -> Action.Stop("the bot token was refused")
        4010, 4011, 4012, 4013, 4014 -> Action.Stop("Discord refused the connection settings (code $code)")
        else -> Action.Reconnect
    }

    private fun heartbeatPayload() = JsonObject().apply {
        addProperty("op", HEARTBEAT)
        add("d", sequence?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }

    private fun identify() = JsonObject().apply {
        addProperty("op", IDENTIFY)
        add("d", JsonObject().apply {
            addProperty("token", token)
            // Slash commands arrive without any intent; the bot reads no messages.
            addProperty("intents", 0)
            add("properties", JsonObject().apply {
                addProperty("os", System.getProperty("os.name").orEmpty().lowercase())
                addProperty("browser", "jbro-policy")
                addProperty("device", "jbro-policy")
            })
            add("presence", PRESENCE)
        })
    }

    companion object {
        const val DISPATCH = 0
        const val HEARTBEAT = 1
        const val IDENTIFY = 2
        const val RECONNECT = 7
        const val INVALID_SESSION = 9
        const val HELLO = 10
        const val HEARTBEAT_ACK = 11

        /** Online, with no status text. */
        val PRESENCE: JsonObject get() = JsonObject().apply {
            add("since", JsonNull.INSTANCE)
            add("activities", JsonArray())
            addProperty("status", "online")
            addProperty("afk", false)
        }
    }
}

/**
 * The server's Discord bot, online from server start to stop. It keeps the status channel's card current, posts
 * news, answers slash commands and posts inquiries when it has a channel for them. It reconnects by itself after a
 * dropped connection, waiting longer each time up to five minutes, and gives up only when Discord refuses the token.
 */
internal object DiscordBot {
    const val USER_AGENT = "DiscordBot (https://github.com/taku7664/Minecraft-Cobblemon-Mods, 1.0)"
    private const val GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
    private const val STATUS_DEBOUNCE_SECONDS = 15L
    private const val STATUS_REFRESH_MINUTES = 5L
    private const val COMMAND_TIMEOUT_SECONDS = 10L

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    // Everything about the connection happens on this one thread, so sends never overlap.
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "jbro-policy-discord").apply { isDaemon = true } }
    // REST calls (status card, news, commands) run here, so a slow request never holds up the gateway.
    private val restWorker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "jbro-policy-discord-rest").apply { isDaemon = true } }

    private var token = ""
    private var settings = DiscordSettings()
    private var rest: DiscordRest? = null
    @Volatile private var server: MinecraftServer? = null
    @Volatile private var running = false
    @Volatile private var players = 0
    /** The Discord servers the bot is in, as of its last login. */
    @Volatile var guilds: List<String> = emptyList()
        private set
    private var socket: WebSocket? = null
    private var session: DiscordGatewaySession? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var retryDelaySeconds = 0L
    private var status: DiscordStatusMessage? = null
    private var statusRefresh: ScheduledFuture<*>? = null
    private var statusPending: ScheduledFuture<*>? = null

    /** Runs [task] on the worker; a failure is logged, where a scheduled executor would otherwise drop it silently. */
    private fun onWorker(task: () -> Unit) = worker.execute(guarded(task))

    private fun guarded(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (failure: Exception) {
            JbroPolicy.LOGGER.warn("Discord bot task failed", failure)
        }
    }

    /**
     * Starts the bot with the server when [settings] has a token. [withContents] adds the commands and news of More
     * Cobblemon Contents, and must be true only when it is installed.
     */
    fun register(settings: DiscordSettings, statusFile: java.nio.file.Path, withContents: Boolean) {
        if (!settings.botConfigured) return
        token = settings.botToken
        this.settings = settings
        val client = DiscordRest(token).also { rest = it }
        if (settings.statusChannelId.isNotBlank()) status = DiscordStatusMessage(token, settings.statusChannelId, statusFile)
        if (settings.newsChannelId.isNotBlank()) DiscordNews.register(client, settings.newsChannelId, restWorker)
        DiscordCommands.registerBuiltIns()
        if (withContents) MccDiscordCommands.register()
        if (settings.verifiedRoleId.isNotBlank()) {
            DiscordLinks.registerDiscord(settings)
            DiscordRankRoles.register(settings)
        }
        if (settings.adminChannelId.isNotBlank()) {
            DiscordAdminCommands.register()
            if (withContents) MccDiscordCommands.registerAdmin()
        }
        ServerLifecycleEvents.SERVER_STARTED.register { started ->
            server = started
            // Reads every mod's Korean text now, off the server thread, rather than on the first command.
            restWorker.execute(guarded { KoreanText.entries() })
            players = started.playerCount
            onWorker { start() }
            showStatus(open = true)
            statusRefresh = restWorker.scheduleAtFixedRate(guarded { showStatus(open = true) },
                STATUS_REFRESH_MINUTES, STATUS_REFRESH_MINUTES, TimeUnit.MINUTES)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            statusRefresh?.cancel(false)
            // Waits for "closed" to land, since nothing can say it once the server is gone; a slow Discord only
            // delays the shutdown, never stops it.
            try {
                showStatus(open = false).get(10, TimeUnit.SECONDS)
                worker.submit { stop() }.get(5, TimeUnit.SECONDS)
            } catch (failure: Exception) {
                JbroPolicy.LOGGER.warn("Discord bot did not finish closing: {}", failure.toString())
            }
            server = null
        }
        // The player list changes after these events, so count on the next tick.
        ServerPlayConnectionEvents.JOIN.register { _, _, joined -> recount(joined) }
        ServerPlayConnectionEvents.DISCONNECT.register { _, left -> recount(left) }
    }

    private fun recount(server: MinecraftServer) = server.execute {
        players = server.playerCount
        scheduleStatus()
    }

    /** Shows the server open or closed in the status channel, when there is one; failures are logged, not thrown. */
    private fun showStatus(open: Boolean): java.util.concurrent.Future<*> = restWorker.submit {
        val message = status ?: return@submit
        try {
            message.show(open, players)
        } catch (failure: Exception) {
            JbroPolicy.LOGGER.warn("Could not update the Discord status message: {}", failure.toString())
        }
    }

    /** At most one status edit per debounce window, so a rush of joins does not hit Discord's rate limit. */
    private fun scheduleStatus() {
        if (status == null || statusPending?.isDone == false) return
        statusPending = restWorker.schedule(guarded { showStatus(open = true) }, STATUS_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
    }

    private fun start() {
        running = true
        retryDelaySeconds = 0
        connect()
    }

    private fun stop() {
        running = false
        heartbeat?.cancel(false)
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping")?.orTimeout(3, TimeUnit.SECONDS)
        socket = null
        session = null
    }

    private fun connect() {
        if (!running) return
        val fresh = DiscordGatewaySession(token)
        session = fresh
        http.newWebSocketBuilder().header("User-Agent", USER_AGENT).buildAsync(URI.create(GATEWAY), Listener(fresh))
            .whenComplete { ws, failure ->
                onWorker {
                    if (failure != null) {
                        JbroPolicy.LOGGER.warn("Discord bot could not connect: {}", failure.toString())
                        if (session === fresh) retry()
                    } else if (session !== fresh) ws.abort()
                }
            }
    }

    /** Drops the connection and connects again after the back-off. */
    private fun retry() {
        heartbeat?.cancel(false)
        socket?.abort()
        socket = null
        session = null
        if (!running) return
        retryDelaySeconds = (retryDelaySeconds * 2).coerceIn(5, 300)
        worker.schedule(guarded { connect() }, retryDelaySeconds, TimeUnit.SECONDS)
    }

    private fun act(owner: DiscordGatewaySession, actions: List<DiscordGatewaySession.Action>) {
        if (session !== owner) return
        for (action in actions) when (action) {
            is DiscordGatewaySession.Action.Send -> send(action.payload)
            is DiscordGatewaySession.Action.Heartbeat -> {
                heartbeat?.cancel(false)
                val interval = action.intervalMillis
                heartbeat = worker.scheduleAtFixedRate(guarded { if (session === owner) act(owner, listOf(owner.heartbeat())) },
                    (interval * Math.random()).toLong(), interval, TimeUnit.MILLISECONDS)
            }
            is DiscordGatewaySession.Action.Ready -> {
                retryDelaySeconds = 0
                JbroPolicy.LOGGER.info("Discord bot {} is online", action.name)
                guilds = action.guildIds
                restWorker.execute(guarded { registerCommands(action.applicationId, action.guildIds) })
                // Whatever ranks changed while the bot was away.
                server?.let { live -> live.execute { DiscordRankRoles.refreshAll(live) } }
            }
            is DiscordGatewaySession.Action.Interaction -> restWorker.execute(guarded { answer(action.data) })
            DiscordGatewaySession.Action.Reconnect -> { retry(); return }
            is DiscordGatewaySession.Action.Stop -> {
                JbroPolicy.LOGGER.error("Discord bot stopped: {}. Check botToken in config/jbro-policy-discord.json", action.reason)
                running = false
                retry()
                return
            }
        }
    }

    /** Puts the slash commands on every server the bot is in; per server, so they show up at once. */
    private fun registerCommands(applicationId: String, guildIds: List<String>) {
        val client = rest ?: return
        val definitions = DiscordCommands.definitions()
        for (guild in guildIds) {
            val response = client.request("PUT", "/applications/$applicationId/guilds/$guild/commands", definitions)
            if (response.ok) JbroPolicy.LOGGER.info("Discord bot registered {} slash commands", definitions.size())
            else JbroPolicy.LOGGER.warn("Discord refused the slash commands ({}): {}", response.status, response.body.take(200))
        }
    }

    /** Answers one slash command: acknowledges at once, works out the reply on the server thread, then edits it in. */
    private fun answer(interaction: JsonObject) {
        val client = rest ?: return
        val id = interaction.get("id").asString
        val interactionToken = interaction.get("token").asString
        if (interaction.get("type")?.asInt == MESSAGE_COMPONENT) return press(client, interaction, id, interactionToken)
        if (interaction.get("type")?.asInt != APPLICATION_COMMAND) return
        val applicationId = interaction.get("application_id").asString
        val data = interaction.getAsJsonObject("data")
        val options = data.getAsJsonArray("options")?.associate { option ->
            option.asJsonObject.get("name").asString to option.asJsonObject.get("value").asString
        }.orEmpty()
        val command = DiscordCommands.find(data.get("name").asString)
        val caller = caller(interaction)
        val misplaced = command?.let { DiscordAdminAccess.channelRefusal(settings, it, caller.channelId) }
        client.request("POST", "/interactions/$id/$interactionToken/callback", JsonObject().apply {
            addProperty("type", DEFERRED_REPLY)
            // A command in the wrong channel is told so to the caller alone.
            if (command?.ephemeral == true || misplaced != null) add("data", JsonObject().apply { addProperty("flags", EPHEMERAL) })
        }, authorized = false)
        val live = server
        val refusal = (command as? DiscordAdminCommand)?.let { DiscordAdminAccess.check(settings, caller, it.name) }
            as? DiscordAdminAccess.Verdict.Refused
        if (command is DiscordAdminCommand) {
            JbroPolicy.LOGGER.info("Discord {} /{} {} by {}", if (refusal == null) "ran" else "refused", command.name, options, caller)
        }
        val reply = when {
            command == null -> DiscordRest.message("모르는 명령이에요.")
            misplaced != null -> DiscordRest.message(misplaced)
            refusal != null -> DiscordRest.message(refusal.reason)
            live == null -> DiscordRest.message("서버가 아직 켜지는 중이에요. 잠시 뒤에 다시 해 주세요.")
            else -> try {
                live.submit(Supplier {
                    if (command is DiscordAdminCommand) command.run(live, options, caller) else command.reply(live, options, caller)
                }).get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (failure: Exception) {
                JbroPolicy.LOGGER.warn("Discord command /{} failed", command.name, failure)
                DiscordRest.message("명령을 처리하지 못했어요. 잠시 뒤에 다시 해 주세요.")
            }
        }
        val edited = client.request("PATCH", "/webhooks/$applicationId/$interactionToken/messages/@original", reply, authorized = false)
        if (edited.ok) return
        JbroPolicy.LOGGER.warn("Discord refused a command reply ({}): {}", edited.status, edited.body.take(500))
        // Without a reply the command stays on "thinking" for good, so say something plain instead.
        client.request("PATCH", "/webhooks/$applicationId/$interactionToken/messages/@original",
            DiscordRest.message("답을 만들지 못했어요. 운영진에게 알려 주세요."), authorized = false)
    }

    /** Answers a button press; the only buttons are the inquiry reviews' "처리 완료". */
    private fun press(client: DiscordRest, interaction: JsonObject, id: String, interactionToken: String) {
        val customId = interaction.getAsJsonObject("data")?.get("custom_id")?.asString.orEmpty()
        if (!customId.startsWith(InquiryReview.BUTTON_PREFIX)) return
        val answer = InquiryReview.press(customId, caller(interaction), interaction.getAsJsonObject("message"))
        val response = client.request("POST", "/interactions/$id/$interactionToken/callback", answer, authorized = false)
        if (!response.ok) JbroPolicy.LOGGER.warn("Discord refused a button answer ({}): {}", response.status, response.body.take(300))
    }

    /** The member in a server channel; a direct message carries the user alone. */
    private fun caller(interaction: JsonObject): DiscordCaller {
        val member = interaction.getAsJsonObject("member")
        val user = member?.getAsJsonObject("user") ?: interaction.getAsJsonObject("user")
        return DiscordCaller(
            user?.get("id")?.asString.orEmpty(),
            user?.get("username")?.asString.orEmpty(),
            member?.getAsJsonArray("roles")?.map { it.asString }.orEmpty(),
            interaction.get("channel_id")?.asString.orEmpty(),
            interaction.get("guild_id")?.asString.orEmpty(),
        )
    }

    private const val APPLICATION_COMMAND = 2
    private const val MESSAGE_COMPONENT = 3
    private const val DEFERRED_REPLY = 5
    private const val EPHEMERAL = 64

    /** Runs [task] with the bot's REST client off the server thread, after the reply in hand; dropped without a bot. */
    fun later(task: (DiscordRest) -> Unit) {
        val client = rest ?: return
        restWorker.execute(guarded { task(client) })
    }

    private fun send(payload: JsonObject) {
        val open = socket ?: return JbroPolicy.LOGGER.warn("Discord bot had no open connection to send on")
        try {
            open.sendText(payload.toString(), true).get(10, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            JbroPolicy.LOGGER.warn("Discord bot could not send: {}", failure.toString())
            retry()
        }
    }

    private class Listener(private val owner: DiscordGatewaySession) : WebSocket.Listener {
        private val text = StringBuilder()

        // Queued on the worker before any message, so the socket is kept by the time Hello asks for the login.
        override fun onOpen(webSocket: WebSocket) {
            onWorker { if (session === owner) socket = webSocket else webSocket.abort() }
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val message = text.toString()
                text.setLength(0)
                onWorker { act(owner, owner.onMessage(message)) }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            onWorker {
                if (session !== owner) return@onWorker
                JbroPolicy.LOGGER.info("Discord bot disconnected ({} {})", statusCode, reason)
                act(owner, listOf(owner.onClose(statusCode)))
            }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            onWorker {
                if (session !== owner) return@onWorker
                JbroPolicy.LOGGER.warn("Discord bot connection failed: {}", error.toString())
                retry()
            }
        }
    }
}
