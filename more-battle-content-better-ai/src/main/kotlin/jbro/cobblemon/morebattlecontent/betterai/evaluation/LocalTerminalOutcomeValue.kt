package jbro.cobblemon.morebattlecontent.betterai.evaluation

import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

/** A finished win outranks every nonterminal material advantage; a double KO is a draw. */
internal object LocalTerminalOutcomeValue {
    fun evaluate(state: BattleStateView): Double {
        val allyAlive = state.remainingPokemonBySide.getValue(BattleSide.ALLY) > 0
        val opponentAlive = state.remainingPokemonBySide.getValue(BattleSide.OPPONENT) > 0
        return when {
            allyAlive && !opponentAlive -> WIN_VALUE
            !allyAlive && opponentAlive -> -WIN_VALUE
            else -> 0.0
        }
    }

    private const val WIN_VALUE = 1_000.0
}
