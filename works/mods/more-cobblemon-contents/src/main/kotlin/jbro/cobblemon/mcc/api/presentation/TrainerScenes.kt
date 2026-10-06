package jbro.cobblemon.mcc.api.presentation

/**
 * What a managed battle's trainer says at set moments, as translation keys. At each moment the battle camera turns
 * to the trainer while the lines are read ([BattleScenes]); a moment with no lines passes quietly. Each moment plays
 * at most once a battle.
 */
data class TrainerScenes(val lines: Map<Moment, List<String>> = emptyMap()) {
    enum class Moment(val id: String) {
        /** The battle has opened. */
        BATTLE_START("battle_start"),
        /** The trainer is down to its last Pokemon, before it comes out (not for a one-Pokemon team). */
        LAST_POKEMON("last_pokemon"),
        /** The player won; the battle closes after the lines. */
        PLAYER_WON("player_won"),
        /** The player lost; the battle closes after the lines. */
        PLAYER_LOST("player_lost");

        companion object {
            fun fromId(id: String): Moment? = entries.firstOrNull { it.id == id }
        }
    }

    init {
        lines.values.forEach { keys ->
            require(keys.size in 1..BattleScenes.MAX_LINES) { "A scene moment has 1 to ${BattleScenes.MAX_LINES} lines" }
            require(keys.all { it.isNotBlank() && it.length <= 256 }) { "Scene lines are translation keys" }
        }
    }

    operator fun get(moment: Moment): List<String> = lines[moment].orEmpty()

    companion object {
        val NONE = TrainerScenes()
    }
}
