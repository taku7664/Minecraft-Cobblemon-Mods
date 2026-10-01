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
import jbro.cobblemon.policy.JbroPolicy
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer

/**
 * One Discord gateway connection, as the messages it answers: Hello starts the heartbeat and the login, a missed
 * heartbeat acknowledgement or a reconnect request starts over, and a refused token stops for good. No socket here,
 * so tests drive it with plain messages.
 */
internal class DiscordGatewaySession(private val token: String, private val presence: () -> JsonObject) {
    sealed interface Action {
        data class Send(val payload: JsonObject) : Action
        data class Heartbeat(val intervalMillis: Long) : Action
        data object Reconnect : Action
        data class Stop(val reason: String) : Action
        data class Ready(val name: String) : Action
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
            DISPATCH -> if (message.get("t")?.asString == "READY") {
                val user = message.getAsJsonObject("d").getAsJsonObject("user")
                listOf(Action.Ready(user.get("username").asString))
            } else emptyList()
            else -> emptyList()
        }
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

    fun presenceUpdate(): JsonObject = JsonObject().apply { addProperty("op", PRESENCE_UPDATE); add("d", presence()) }

    private fun heartbeatPayload() = JsonObject().apply {
        addProperty("op", HEARTBEAT)
        add("d", sequence?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }

    private fun identify() = JsonObject().apply {
        addProperty("op", IDENTIFY)
        add("d", JsonObject().apply {
            addProperty("token", token)
            // No events are needed: the bot only shows it is online and posts by REST.
            addProperty("intents", 0)
            add("properties", JsonObject().apply {
                addProperty("os", System.getProperty("os.name").orEmpty().lowercase())
                addProperty("browser", "jbro-policy")
                addProperty("device", "jbro-policy")
            })
            add("presence", presence())
        })
    }

    companion object {
        const val DISPATCH = 0
        const val HEARTBEAT = 1
        const val IDENTIFY = 2
        const val PRESENCE_UPDATE = 3
        const val RECONNECT = 7
        const val INVALID_SESSION = 9
        const val HELLO = 10
        const val HEARTBEAT_ACK = 11

        /** Online, with a custom status naming how many players are on. */
        fun presence(players: Int): JsonObject = JsonObject().apply {
            add("since", JsonNull.INSTANCE)
            add("activities", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "Custom Status")
                    addProperty("type", 4)
                    addProperty("state", statusText(players))
                })
            })
            addProperty("status", "online")
            addProperty("afk", false)
        }

        fun statusText(players: Int) = if (players > 0) "서버가 열려있어요!!! (${players}명 접속 중)" else "서버가 열려있어요!!!"
    }
}

/**
 * The server's Discord bot: online from server start to stop, its status showing how many players are on, and the
 * poster of inquiries when it has a channel. It reconnects by itself after a dropped connection, waiting longer each
 * time up to five minutes, and gives up only when Discord refuses the token.
 */
internal object DiscordBot {
    const val USER_AGENT = "DiscordBot (https://github.com/taku7664/Minecraft-Cobblemon-Mods, 1.0)"
    private const val GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
    private const val PRESENCE_DEBOUNCE_SECONDS = 15L

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    // Everything about the connection happens on this one thread, so sends never overlap.
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "jbro-policy-discord").apply { isDaemon = true } }

    private var token = ""
    @Volatile private var running = false
    @Volatile private var players = 0
    private var socket: WebSocket? = null
    private var session: DiscordGatewaySession? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var presencePending: ScheduledFuture<*>? = null
    private var retryDelaySeconds = 0L

    /** Runs [task] on the worker; a failure is logged, where a scheduled executor would otherwise drop it silently. */
    private fun onWorker(task: () -> Unit) = worker.execute(guarded(task))

    private fun guarded(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (failure: Exception) {
            JbroPolicy.LOGGER.warn("Discord bot task failed", failure)
        }
    }

    fun register(settings: DiscordSettings) {
        if (!settings.botConfigured) return
        token = settings.botToken
        ServerLifecycleEvents.SERVER_STARTED.register { server -> players = server.playerCount; onWorker { start() } }
        ServerLifecycleEvents.SERVER_STOPPING.register { worker.submit { stop() }.get(5, TimeUnit.SECONDS) }
        // The player list changes after these events, so count on the next tick.
        ServerPlayConnectionEvents.JOIN.register { _, _, server -> recount(server) }
        ServerPlayConnectionEvents.DISCONNECT.register { _, server -> recount(server) }
    }

    private fun recount(server: MinecraftServer) = server.execute {
        players = server.playerCount
        onWorker { schedulePresence() }
    }

    private fun start() {
        running = true
        retryDelaySeconds = 0
        connect()
    }

    private fun stop() {
        running = false
        heartbeat?.cancel(false)
        presencePending?.cancel(false)
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping")?.orTimeout(3, TimeUnit.SECONDS)
        socket = null
        session = null
    }

    private fun connect() {
        if (!running) return
        val fresh = DiscordGatewaySession(token) { DiscordGatewaySession.presence(players) }
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
            }
            DiscordGatewaySession.Action.Reconnect -> { retry(); return }
            is DiscordGatewaySession.Action.Stop -> {
                JbroPolicy.LOGGER.error("Discord bot stopped: {}. Check botToken in config/jbro-policy-discord.json", action.reason)
                running = false
                retry()
                return
            }
        }
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

    /** At most one status change per debounce window, so a rush of joins does not hit Discord's rate limit. */
    private fun schedulePresence() {
        if (!running || presencePending?.isDone == false) return
        presencePending = worker.schedule(guarded {
            session?.let { send(it.presenceUpdate()) }
        }, PRESENCE_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
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
