package jbro.cobblemon.mcc.api.news

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/**
 * Something worth telling everyone, such as a new Champion or a win-streak milestone.
 *
 * @property kind what happened, as a namespaced ID (`more_cobblemon_contents_league_challenge:champion`), for
 *   listeners that treat some kinds differently.
 * @property playerId who it happened to, when it was one player.
 * @property message the sentence to show, translatable so each listener can render it in its own language.
 */
data class MccNewsEvent(val kind: String, val playerId: UUID?, val message: Component)

/**
 * Server news from every content, for whoever wants to pass it on: a Discord bot, a chat broadcast. Contents
 * [publish]; listeners subscribe with [listen]. Both run on the server thread.
 */
object MccNews {
    fun interface Listener {
        fun news(server: MinecraftServer, event: MccNewsEvent)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun listen(listener: Listener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun publish(server: MinecraftServer, event: MccNewsEvent) {
        listeners.forEach { listener ->
            try {
                listener.news(server, event)
            } catch (failure: RuntimeException) {
                MoreCobblemonContents.LOGGER.warn("News listener {} failed for {}", listener, event.kind, failure)
            }
        }
    }

    /** The name [playerId] plays under, for news sentences. */
    fun playerName(server: MinecraftServer, playerId: UUID): String =
        server.playerList.getPlayer(playerId)?.gameProfile?.name
            ?: server.profileCache?.get(playerId)?.orElse(null)?.name
            ?: playerId.toString().take(8)
}
