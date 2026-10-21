package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.PublicSwitchEntryHazardCalculator

internal data class LocalImmediateTurnScore(
    val materialDelta: Double,
    val stageDelta: Double,
    val statusDelta: Double,
    val speedControlDelta: Double,
    val fieldDelta: Double,
) {
    val total: Double = materialDelta + stageDelta + statusDelta + speedControlDelta + fieldDelta
}

/**
 * Scores only what changed during one projected turn.
 *
 * Damage keeps separate KO/survival states with a representative roll within each class.
 * Removal value is already part of materialDelta; secondary effects can still use weighted score.
 */
internal object LocalImmediateTurnScorer {
    fun score(
        before: BattleStateView,
        after: BattleStateView,
        source: BattleDecisionContext? = null,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        shouldContinue: () -> Boolean = { true },
    ): LocalImmediateTurnScore {
        val beforeMaterial = LocalBoardMaterial.evaluate(before)
        val afterMaterial = LocalBoardMaterial.evaluate(after)
        val stageDelta = source?.let {
            LocalStatStageMarginalEvaluator.transitionValue(
                before,
                after,
                it,
                calculationCache,
                tuning,
                shouldContinue,
            ).pressureBoardDelta + if (tuning.leafPersistentStageValue == 0.0) 0.0 else {
                (LocalPersistentStageValue.evaluate(after, it.publicActionCatalog) -
                    LocalPersistentStageValue.evaluate(before, it.publicActionCatalog)) * tuning.leafPersistentStageValue
            } + if (tuning.leafDuelValue == 0.0) 0.0 else {
                // The leaf's matchup term, changed by this turn; counted here so a one-turn line sees it too.
                (LocalLookaheadStateEvaluator.activeDuel(after, it, calculationCache, shouldContinue, tuning) -
                    LocalLookaheadStateEvaluator.activeDuel(before, it, calculationCache, shouldContinue, tuning)) * tuning.leafDuelValue
            }
        } ?: 0.0
        val beforeStatus = positionStatus(before)
        val afterStatus = positionStatus(after)
        val speedControlDelta = LocalStatStageMarginalEvaluator.speedTransitionBoardDelta(before, after, tuning, source, calculationCache)
        val beforeField = positionField(before, source)
        val afterField = positionField(after, source)
        return LocalImmediateTurnScore(
            materialDelta = afterMaterial - beforeMaterial +
                LocalTerminalOutcomeValue.evaluate(after) - LocalTerminalOutcomeValue.evaluate(before),
            stageDelta = stageDelta,
            statusDelta = afterStatus - beforeStatus,
            speedControlDelta = speedControlDelta,
            fieldDelta = afterField - beforeField,
        )
    }

    fun expectedEffectScore(
        before: BattleStateView,
        afterEffect: BattleStateView,
        probability: Double,
        source: BattleDecisionContext,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        shouldContinue: () -> Boolean = { true },
    ): Double = score(
        before,
        afterEffect,
        source,
        calculationCache,
        tuning,
        shouldContinue,
    ).total * probability.coerceIn(0.0, 1.0)

    /** Native search has no immediate-delta scorer, so retain durable public effects at its leaf. */
    fun positionEffectValue(state: BattleStateView, source: BattleDecisionContext? = null): Double =
        positionStatus(state) + positionField(state, source)

    private fun positionStatus(state: BattleStateView): Double =
        sideStatusBurden(state, BattleSide.OPPONENT) - sideStatusBurden(state, BattleSide.ALLY)

    internal fun sideStatusBurden(state: BattleStateView, side: BattleSide): Double = state.pokemon
        .asSequence()
        .filter { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        .sumOf { statusBurden(it.statusId) }

    private fun statusBurden(statusId: String?): Double = when (canonicalId(statusId)) {
        null -> 0.0
        "tox", "toxic", "badlypoisoned" -> 0.35
        "slp", "sleep", "frz", "freeze", "frozen" -> 0.35
        "par", "paralysis", "paralyzed", "paralysed", "brn", "burn", "burned", "burnt" -> 0.25
        "psn", "poison", "poisoned" -> 0.20
        else -> 0.15
    }

    private fun positionField(state: BattleStateView, source: BattleDecisionContext?): Double =
        sideFieldValue(state, BattleSide.ALLY, source) - sideFieldValue(state, BattleSide.OPPONENT, source)

    internal fun sideFieldValue(state: BattleStateView, side: BattleSide, source: BattleDecisionContext?): Double =
        -entryHazardLoss(state, side, source) + state.field.sideConditions.getValue(side).sumOf { effect ->
            val stacks = effect.stacks ?: 1
            when (canonicalId(effect.effectId)) {
                // No HP lost on entry: the old flat value, discounted by the reserves left to step on them.
                "toxicspikes", "stickyweb" -> -HAZARD_STACK_VALUE * stacks *
                    LocalHazardSwitchAvailability.fraction(state, side, source)
                "reflect", "lightscreen", "auroraveil", "safeguard", "mist" -> BENEFICIAL_SIDE_EFFECT_VALUE
                else -> 0.0
            }
        }

    /**
     * The HP a side's reserves lose to its hazards (Stealth Rock, Spikes, Steelsurge), each entering once, by the game's
     * own fractions ([PublicSwitchEntryHazardCalculator]). A flat 0.10 a layer let a single 40% hit outweigh Stealth Rock
     * with three opponents left to come in, where those entries are worth half a health bar. Each reserve is counted
     * once and never past its own HP. A reserve not seen yet takes the mean of the preview's unseen Pokemon (else a
     * neutral entry); the active Pokemon is not counted, its own re-entry being uncertain.
     */
    private fun entryHazardLoss(state: BattleStateView, side: BattleSide, source: BattleDecisionContext?): Double {
        val hazards = state.field.sideConditions.getValue(side)
        if (hazards.none { canonicalId(it.effectId) in HP_HAZARDS }) return 0.0
        val reserves = (state.remainingPokemonBySide.getValue(side) -
            state.pokemon.count { it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }).coerceAtLeast(0)
        if (reserves == 0) return 0.0
        val known = state.pokemon.filter { it.side == side && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }
            .take(reserves)
        val knownLoss = known.sumOf { reserve ->
            minOf(PublicSwitchEntryHazardCalculator.hpLoss(state, side, reserve) ?: neutralLoss(state, side), reserve.hpFraction)
        }
        val unseen = reserves - known.size
        if (unseen <= 0) return knownLoss
        val preview = source?.opponentTeamPreview?.takeIf { side == BattleSide.OPPONENT }
        val seenSpecies = state.pokemon.filter { it.side == side }.map { canonicalId(it.speciesId) }.toSet()
        val unseenLosses = preview?.pokemon.orEmpty()
            .filter { canonicalId(it.speciesId) !in seenSpecies && it.knownTypeIds.isNotEmpty() }
            .mapNotNull { slot ->
                PublicSwitchEntryHazardCalculator.hpLoss(state.field, side, BattlePokemonStateView(
                    java.util.UUID.nameUUIDFromBytes("preview:${slot.previewSlotId}".toByteArray()), side, null,
                    slot.speciesId, slot.formId, slot.level, 1.0, null, emptyMap(), emptySet(), null, null, false,
                    slot.knownTypeIds,
                ))
            }
        val perUnseen = if (unseenLosses.isEmpty()) neutralLoss(state, side) else unseenLosses.average()
        return knownLoss + unseen * perUnseen
    }

    /** One entry for a Pokemon of no known type: Stealth Rock and Steelsurge neutral, Spikes grounded. */
    private fun neutralLoss(state: BattleStateView, side: BattleSide): Double =
        state.field.sideConditions.getValue(side).sumOf { hazard ->
            when (canonicalId(hazard.effectId)) {
                "stealthrock", "gmaxsteelsurge" -> 1.0 / 8.0
                "spikes" -> when ((hazard.stacks ?: 1).coerceIn(1, 3)) { 1 -> 1.0 / 8.0; 2 -> 1.0 / 6.0; else -> 1.0 / 4.0 }
                else -> 0.0
            }
        }.coerceIn(0.0, 1.0)

    private fun canonicalId(value: String?): String? = value?.let(PublicIds::canonical)

    private const val HAZARD_STACK_VALUE = 0.10
    /** Hazards that take HP on entry ([PublicSwitchEntryHazardCalculator]). */
    private val HP_HAZARDS = setOf("stealthrock", "spikes", "gmaxsteelsurge")
    private const val BENEFICIAL_SIDE_EFFECT_VALUE = 0.15
}
