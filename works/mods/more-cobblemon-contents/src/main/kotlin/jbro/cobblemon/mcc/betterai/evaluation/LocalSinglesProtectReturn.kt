package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.state.RecursiveSnapshotActionConstraints
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView
import java.util.UUID

/**
 * What a Protect is worth in singles: the return on the turn it spends instead of an attack, in board units.
 *
 * Blocking a hit is no gain by itself in singles, the turn is (decided with the user, 2026-10-16). Priced as a generic
 * status move (a flat 20), a Max Guard outranked the Max Moves it replaced whenever they hit for little. What the turn
 * brings:
 * - its end, played out with nobody acting ([PublicSingleTurnProjector]): residual damage on either side (poison, burn,
 *   Leech Seed, Curse), healing, Speed Boost and the like, scored as any turn is ([LocalImmediateTurnScorer]);
 * - a turn off each timed field effect still running after it (screens, Tailwind, weather...): its worth to whichever
 *   side it serves, by the same scorer, over the turns it had left (one that ends this turn is in the first term);
 * - a turn off every Dynamax in front, the user's own included: what that Dynamax changes in one exchange of the two in
 *   front, each one's best hit on the other ([LocalMatchupScoreCalculator.pairMatchup]) with and without it. Not its
 *   change to their win chance spread over the turns left: the matchup reads a Dynamax as lasting the whole duel, so
 *   its last turn came out worth a knockout (a Max Guard at 212 against attacks at 7).
 *
 * Null outside singles or without both Pokemon in front; the caller weighs it by the chance the Protect works.
 *
 * The turn's end is played here with the opponent waiting. No turn is played in the engine against the opponent's
 * replies (user decision 2026-10-19: no event-branch search).
 */
internal object LocalSinglesProtectReturn {
    fun value(candidate: BattleActionCandidate, context: BattleDecisionContext, tuning: LocalDecisionTuning): Double? {
        val state = context.state
        if (state.format != BattleFormat.SINGLE) return null
        val user = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && alive(it) } ?: return null
        val foe = state.pokemon.firstOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && alive(it) } ?: return null
        val cache = LocalProjectedActionCalculationCache()
        val shouldContinue = { System.currentTimeMillis() < context.deadlineEpochMillis }
        val (turn, projections) = turnEnd(candidate, context, tuning, cache) ?: return null
        val fieldTurns = fieldTurns(context, tuning, cache, shouldContinue)
        // After this turn's actions, so a Dynamax that starts on it (a Dynamax Max Guard) is counted from its first turn.
        val acted = projections.first().stateBeforeResidual
        val dynamaxTurns = listOf(user, foe).sumOf { pokemon ->
            val dynamaxed = acted.pokemon.firstOrNull { it.battlePokemonId == pokemon.battlePokemonId }?.takeIf(LocalDynamax::active)
                ?: return@sumOf 0.0
            dynamaxTurn(context, user, foe, dynamaxed, cache)
        }
        return turn + fieldTurns + dynamaxTurns
    }

    /**
     * A turn off each timed field effect still running after it (screens, Tailwind, weather...), in board units: its
     * worth to whichever side it serves, over the turns it had left. Singles and doubles alike.
     */
    internal fun fieldTurns(
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
        cache: LocalProjectedActionCalculationCache,
        shouldContinue: () -> Boolean,
    ): Double {
        val state = context.state
        return -timedEffects(state.field).sumOf { (effect, without) ->
            val left = remaining(effect)?.takeIf { it >= 2.0 } ?: return@sumOf 0.0
            LocalImmediateTurnScorer.score(state.derive(field = without), state, context, cache, tuning, shouldContinue).total / left
        }
    }

    /**
     * A turn off [dynamaxed]'s Dynamax in the exchange of [user] and [foe], in board units: the user's own Dynamax lost
     * a turn costs what it adds, the opponent's gives back what it takes.
     */
    internal fun dynamaxTurn(
        context: BattleDecisionContext,
        user: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        dynamaxed: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
    ): Double {
        val with = exchange(context, user, foe, dynamaxed.battlePokemonId, dynamaxed.knownVolatileEffectIds, cache) ?: return 0.0
        val without = exchange(context, user, foe, dynamaxed.battlePokemonId, dynamaxed.knownVolatileEffectIds.filterNot {
            it == LocalDynamax.VOLATILE || it.startsWith(LocalDynamax.TURNS)
        }.toSet(), cache) ?: return 0.0
        return -(with - without)
    }

    /**
     * The turn's end with the opponent waiting, in board units, and the projections it was read from: residual damage
     * on either side, healing, Speed Boost and the like. Null when nothing could be projected.
     */
    fun turnEnd(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
    ): Pair<Double, List<jbro.cobblemon.mcc.betterai.state.PublicTurnProjection>>? {
        val state = context.state
        val shouldContinue = { System.currentTimeMillis() < context.deadlineEpochMillis }
        val projections = PublicSingleTurnProjector.project(state, candidate, BattleActionCandidate("protect:wait", BattleActionKind.WAIT),
            context, RecursiveSnapshotActionConstraints.seed(state), calculationCache = cache, shouldContinue = shouldContinue)
        val mass = projections.sumOf { it.probability * it.orderProbability }
        if (mass <= 0.0) return null
        // The win bonus left out: the attacks it is weighed against carry none, so a residual knockout of the last opponent
        // put a Max Guard at 100180 (its knockout stays, as material).
        return projections.sumOf {
            it.probability * it.orderProbability * (LocalImmediateTurnScorer.score(state, it.state, context, cache, tuning, shouldContinue).total -
                LocalTerminalOutcomeValue.evaluate(it.state) + LocalTerminalOutcomeValue.evaluate(state))
        } / mass to projections
    }

    /** One exchange of [user] and [foe], the user's best expected hit less the foe's, with [pokemonId]'s volatiles set to [volatiles]. */
    private fun exchange(
        context: BattleDecisionContext,
        user: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        pokemonId: UUID,
        volatiles: Set<String>,
        cache: LocalProjectedActionCalculationCache,
    ): Double? {
        val state = context.state.copyState(pokemon = context.state.pokemon.map {
            if (it.battlePokemonId == pokemonId) it.copyState(knownVolatileEffectIds = volatiles) else it
        })
        val matchup = LocalMatchupScoreCalculator.pairMatchup(context.copy(state = state), user.battlePokemonId, foe.battlePokemonId,
            MatchupSpeedField.CURRENT, cache) ?: return null
        fun hit(move: jbro.cobblemon.mcc.betterai.matchup.MoveMatchupScore?) =
            move?.let { (it.minimumDamageFraction + it.maximumDamageFraction) / 2.0 * it.accuracy } ?: 0.0
        return hit(matchup.subjectMove) - hit(matchup.opponentMove)
    }

    /** Every timed effect on the field, with the field it leaves when taken away. */
    private fun timedEffects(field: BattleFieldStateView): List<Pair<BattleTimedEffectView, BattleFieldStateView>> = buildList {
        field.weather?.let { add(it to BattleFieldStateView(null, field.terrain, field.roomEffects, field.globalEffects, field.sideConditions)) }
        field.terrain?.let { add(it to BattleFieldStateView(field.weather, null, field.roomEffects, field.globalEffects, field.sideConditions)) }
        field.roomEffects.forEach { effect -> add(effect to BattleFieldStateView(field.weather, field.terrain,
            field.roomEffects - effect, field.globalEffects, field.sideConditions)) }
        field.globalEffects.forEach { effect -> add(effect to BattleFieldStateView(field.weather, field.terrain,
            field.roomEffects, field.globalEffects - effect, field.sideConditions)) }
        field.sideConditions.forEach { (side, effects) -> effects.forEach { effect -> add(effect to BattleFieldStateView(field.weather,
            field.terrain, field.roomEffects, field.globalEffects, field.sideConditions + (side to effects - effect))) } }
    }

    private fun remaining(effect: BattleTimedEffectView): Double? =
        effect.remainingTurns?.toDouble() ?: effect.remainingTurnsRange?.let { (it.minimum + it.maximum) / 2.0 }

    private fun alive(pokemon: BattlePokemonStateView) = !pokemon.fainted && pokemon.hpFraction > 0.0
}
