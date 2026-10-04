package jbro.cobblemon.mcc.internal.wiki

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.wiki.WikiApi
import jbro.cobblemon.mcc.api.wiki.WikiApiRequest
import jbro.cobblemon.mcc.api.wiki.WikiPlayerData
import jbro.cobblemon.mcc.internal.bp.BattlePointService
import jbro.cobblemon.mcc.internal.hub.BattleHubRecordView
import jbro.cobblemon.mcc.internal.record.BattleRecordService
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource

/**
 * The server wiki over HTTP: the wiki's files from [WikiConfig.directory], and `/api/me`, the asking player's live
 * dashboard (BP, records and content sections), so a refresh always shows the latest. The player is known by the
 * token their wiki link carries (see [jbro.cobblemon.mcc.api.wiki.WikiApi.linkFor]). Runs only while the Minecraft server does, and only when enabled.
 */
internal object WikiServer {
    @Volatile
    var config: WikiConfig = WikiConfig()
        private set

    @Volatile
    var tokens: WikiTokens? = null
        private set

    private var http: HttpServer? = null
    private var executor: java.util.concurrent.ExecutorService? = null

    val running: Boolean get() = http != null

    /** The port each player's own client serves its copy of the wiki on, as it reported on joining. */
    private val localPorts = ConcurrentHashMap<UUID, Int>()

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register(::start)
        ServerLifecycleEvents.SERVER_STOPPING.register { stop() }
        PayloadTypeRegistry.playC2S().register(WikiLocalPayload.TYPE, WikiLocalPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(WikiLocalPayload.TYPE) { payload, context ->
            if (payload.port in WikiLocalPayload.PORTS) localPorts[context.player().uuid] = payload.port
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> localPorts.remove(handler.player.uuid) }
    }

    @Volatile
    private var server: MinecraftServer? = null

    fun linkFor(playerId: UUID): String? = tokens?.let { link(playerId, it.tokenFor(playerId)) }

    fun resetLinkFor(playerId: UUID): String? = tokens?.let { link(playerId, it.reset(playerId)) }

    /**
     * The player's own copy of the wiki on localhost when their client serves one, reading their data from this
     * server ([baseFor]) through `?s=`; else this server's pages.
     */
    internal fun link(playerId: UUID, token: String, localPort: Int? = localPorts[playerId], server: String = baseFor(playerId)): String =
        if (localPort == null) "$server/?t=$token"
        else "http://localhost:$localPort/?t=$token&s=${URLEncoder.encode(server, StandardCharsets.UTF_8)}"

    /** Where anyone opens the wiki: `public_url` when set, else the address the latest player from elsewhere joined at. */
    fun sharedBase(): String =
        if (config.publicUrl.isNotBlank()) config.base else WikiPortSharing.lastPublicBase ?: config.base

    /**
     * Where [playerId] reaches this server's wiki: `public_url` when set, else the address they typed to join, whose
     * game port serves the wiki too ([WikiPortSharing]), else this machine's own wiki port.
     */
    private fun baseFor(playerId: UUID): String {
        if (config.publicUrl.isNotBlank()) return config.base
        val connection = server?.playerList?.getPlayer(playerId)
            ?.let { (it.connection as jbro.cobblemon.mcc.internal.mixin.ServerCommonPacketListenerImplAccessor).`mcc$connection`() }
        return connection?.let(WikiPortSharing::baseFor) ?: config.base
    }

    private fun start(server: MinecraftServer) {
        this.server = server
        config = WikiConfig.load()
        if (!config.enabled) return
        val root = server.serverDirectory.resolve(config.directory).toAbsolutePath().normalize()
        tokens = WikiTokens(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("mcc_wiki_tokens.json")).also(WikiTokens::load)
        try {
            val pool = Executors.newFixedThreadPool(THREADS) { runnable -> Thread(runnable, "mcc-wiki-http").apply { isDaemon = true } }
            http = HttpServer.create(InetSocketAddress(config.bind, config.port), 0).apply {
                createContext("/api/me") { exchange -> respond(exchange) { me(server, exchange) } }
                createContext("/api/") { exchange -> respond(exchange) { content(server, exchange) } }
                createContext("/") { exchange -> respond(exchange) { file(root, exchange) } }
                setExecutor(pool)
                start()
            }
            executor = pool
            if (Files.notExists(root.resolve("index.html"))) {
                MoreCobblemonContents.LOGGER.warn("Wiki directory {} has no index.html; copy the server wiki there", root)
            }
            MoreCobblemonContents.LOGGER.info("Wiki serving {} on {}:{} for {}", root, config.bind, config.port,
                if (config.publicUrl.isBlank()) "the game port at each player's own address" else config.base)
        } catch (failure: Exception) {
            MoreCobblemonContents.LOGGER.error("Wiki could not listen on {}:{}", config.bind, config.port, failure)
            stop()
        }
    }

    private fun stop() {
        server = null
        http?.stop(0)
        http = null
        executor?.shutdownNow()
        executor = null
        tokens = null
    }

    private class Reply(val status: Int, val type: String, val body: ByteArray, val cache: Boolean = false)

    private fun respond(exchange: HttpExchange, handle: () -> Reply) {
        // A player's local copy of the wiki asks for their data from another origin; the token in a header, not a
        // cookie, is what names them, so any origin may ask.
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        if (exchange.requestMethod == "OPTIONS") {
            exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, HEAD")
            exchange.responseHeaders.add("Access-Control-Allow-Headers", "X-MCC-Wiki-Token")
            exchange.responseHeaders.add("Access-Control-Max-Age", "600")
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
            return
        }
        val reply = try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") text(405, "method not allowed") else handle()
        } catch (failure: Exception) {
            MoreCobblemonContents.LOGGER.warn("Wiki request {} failed", exchange.requestURI.path, failure)
            text(500, "server error")
        }
        exchange.responseHeaders.add("Content-Type", reply.type)
        exchange.responseHeaders.add("Cache-Control", if (reply.cache) "public, max-age=3600" else "no-store")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
        val head = exchange.requestMethod == "HEAD"
        exchange.sendResponseHeaders(reply.status, if (head) -1 else reply.body.size.toLong())
        if (!head) exchange.responseBody.use { it.write(reply.body) }
        exchange.close()
    }

    private fun text(status: Int, message: String) = Reply(status, "text/plain; charset=utf-8", message.toByteArray())

    private fun json(status: Int, body: JsonObject) = Reply(status, "application/json; charset=utf-8", Gson().toJson(body).toByteArray())

    /** The player the request's token names, from the `X-MCC-Wiki-Token` header or `?t=`. */
    private fun viewer(exchange: HttpExchange): UUID? =
        (exchange.requestHeaders.getFirst("X-MCC-Wiki-Token") ?: query(exchange)["t"])?.let { tokens?.playerFor(it) }

    /** A content's endpoint from [WikiApi], answered on this HTTP thread. */
    private fun content(server: MinecraftServer, exchange: HttpExchange): Reply {
        val name = exchange.requestURI.path.removePrefix("/api/").trimEnd('/')
        val handler = WikiApi.handler(name) ?: return json(404, JsonObject().apply { addProperty("error", "unknown_endpoint") })
        return try {
            val body = handler.handle(WikiApiRequest(server, query(exchange), viewer(exchange)))
            Reply(200, "application/json; charset=utf-8", Gson().toJson(body).toByteArray())
        } catch (failure: IllegalArgumentException) {
            json(400, JsonObject().apply { addProperty("error", failure.message ?: "bad_request") })
        }
    }

    /** The asking player's dashboard, read on the server thread. */
    private fun me(server: MinecraftServer, exchange: HttpExchange): Reply {
        val playerId = viewer(exchange) ?: return json(401, JsonObject().apply { addProperty("error", "unknown_token") })
        val body = server.submit(Supplier { dashboard(server, playerId) }).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return json(200, body)
    }

    internal fun dashboard(server: MinecraftServer, playerId: UUID): JsonObject = JsonObject().apply {
        add("player", JsonObject().apply {
            addProperty("uuid", playerId.toString())
            addProperty("name", server.playerList.getPlayer(playerId)?.gameProfile?.name
                ?: server.profileCache?.get(playerId)?.orElse(null)?.name ?: playerId.toString())
            addProperty("online", server.playerList.getPlayer(playerId) != null)
        })
        addProperty("bp", if (BattlePointService.isAvailable(server)) BattlePointService.balance(server, playerId) else 0L)
        add("records", JsonArray().also { records ->
            if (!BattleRecordService.isAvailable(server)) return@also
            BattleRecordService.forPlayer(server, playerId)
                .sortedWith(compareBy({ it.key.category.contentId }, { it.key.category.formatId }))
                .map(BattleHubRecordView::from)
                .forEach { view ->
                    records.add(JsonObject().apply {
                        addProperty("content", view.contentId)
                        addProperty("format", view.formatId)
                        addProperty("wins", view.wins)
                        addProperty("losses", view.losses)
                        addProperty("current_streak", view.currentStreak)
                        addProperty("best_streak", view.bestStreak)
                        add("metrics", JsonObject().also { metrics -> view.bestMetrics.forEach(metrics::addProperty) })
                    })
                }
        })
        add("sections", JsonObject().also { sections ->
            WikiPlayerData.all().forEach { (key, section) ->
                try {
                    section.build(server, playerId)?.let { sections.add(key, it) }
                } catch (failure: RuntimeException) {
                    MoreCobblemonContents.LOGGER.warn("Wiki section {} failed for {}", key, playerId, failure)
                }
            }
        })
        addProperty("updated", System.currentTimeMillis())
    }

    private fun file(root: Path, exchange: HttpExchange): Reply =
        WikiFiles.read(root, exchange.requestURI.rawPath).let { Reply(it.status, it.type, it.body, it.cache) }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate { pair ->
            val (key, value) = pair.split('=', limit = 2)
            URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

    private const val THREADS = 4
    private const val REQUEST_TIMEOUT_SECONDS = 5L
}
