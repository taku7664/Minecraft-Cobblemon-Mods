package jbro.cobblemon.policy.support

import com.cobblemon.mod.common.api.events.CobblemonEvents
import java.util.concurrent.ExecutorService
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.api.Announcements
import jbro.cobblemon.policy.legend.LegendPolicy

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
            val species = event.pokemon.species
            // A Legend's own name tells Galarian Zapdos from Zapdos.
            val legend = LegendPolicy.legendOf(event.pokemon)
            val name = legend?.let { KoreanText.translate(it.nameKey) }
                ?: KoreanText.translate("${species.resourceIdentifier.namespace}.species.${species.resourceIdentifier.path}.name")
                ?: species.name
            catchNews(event.player.gameProfile.name, name, legend = legend != null, shiny = event.pokemon.shiny)
                ?.let(::post)
        }
        Announcements.onBroadcast { _, message -> post(DiscordText.say("newsNotice", "message" to KoreanText.render(message))) }
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
        legend && shiny -> DiscordText.say("newsShinyLegend", "player" to player, "species" to species)
        legend -> DiscordText.say("newsLegend", "player" to player, "species" to species)
        shiny -> DiscordText.say("newsShiny", "player" to player, "species" to species)
        else -> null
    }

    /** The emoji in front of a More Cobblemon Contents news kind. */
    fun emoji(kind: String): String = when (kind.substringAfter(':')) {
        "champion", "hard_champion" -> DiscordText.say("newsIconChampion")
        "win_streak" -> DiscordText.say("newsIconStreak")
        "highest_floor" -> DiscordText.say("newsIconFloor")
        else -> DiscordText.say("newsIconOther")
    }
}
