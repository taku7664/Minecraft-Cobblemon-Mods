package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.*

/** Supplies the attacks a flinched Pokemon can actually make to the existing exchange formula. */
internal object LocalFlinchAvailability {
    fun profile(position: BattleDecisionContext, flinching: LocalMatchupScoreCalculator.ScoredMove?,
        responding: LocalMatchupScoreCalculator.ScoredMove?, first: Double): MoveMatchupScore? {
        if (flinching == null || responding == null || first <= 0.0) return responding?.score
        val state = position.state
        val source = state.pokemon.firstOrNull { it.battlePokemonId == flinching.score.userId } ?: return responding.score
        val target = state.pokemon.firstOrNull { it.battlePokemonId == responding.score.userId } ?: return responding.score
        val action = flinching.action
        if (action.moveDetails?.effects?.effects.orEmpty().none {
            it.kind == BattleMoveEffectKind.VOLATILE_STATUS && PublicIds.canonical(it.valueId.orEmpty()) == "flinch"
        }) return responding.score
        val cache = LocalProjectedActionCalculationCache()
        val probabilities = LocalRepeatedMoveMechanics.flinchProbabilities(position, action, source.battlePokemonId,
            target.battlePokemonId, cache) ?: return responding.score
        val available = probabilities.map { 1.0 - it * first }
        if (available.all { it >= 1.0 }) return responding.score
        val reply = responding.action
        val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(reply, position, target.side) ?: return responding.score
        val profile = LocalRepeatedMoveMechanics.profile(position, reply, target.battlePokemonId, source.battlePokemonId, rolls,
            responding.score.accuracy, cache, available) ?: return responding.score
        return LocalMatchupScoreCalculator.moveScore(target.battlePokemonId, responding.score.moveId, source.battlePokemonId,
            rolls, responding.score.accuracy, source.hpFraction, profile)
    }
}
