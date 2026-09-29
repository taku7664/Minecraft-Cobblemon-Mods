package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector

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
        includePositionEffects: Boolean = false,
    ): Double {
        val material = LocalBoardMaterial.evaluate(state)
        if (battleEnded(state)) return material + LocalTerminalOutcomeValue.evaluate(state)
        val pressure =
            attackPressure(state, BattleSide.ALLY, source, calculationCache, shouldContinue, tuning,
                capDamageToRemainingHp = tuning.capLeafDamageToRemainingHp) -
                attackPressure(state, BattleSide.OPPONENT, source, calculationCache, shouldContinue, tuning,
                    capDamageToRemainingHp = tuning.capLeafDamageToRemainingHp)
        val speedControl = when (speedRelation(state)) {
            LocalPublicSpeedRelation.ALLY_FIRST -> tuning.leafSpeedControlValue
            LocalPublicSpeedRelation.OPPONENT_FIRST -> -tuning.leafSpeedControlValue
            LocalPublicSpeedRelation.AMBIGUOUS,
            LocalPublicSpeedRelation.UNAVAILABLE,
            -> 0.0
        }
        val teamCoverage = if (tuning.leafTeamCoverageWeight == 0.0) 0.0 else {
            LocalTeamMatchupCoverage.evaluate(state, source, calculationCache, shouldContinue, tuning)
        }
        val duel = if (tuning.leafDuelValue == 0.0) 0.0 else {
            activeDuel(state, source, calculationCache, shouldContinue, tuning) * tuning.leafDuelValue
        }
        val persistentStages = if (tuning.leafPersistentStageValue == 0.0) 0.0 else {
            LocalPersistentStageValue.evaluate(state, source.publicActionCatalog) * tuning.leafPersistentStageValue
        }
        return material + pressure * tuning.leafPressureWeight + speedControl + persistentStages + duel +
            teamCoverage * tuning.leafTeamCoverageWeight +
            if (includePositionEffects) LocalImmediateTurnScorer.positionEffectValue(state) else 0.0
    }

    /**
     * Who wins the singles matchup on the field if both keep attacking: +1 when the AI's Pokemon knocks the
     * opponent's out first, -1 when it is knocked out first, counted in hits of each side's best expected
     * damage and settled by the public speed order (an open order splits it). Pressure alone prices a hit,
     * not the race: two Pokemon that each take a third per hit look even even when one moves first.
     */
    internal fun activeDuel(
        state: BattleStateView,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache,
        shouldContinue: () -> Boolean,
        tuning: LocalDecisionTuning,
    ): Double {
        if (state.format != BattleFormat.SINGLE) return 0.0
        val ally = state.pokemon.singleOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 } ?: return 0.0
        val foe = state.pokemon.singleOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 } ?: return 0.0
        val damageOnly = tuning.copy(leafKnockoutPressure = 0.0)
        val outgoing = attackPressure(state, BattleSide.ALLY, source, calculationCache, shouldContinue, damageOnly)
        val incoming = attackPressure(state, BattleSide.OPPONENT, source, calculationCache, shouldContinue, damageOnly)
        val ours = hits(foe.hpFraction, outgoing)
        val theirs = hits(ally.hpFraction, incoming)
        if (ours == Int.MAX_VALUE && theirs == Int.MAX_VALUE) return 0.0
        fun result(allyFirst: Boolean): Double = when {
            ours < theirs -> 1.0
            ours > theirs -> -1.0
            else -> if (allyFirst) 1.0 else -1.0
        }
        return when (speedRelation(state)) {
            LocalPublicSpeedRelation.ALLY_FIRST -> result(true)
            LocalPublicSpeedRelation.OPPONENT_FIRST -> result(false)
            LocalPublicSpeedRelation.AMBIGUOUS, LocalPublicSpeedRelation.UNAVAILABLE -> (result(true) + result(false)) / 2.0
        }
    }

    private fun hits(hp: Double, damage: Double): Int =
        if (damage <= DUEL_MINIMUM_DAMAGE) Int.MAX_VALUE else kotlin.math.ceil(hp / damage - 1e-9).toInt().coerceAtLeast(1)

    private const val DUEL_MINIMUM_DAMAGE = 0.01

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
        // Leaf value measures realizable primary-target HP loss. Exposure/switch callers retain
        // raw damage pressure. This does not turn the existing max pressure into a joint-turn sum.
        capDamageToRemainingHp: Boolean = false,
        // Threat estimation may count the opponent's expected move slots; the leaf keeps known moves.
        includeMoveHypotheses: Boolean = false,
    ): Double = calculationCache.slotActions(state, side, source.publicActionCatalog, includeMoveHypotheses) {
        PublicFutureActionFactory.slotActions(state, side, source.publicActionCatalog, includeMoveHypotheses = includeMoveHypotheses)
    }
        .filter { action ->
            action.kind == BattleActionKind.USE_MOVE &&
                action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS &&
                (action.moveDetails?.power ?: 0.0) > 0.0
        }
        .takeWhile { shouldContinue() }
        .map { action ->
            val calculatedContext = calculationCache.getOrCalculate(state, side, action, catalog = source.publicActionCatalog) {
                PublicBattleTacticalCalculator.calculate(
                    actionContext(state, action, source),
                    side,
                )
            }
            val leaf = calculationCache.leafAttack(calculatedContext) {
                val calculated = calculatedContext.candidates.single()
                val mechanics = LocalPublicMechanicsKernel.projectMove(calculated, calculatedContext, side)
                LocalProjectedActionCalculationCache.LeafAttack(
                    nullified = mechanics.publiclyNullified,
                    damageMultiplier = mechanics.knownDamageMultiplier,
                    accuracy = if (mechanics.publiclyNullified) 0.0 else LocalPublicAccuracy.probability(calculated, calculatedContext, side),
                    targetHpFraction = if (mechanics.publiclyNullified) null
                        else PublicBattleTacticalCalculator.primaryTargetHpFraction(calculated, calculatedContext, side),
                    damageRange = calculated.facts?.standardDamageFractionRange,
                    knockoutRange = calculated.facts?.standardDamageRollKoProbabilityRange,
                )
            }
            if (leaf.nullified) return@map 0.0
            val accuracy = leaf.accuracy
            val targetHp = if (capDamageToRemainingHp) leaf.targetHpFraction else null
            val expectedDamage = leaf.damageRange?.let { range ->
                if (targetHp == null) {
                    (range.minimum + range.maximum) / 2.0 * accuracy * leaf.damageMultiplier
                } else {
                    // Cap before accuracy: inaccurate overkill is not a certain full HP bar.
                    val minimum = (range.minimum * leaf.damageMultiplier).coerceAtMost(targetHp)
                    val maximum = (range.maximum * leaf.damageMultiplier).coerceAtMost(targetHp)
                    (minimum + maximum) / 2.0 * accuracy
                }
            } ?: 0.0
            val knockoutProbability = leaf.knockoutRange?.let { range ->
                (range.minimum + range.maximum) / 2.0 * accuracy
            } ?: 0.0
            expectedDamage + knockoutProbability * tuning.leafKnockoutPressure
        }
        .maxOrNull()
        ?: 0.0

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
    ) = source.copy(
        state = state,
        candidates = listOf(action),
        memory = BattleTacticalMemoryView.empty(),
    )

    private fun battleEnded(state: BattleStateView): Boolean = BattleSide.entries.any { side ->
        state.remainingPokemonBySide.getValue(side) <= 0
    }

    private fun canonicalId(id: String?): String? = id?.let(PublicIds::canonical)

}

internal enum class LocalPublicSpeedRelation { ALLY_FIRST, OPPONENT_FIRST, AMBIGUOUS, UNAVAILABLE }
