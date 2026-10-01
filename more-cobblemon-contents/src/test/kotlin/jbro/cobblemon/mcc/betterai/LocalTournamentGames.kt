package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Games of a real singles tournament, each as the three Pokemon both players brought and who won.
 *
 * The sets come from the games' replays: every move, item and ability a replay showed is the player's own, and
 * what it never showed (the spread, the moves not used, an item that never activated) is the most used choice of
 * that month's ladder usage statistics. A player who sent in only two has the third filled in from the team preview,
 * the teammate the ladder pairs most often with the two shown; [complete] is false for those games. Each set has a
 * Tera type: the one the replay showed for the Pokemon that Terastallized, the ladder's most used for the rest.
 */
internal data class LocalTournamentGame(
    val name: String,
    /** Lead first, in the order they came in. */
    val p1: List<String>,
    val p2: List<String>,
    /** "p1" or "p2": who won the real game. */
    val winner: String,
    /** Both sides' three were all seen in the replay. */
    val complete: Boolean,
)

internal object LocalTournamentGames {
    private val FILES = listOf("bss-open-viii-round2.json")

    fun roots(): List<JsonObject> = FILES.map { file ->
        val stream = requireNotNull(LocalTournamentGames::class.java.getResourceAsStream("/betterai/tournament/$file")) {
            "Missing tournament data $file"
        }
        stream.reader().use(JsonParser::parseReader).asJsonObject
    }

    fun all(): List<LocalTournamentGame> = roots().flatMap { root ->
        root.getAsJsonArray("games").map { element ->
            val game = element.asJsonObject
            LocalTournamentGame(
                name = game.get("name").asString,
                p1 = game.getAsJsonArray("p1").map { it.asString },
                p2 = game.getAsJsonArray("p2").map { it.asString },
                winner = game.get("winner").asString,
                complete = game.get("complete").asBoolean,
            )
        }
    }
}
