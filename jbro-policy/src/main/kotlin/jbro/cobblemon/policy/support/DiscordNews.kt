package jbro.cobblemon.policy.support

import com.cobblemon.mod.common.api.events.CobblemonEvents
import java.util.concurrent.ExecutorService
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.api.Announcements
import jbro.cobblemon.policy.legend.LegendCatalog

/**
 * Server news posted to the news channel: More Cobblemon Contents' news (Champions, records), Legend and shiny
 * catches, and server notices. Posting happens off the server thread and never holds it up.
 */
internal object DiscordNews {
    private var rest: DiscordRest? = null
    private var channelId = ""
    private var executor: ExecutorService? = null

    fun register(rest: DiscordRest, channelId: String, executor: ExecutorService) {
        this.rest = rest
        this.channelId = channelId
        this.executor = executor
        CobblemonEvents.POKEMON_CAPTURED.subscribe { event ->
            catchNews(event.player.gameProfile.name, KoreanText.render(event.pokemon.species.translatedName),
                legend = LegendCatalog[event.pokemon.species.resourceIdentifier.path] != null, shiny = event.pokemon.shiny)
                ?.let(::post)
        }
        Announcements.onBroadcast { _, message -> post("📢 [공지] " + KoreanText.render(message)) }
    }

    /** Posts [text] to the news channel, when there is one. */
    fun post(text: String) {
        val client = rest ?: return
        val pool = executor ?: return
        pool.execute {
            try {
                val response = client.post(channelId, text)
                if (!response.ok) JbroPolicy.LOGGER.warn("Discord refused a news post ({}): {}", response.status, response.body.take(200))
            } catch (failure: Exception) {
                JbroPolicy.LOGGER.warn("Could not post news to Discord: {}", failure.toString())
            }
        }
    }

    /** The news line for a catch, or null for an everyday Pokémon. */
    internal fun catchNews(player: String, species: String, legend: Boolean, shiny: Boolean): String? = when {
        legend && shiny -> "🌟 ${player}님이 이로치 전설의 포켓몬 ${species}을(를) 잡았습니다!"
        legend -> "✨ ${player}님이 전설의 포켓몬 ${species}을(를) 잡았습니다!"
        shiny -> "🌟 ${player}님이 이로치 ${species}을(를) 잡았습니다!"
        else -> null
    }

    /** The emoji in front of a More Cobblemon Contents news kind. */
    fun emoji(kind: String): String = when (kind.substringAfter(':')) {
        "champion", "hard_champion" -> "🏆"
        "win_streak" -> "🔥"
        "highest_floor" -> "🏭"
        else -> "📣"
    }
}
