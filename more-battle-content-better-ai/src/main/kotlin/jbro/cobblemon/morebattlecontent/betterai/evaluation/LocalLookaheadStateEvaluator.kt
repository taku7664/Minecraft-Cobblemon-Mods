package jbro.cobblemon.morebattlecontent.betterai.evaluation

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector

/**
 * Leaf evaluation for the local recursive search.
 *
 * Values stay in board units: one point is one full HP bar and a living Pokemon is worth two
 * additional points. Stat stages are not assigned flat prices. Instead, public moves are
 * recalculated in the staged state so offensive and defensive changes are worth only the pressure
 * they can credibly create. Speed receives value only when it crosses a known action-order bound.
 */
internal object LocalLookaheadStateEvaluator {
    fun evaluate(
        state: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        shouldContinue: () -> Boolean = { true },
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double {
        val material = sideMaterial(state, BattleSide.ALLY) - sideMaterial(state, BattleSide.OPPONENT)
        if (battleEnded(state)) return material
        val pressure =
            attackPressure(state, BattleSide.ALLY, source, calculationCache, shouldContinue, tuning) -
                attackPressure(state, BattleSide.OPPONENT, source, calculationCache, shouldContinue, tuning)
        val speedControl = when (speedRelation(state)) {
            LocalPublicSpeedRelation.ALLY_FIRST -> tuning.leafSpeedControlValue
            LocalPublicSpeedRelation.OPPONENT_FIRST -> -tuning.leafSpeedControlValue
            LocalPublicSpeedRelation.AMBIGUOUS,
            LocalPublicSpeedRelation.UNAVAILABLE,
            -> 0.0
        }
        return material + pressure * tuning.leafPressureWeight + speedControl
    }

    fun speedRelation(state: BattleStateView): LocalPublicSpeedRelation {
        val ally = activeSpeed(state, BattleSide.ALLY) ?: return LocalPublicSpeedRelation.UNAVAILABLE
        val opponent = activeSpeed(state, BattleSide.OPPONENT) ?: return LocalPublicSpeedRelation.UNAVAILABLE
        val ordinaryRelation = when {
            ally.first > opponent.second -> LocalPublicSpeedRelation.ALLY_FIRST
            opponent.first > ally.second -> LocalPublicSpeedRelation.OPPONENT_FIRST
            else -> LocalPublicSpeedRelation.AMBIGUOUS
        }
        val trickRoomActive = state.field.roomEffects.any { effect ->
            val remainingTurns = effect.remainingTurns
            canonicalId(effect.effectId) == "trickroom" && (remainingTurns == null || remainingTurns > 0)
        }
        if (!trickRoomActive) return ordinaryRelation
        return when (ordinaryRelation) {
            LocalPublicSpeedRelation.ALLY_FIRST -> LocalPublicSpeedRelation.OPPONENT_FIRST
            LocalPublicSpeedRelation.OPPONENT_FIRST -> LocalPublicSpeedRelation.ALLY_FIRST
            LocalPublicSpeedRelation.AMBIGUOUS,
            LocalPublicSpeedRelation.UNAVAILABLE,
            -> ordinaryRelation
        }
    }

    fun switchOffensivePressureImprovement(
        candidate: BattleActionCandidate,
        source: BattleDecisionContext,
    ): Double? {
        if (candidate.kind != BattleActionKind.SWITCH || candidate.switchPokemonId == null) return null
        val current = attackPressure(source.state, BattleSide.ALLY, source)
        val switched = LocalSwitchStateProjector.project(source.state, BattleSide.ALLY, candidate)
        val incomingActive = switched.pokemon.any {
            it.battlePokemonId == candidate.switchPokemonId && it.activeSlot == candidate.actorSlot && !it.fainted
        }
        if (!incomingActive) return null
        return attackPressure(switched, BattleSide.ALLY, source) - current
    }

    fun switchInitiativeImprovement(
        candidate: BattleActionCandidate,
        source: BattleDecisionContext,
    ): Double? {
        if (candidate.kind != BattleActionKind.SWITCH || candidate.switchPokemonId == null) return null
        val switched = LocalSwitchStateProjector.project(source.state, BattleSide.ALLY, candidate)
        val incomingActive = switched.pokemon.any {
            it.battlePokemonId == candidate.switchPokemonId && it.activeSlot == candidate.actorSlot && !it.fainted
        }
        if (!incomingActive) return null
        return initiativeValue(speedRelation(switched)) - initiativeValue(speedRelation(source.state))
    }

    internal fun attackPressure(
        state: BattleStateView,
        side: BattleSide,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        shouldContinue: () -> Boolean = { true },
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double = PublicFutureActionFactory.actions(state, side, source.publicActionCatalog)
        .flatMap { action ->
            if (action.kind == BattleActionKind.COMPOSITE) action.componentActions else listOf(action)
        }
        .distinctBy(BattleActionCandidate::actionId)
        .filter { action ->
            action.kind == BattleActionKind.USE_MOVE &&
                action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS &&
                (action.moveDetails?.power ?: 0.0) > 0.0
        }
        .takeWhile { shouldContinue() }
        .map { action ->
            val calculatedContext = calculationCache.getOrCalculate(state, side, action) {
                PublicBattleTacticalCalculator.calculate(
                    actionContext(state, action, source),
                    side,
                )
            }
            val calculated = calculatedContext.candidates.single()
            val mechanics = LocalPublicMechanicsKernel.projectMove(calculated, calculatedContext, side)
            if (mechanics.publiclyNullified) return@map 0.0
            val facts = calculated.facts
            val accuracy = facts?.baseAccuracyProbability ?: 0.0
            val expectedDamage = facts?.standardDamageFractionRange?.let { range ->
                (range.minimum + range.maximum) / 2.0 * accuracy * mechanics.knownDamageMultiplier
            } ?: 0.0
            val knockoutProbability = facts?.standardDamageRollKoProbabilityRange?.let { range ->
                (range.minimum + range.maximum) / 2.0 * accuracy
            } ?: 0.0
            expectedDamage + knockoutProbability * tuning.leafKnockoutPressure
        }
        .maxOrNull()
        ?: 0.0

    private fun sideMaterial(state: BattleStateView, side: BattleSide): Double {
        val knownLiving = state.pokemon.filter {
            it.side == side && !it.fainted && it.hpFraction > 0.0
        }
        val unseenLiving = (state.remainingPokemonBySide.getValue(side) - knownLiving.size).coerceAtLeast(0)
        return knownLiving.sumOf { pokemon ->
            pokemon.hpFraction + if (!pokemon.fainted && pokemon.hpFraction > 0.0) LIVING_POKEMON_VALUE else 0.0
        } + unseenLiving * (1.0 + LIVING_POKEMON_VALUE)
    }

    private fun initiativeValue(relation: LocalPublicSpeedRelation): Double = when (relation) {
        LocalPublicSpeedRelation.ALLY_FIRST -> 1.0
        LocalPublicSpeedRelation.OPPONENT_FIRST -> -1.0
        LocalPublicSpeedRelation.AMBIGUOUS,
        LocalPublicSpeedRelation.UNAVAILABLE,
        -> 0.0
    }

    /**
     * The side's public Speed range, widened over every active slot.
     *
     * The per-Pokemon arithmetic - stage, paralysis, Tailwind, a revealed Choice Scarf - used to be
     * written out here as well as in [LocalPublicTurnOrder], two copies of the same answer to the
     * same question. They agreed today, which is exactly why the duplication was dangerous: the next
     * Speed rule added to one of them would silently miss the other, and nothing would fail. One
     * implementation, two callers.
     */
    private fun activeSpeed(state: BattleStateView, side: BattleSide): Pair<Int, Int>? {
        val ranges = state.pokemon.filter {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }.map { pokemon -> LocalPublicTurnOrder.effectiveSpeed(state, pokemon) ?: return null }
        if (ranges.isEmpty()) return null
        return ranges.minOf { it.first } to ranges.maxOf { it.second }
    }

    private fun actionContext(
        state: BattleStateView,
        action: BattleActionCandidate,
        source: BattleDecisionContext,
    ) = BattleDecisionContext(
        requestId = source.requestId,
        state = state,
        candidates = listOf(action),
        deadlineEpochMillis = source.deadlineEpochMillis,
        memory = BattleTacticalMemoryView.empty(),
        publicActionCatalog = source.publicActionCatalog,
    )

    private fun battleEnded(state: BattleStateView): Boolean = BattleSide.entries.any { side ->
        state.remainingPokemonBySide.getValue(side) <= 0
    }

    private fun canonicalId(id: String?): String? = id?.substringAfter(':')?.lowercase()?.filter { it.isLetterOrDigit() }

    private const val LIVING_POKEMON_VALUE = 2.0
}

internal enum class LocalPublicSpeedRelation { ALLY_FIRST, OPPONENT_FIRST, AMBIGUOUS, UNAVAILABLE }
