package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalStatStageMarginalEvaluator
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView

/** The reserve value erased by leaving, beyond the current exchange already priced against staying. */
internal object LocalSwitchRankValue {
    /** Signed score units: clearing harmful stages can be a gain. No price per stage. */
    fun loss(context: BattleDecisionContext, user: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache()): Double {
        if (user.statStages.values.none { it != 0 }) return 0.0
        val foes = context.state.pokemon.filter { it.side != user.side && !it.fainted && it.hpFraction > 0.0 }
        if (foes.isEmpty()) return 0.0
        val tuning = LocalDecisionTuning.CURRENT
        val resetContext = context.copy(state = context.state.derive(pokemon = context.state.pokemon.map {
            if (it.battlePokemonId == user.battlePokemonId) it.copyState(statStages = emptyMap()) else it
        }))
        // A foe in front is already in switchValue - stayValue. Charge that marginal only once.
        // Use the setup formula's average over all remaining foes; the field's contribution is owned above.
        return foes.filter { it.activeSlot == null }.sumOf { foe ->
            val before = LocalMatchupPosition.face(context, user, foe, cache) ?: return@sumOf 0.0
            // Reset before the foe enters: its Intimidate (and other entry effects) belongs to both boards.
            val clearedUser = resetContext.state.pokemon.first { it.battlePokemonId == user.battlePokemonId }
            val reset = LocalMatchupPosition.face(resetContext, clearedUser, foe, cache) ?: return@sumOf 0.0
            val change = LocalStatStageMarginalEvaluator.evaluate(before.state, reset.state, before, cache, tuning)
            jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record("switchRanks:${user.battlePokemonId}:${foe.battlePokemonId}",
                mapOf("pressureDelta" to change.pressureBoardDelta, "speedDelta" to change.speedBoardDelta,
                    "opponents" to foes.size.toDouble(), "loss" to -change.score / foes.size))
            -change.score
        } / foes.size
    }
}
