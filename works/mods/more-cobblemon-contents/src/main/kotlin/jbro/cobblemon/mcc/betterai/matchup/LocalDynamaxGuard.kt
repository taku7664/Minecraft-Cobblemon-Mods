package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * What Dynamaxing guards on the turn it starts: its doubled HP against the opposing Pokemon's best hit this turn, the
 * damage it halves and the knockout it stops, in score units. In doubles, both opposing active Pokemon contribute;
 * their expected damage is capped at the user's remaining HP once and their largest one-hit knockout chance is used,
 * so two threats do not award the same knockout twice.
 *
 * The ranking prices a move by what it does to the target, so a Dynamax choice and the same move without it came out
 * equal whenever both knocked out (a Lapras Freeze-Dry at 300 either way), and the draw kept the one that left it to a
 * faster Max Move (G-948 ②). The hit guarded against is the one the opposing Pokemon has as it stands. Measured
 * against its Max Move too while its side might still Dynamax (the larger of the two), every lead Dynamaxed on turn 1
 * for the knockout that Max Move threatened, walls included (Torkoal at -32, Ferrothorn at -36); 2026-10-16.
 */
internal object LocalDynamaxGuard {
    fun adjustments(candidates: List<BattleActionCandidate>, context: BattleDecisionContext, tuning: LocalDecisionTuning): Map<String, Double> {
        val state = context.state
        if (state.format !in setOf(BattleFormat.SINGLE, BattleFormat.DOUBLE)) return emptyMap()
        val dynamaxing = candidates.mapNotNull { candidate ->
            parts(candidate).singleOrNull { LocalDynamax.isMechanic(it.mechanic?.mechanicId) }?.let { candidate to it }
        }
        if (dynamaxing.isEmpty()) return emptyMap()
        val foes = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && alive(it) }
        if (foes.isEmpty()) return emptyMap()
        val cache = LocalProjectedActionCalculationCache()
        return dynamaxing.mapNotNull { (candidate, mechanic) ->
            val user = state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.activeSlot == mechanic.actorSlot && alive(it)
            } ?: return@mapNotNull null
            if (LocalDynamax.active(user)) return@mapNotNull null
            val gigantamax = context.exactOwnTeam?.buildFor(user.battlePokemonId)?.gigantamax == true || LocalDynamax.gigantamax(user)
            val dynamaxed = LocalDynamax.start(user, gigantamax)
            val plain = threat(context, state, user, foes, tuning, cache) ?: return@mapNotNull null
            val guarded = threat(context, with(state, dynamaxed), dynamaxed, foes, tuning, cache) ?: return@mapNotNull null
            (plain - guarded).takeIf { it > 0.0 }?.let { candidate.actionId to it }
        }.toMap()
    }

    /** The opposing active Pokemon's best hits on [user] this turn, with material counted once, in score units. */
    private fun threat(
        context: BattleDecisionContext,
        state: BattleStateView,
        user: BattlePokemonStateView,
        foes: List<BattlePokemonStateView>,
        tuning: LocalDecisionTuning,
        cache: LocalProjectedActionCalculationCache,
    ): Double? {
        val hits = foes.mapNotNull { foe ->
            LocalMatchupScoreCalculator.pairMatchup(context.copy(state = state), user.battlePokemonId, foe.battlePokemonId,
                MatchupSpeedField.CURRENT, cache)?.opponentMove
        }
        if (hits.isEmpty()) return null
        // Every hit is already halved for a Dynamaxed target, as every calculation reads one.
        val taken = hits.sumOf { (it.minimumDamageFraction + it.maximumDamageFraction) / 2.0 * it.accuracy }
        val knockout = hits.maxOf { it.knockoutChanceWithin(1) }
        return minOf(taken, user.hpFraction) * tuning.boardToScore + knockout * tuning.knockoutMaterialScore
    }

    private fun with(state: BattleStateView, pokemon: BattlePokemonStateView): BattleStateView =
        state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == pokemon.battlePokemonId) pokemon else it })

    private fun parts(candidate: BattleActionCandidate): List<BattleActionCandidate> =
        if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)

    private fun alive(pokemon: BattlePokemonStateView) = !pokemon.fainted && pokemon.hpFraction > 0.0
}
