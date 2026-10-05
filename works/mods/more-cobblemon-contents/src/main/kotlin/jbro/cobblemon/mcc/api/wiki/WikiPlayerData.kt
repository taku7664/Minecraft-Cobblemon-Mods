package jbro.cobblemon.mcc.api.wiki

import com.google.gson.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.server.MinecraftServer

/**
 * What the server wiki's `/api/me` answers about a player, beyond the core's BP and records. A content registers a
 * section under its own [key] (for example its mod ID) and the wiki page reads `me.sections[key]`. Sections are
 * built on the server thread, so they may read saved data directly; a section that throws is left out.
 */
object WikiPlayerData {
    fun interface Section {
        fun build(server: MinecraftServer, playerId: UUID): JsonElement?
    }

    private val sections = ConcurrentHashMap<String, Section>()

    fun register(key: String, section: Section) {
        require(key.matches(Regex("[a-z0-9_.-]+"))) { "Invalid wiki section key: $key" }
        sections[key] = section
    }

    fun all(): Map<String, Section> = sections.toMap()
}

/** A request to a content's wiki endpoint: its query parameters and, when it carried a valid token, who asked. */
class WikiApiRequest(val server: MinecraftServer, val query: Map<String, String>, val viewer: UUID?)

/**
 * Answers one wiki endpoint. Handlers run on the wiki's HTTP threads, not the server thread: read only what is
 * safe to read there (for example a content's own database), or hand work to `server.submit`. Throw
 * [IllegalArgumentException] for a bad request.
 */
fun interface WikiApiHandler {
    fun handle(request: WikiApiRequest): JsonElement
}

/** Endpoints contents add to the server wiki under `/api/<name>`, for example `pvp/matches`. */
object WikiApi {
    private val handlers = ConcurrentHashMap<String, WikiApiHandler>()

    fun register(name: String, handler: WikiApiHandler): AutoCloseable {
        require(name.matches(Regex("[a-z0-9_-]+(/[a-z0-9_-]+)*")) && name != "me") { "Invalid wiki endpoint: $name" }
        handlers[name] = handler
        return AutoCloseable { handlers.remove(name, handler) }
    }

    fun handler(name: String): WikiApiHandler? = handlers[name]

    /** The address players open the wiki at, or null while the wiki is off. */
    fun publicUrl(): String? = jbro.cobblemon.mcc.internal.wiki.WikiServer.takeIf { it.running }?.sharedBase()

    /**
     * [playerId]'s own wiki link, carrying the token that shows the wiki their data, or null while the wiki is off.
     * The core gives players no command for it; the server's own mod hands links out.
     */
    fun linkFor(playerId: UUID): String? = jbro.cobblemon.mcc.internal.wiki.WikiServer.linkFor(playerId)

    /** A new link for [playerId] that ends every link they had before, or null while the wiki is off. */
    fun resetLinkFor(playerId: UUID): String? = jbro.cobblemon.mcc.internal.wiki.WikiServer.resetLinkFor(playerId)

    /**
     * [playerId]'s own wiki link at the shared address ([publicUrl]) rather than the one they joined at or their
     * client's local copy, for links opened away from the game such as on Discord. Null while the wiki is off.
     */
    fun sharedLinkFor(playerId: UUID): String? = jbro.cobblemon.mcc.internal.wiki.WikiServer.sharedLinkFor(playerId)
}
