package jbro.cobblemon.mcc.client.wiki

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.wiki.WikiFiles
import jbro.cobblemon.mcc.internal.wiki.WikiLocalPayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader

/**
 * Serves the wiki copy installed with the client (`config/more-cobblemon-contents/wiki/`) on this machine only, so
 * the pages load from the player's own disk and the game server answers just their data. On joining a server it
 * names the port, and the server's `/wiki` links then open `http://localhost:<port>` with the server's address.
 * Without an installed copy it stays off and links open the server's own wiki.
 */
internal object LocalWikiServer {
    @Volatile
    private var port: Int? = null

    fun register() {
        ClientLifecycleEvents.CLIENT_STARTED.register { start() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { stop() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            val served = port ?: return@register
            if (ClientPlayNetworking.canSend(WikiLocalPayload.TYPE)) ClientPlayNetworking.send(WikiLocalPayload(served))
        }
    }

    private var http: HttpServer? = null

    private fun start() {
        val root = FabricLoader.getInstance().configDir.resolve("more-cobblemon-contents").resolve("wiki").toAbsolutePath().normalize()
        if (!Files.isRegularFile(root.resolve("index.html"))) return
        for (candidate in WikiLocalPayload.PORTS) {
            val server = try {
                HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), 0)
            } catch (busy: java.io.IOException) {
                continue
            }
            server.createContext("/") { exchange ->
                val file = if (exchange.requestMethod == "GET" || exchange.requestMethod == "HEAD") {
                    WikiFiles.read(root, exchange.requestURI.rawPath)
                } else WikiFiles.text(405, "method not allowed")
                exchange.responseHeaders.add("Content-Type", file.type)
                exchange.responseHeaders.add("Cache-Control", if (file.cache) "public, max-age=3600" else "no-cache")
                exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
                exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
                val head = exchange.requestMethod == "HEAD"
                exchange.sendResponseHeaders(file.status, if (head) -1 else file.body.size.toLong())
                if (!head) exchange.responseBody.use { it.write(file.body) }
                exchange.close()
            }
            server.executor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "mcc-local-wiki").apply { isDaemon = true } }
            server.start()
            http = server
            port = candidate
            MoreCobblemonContents.LOGGER.info("Local wiki serving {} on http://localhost:{}", root, candidate)
            return
        }
        MoreCobblemonContents.LOGGER.warn("Local wiki found no free port in {}; wiki links open the server's wiki", WikiLocalPayload.PORTS)
    }

    private fun stop() {
        http?.stop(0)
        http = null
        port = null
    }
}
