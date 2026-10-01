package jbro.cobblemon.mcc.internal.pvp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID
import jbro.cobblemon.mcc.api.wiki.WikiApi

/**
 * `/api/pvp/matches` on the server wiki: every recorded PvP battle, newest first, a page at a time. With
 * `player=<name>` (or `player=me` and the viewer's token) only that player's battles, plus their record against
 * each opponent. `before=<id>` continues from the last page's `next`.
 */
internal object PvpWikiApi {
    private const val PAGE = 50

    fun register() {
        WikiApi.register("pvp/matches") { request ->
            val store = PvpMatchHistory.store ?: throw IllegalArgumentException("history_unavailable")
            val asked = request.query["player"]?.trim()?.takeIf { it.isNotEmpty() }
            val player: UUID? = when {
                asked == null -> null
                asked == "me" -> request.viewer ?: throw IllegalArgumentException("no_token")
                else -> runCatching { UUID.fromString(asked) }.getOrNull() ?: store.playerNamed(asked)
                    ?: return@register JsonObject().apply {
                        add("matches", JsonArray())
                        addProperty("unknown_player", asked)
                    }
            }
            val before = request.query["before"]?.toLongOrNull()
            val limit = request.query["limit"]?.toIntOrNull()?.coerceIn(1, PvpMatchStore.MAX_PAGE) ?: PAGE
            val matches = store.matches(player, limit + 1, before)
            JsonObject().apply {
                add("matches", JsonArray().also { list -> matches.take(limit).forEach { list.add(json(it)) } })
                if (matches.size > limit) addProperty("next", matches[limit - 1].id)
                if (player != null) add("player", summary(store, player))
            }
        }
    }

    private fun json(match: PvpMatch) = JsonObject().apply {
        addProperty("id", match.id)
        addProperty("at", match.playedAt)
        addProperty("format", match.format)
        add("winner", person(match.winnerId, match.winnerName))
        add("loser", person(match.loserId, match.loserName))
    }

    private fun person(id: UUID, name: String) = JsonObject().apply {
        addProperty("uuid", id.toString())
        addProperty("name", name)
    }

    private fun summary(store: PvpMatchStore, player: UUID) = JsonObject().apply {
        val opponents = store.opponents(player)
        addProperty("uuid", player.toString())
        addProperty("name", store.nameOf(player) ?: player.toString())
        addProperty("wins", opponents.sumOf { it.wins })
        addProperty("losses", opponents.sumOf { it.losses })
        add("opponents", JsonArray().also { list ->
            opponents.forEach { opponent ->
                list.add(person(opponent.opponentId, opponent.opponentName).apply {
                    addProperty("wins", opponent.wins)
                    addProperty("losses", opponent.losses)
                })
            }
        })
    }
}
