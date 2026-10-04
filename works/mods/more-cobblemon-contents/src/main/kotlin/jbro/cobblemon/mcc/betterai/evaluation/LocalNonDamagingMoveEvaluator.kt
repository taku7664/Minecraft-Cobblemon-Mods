package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.betterai.mechanics.LocalStallingProtectionRules

/**
 * Values a non-damaging move by the progress it can still make from public state.
 *
 * This keeps recovery, setup and screen decisions from treating every legal status move as the
 * same flat benefit. It does not predict a hidden opponent set or feed recommendations to Router.
 */
internal object LocalNonDamagingMoveEvaluator {
    internal data class Score(
        val total: Double,
        /** The part of [total] owned by the stat-stage evaluator and replaceable by lookahead. */
        val statStageUtility: Double,
    )

    fun pressure(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        accuracy: Double,
    ): Double = score(candidate, context, accuracy).total

    fun score(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        accuracy: Double,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        /** How far the trainer matches the status to the target; see [LocalStatusTargetFit]. */
        statusFitScale: Double = 0.0,
    ): Score {
        val actor = actor(candidate, context)
        val missingHp = (1.0 - (actor?.hpFraction ?: 1.0)).coerceIn(0.0, 1.0)
        val recovery = candidate.facts?.selfHealingFractionRange?.let { range ->
            val averageHealing = (range.minimum + range.maximum) / 2.0
            val effectiveHealing = minOf(averageHealing, missingHp)
            // Each heal in a row that still ended its turn lower makes the next one cost more.
            val losingLoop = actor?.let { LocalRecoveryLoop.failedStreak(it.battlePokemonId, context) } ?: 0
            effectiveHealing * 100.0 - losingLoop * tuning.recoveryLoopPenalty
        } ?: 0.0

        val effects = candidate.moveDetails?.effects?.effects.orEmpty()
        if (LocalIdleUtilityMoveRules.isIdle(candidate, context)) return Score(0.0, 0.0)
        val protectionSuccessProbability = if (LocalStallingProtectionRules.isStallingProtection(candidate)) {
            LocalStallingProtectionRules.nextSuccessProbability(
                LocalStallingProtectionRules.consecutiveSuccessfulUses(
                    context.state,
                    BattleSide.ALLY,
                    candidate.actorSlot,
                ),
            )
        } else {
            1.0
        }
        val selectedTarget = selectedTarget(candidate, context)
        val declaresMajorStatus = selectedTarget?.side == BattleSide.OPPONENT && effects.any {
            it.kind == BattleMoveEffectKind.STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET
        }
        val declaresPureRecovery = candidate.facts?.selfHealingFractionRange != null && isPureRecovery(effects)
        val setupPressure = LocalStatStageMarginalEvaluator.candidateScore(
            candidate,
            context,
            accuracy,
            tuning = tuning,
        )
        val target = selectedTarget?.takeIf { it.side == BattleSide.OPPONENT }
        val baseAccuracy = candidate.facts?.baseAccuracyProbability
            ?: candidate.moveDetails?.accuracy?.div(100.0)
            ?: 1.0
        val statusProbability = candidate.facts?.statusEffectProbability?.let { baseProbability ->
            if (baseAccuracy > 0.0) {
                (baseProbability / baseAccuracy * accuracy).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        }
        var usesSetupPressure = false
        val status = when {
            declaresMajorStatus && target?.statusId != null -> 0.0
            statusProbability != null -> {
                val targetHpWeight = target?.hpFraction?.let { 0.5 + it * 0.5 } ?: 1.0
                val statusId = effects.firstOrNull {
                    it.kind == BattleMoveEffectKind.STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET
                }?.valueId
                val fit = LocalStatusTargetFit.multiplier(statusId, target, context, statusFitScale)
                statusProbability * MAJOR_STATUS_PRESSURE * targetHpWeight * fit
            }
            declaresPureRecovery -> 0.0
            setupPressure != null -> {
                usesSetupPressure = true
                setupPressure
            }
            calloutValue(candidate, context, actor, target) != null ->
                requireNotNull(calloutValue(candidate, context, actor, target)) * accuracy
            else -> (GENERIC_STATUS_PRESSURE - additionalScreenOpportunityCost(effects, context))
                .coerceAtLeast(0.0) * accuracy * protectionSuccessProbability
        }
        // A pure heal is its own value, the losing-loop penalty included; anything else takes the better reading.
        val usableStatus = if (LocalHazardSwitchAvailability.isHazardMove(candidate)) {
            status * LocalHazardSwitchAvailability.fraction(context.state, BattleSide.OPPONENT, context)
        } else status
        val total = if (declaresPureRecovery) recovery else maxOf(recovery, usableStatus)
        return Score(
            total = total,
            statStageUtility = if (usesSetupPressure && status >= recovery) status else 0.0,
        )
    }

    private fun additionalScreenOpportunityCost(
        effects: List<jbro.cobblemon.mcc.internal.ai.BattleMoveEffectView>,
        context: BattleDecisionContext,
    ): Double {
        val setsAlliedScreen = effects.any {
            it.kind == BattleMoveEffectKind.SIDE_CONDITION &&
                it.target == BattleMoveEffectTarget.USER_SIDE &&
                it.valueId?.let(::canonicalEffectId) in SCREEN_EFFECTS
        }
        if (!setsAlliedScreen) return 0.0
        val alreadyProtected = context.state.field.sideConditions.getValue(BattleSide.ALLY).any {
            canonicalEffectId(it.effectId) in SCREEN_EFFECTS && it.remainingTurns != EXPIRING_EFFECT_TURNS
        }
        return if (alreadyProtected) ADDITIONAL_SCREEN_OPPORTUNITY_COST else 0.0
    }

    private fun actor(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): BattlePokemonStateView? = candidate.actorSlot?.let { actorSlot ->
        context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == actorSlot && !it.fainted
        }
    }

    private fun selectedTarget(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): BattlePokemonStateView? {
        val targetSlot = candidate.targets.singleOrNull() ?: return null
        return context.state.pokemon.firstOrNull {
            it.side == targetSlot.side && it.activeSlot == targetSlot.slot && !it.fainted
        }
    }

    private fun isPureRecovery(effects: List<BattleMoveEffectView>): Boolean =
        effects.any { it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER } &&
            effects.all { effect ->
                (effect.kind == BattleMoveEffectKind.HEAL_FRACTION && effect.target == BattleMoveEffectTarget.USER) ||
                    // Roost's one-turn Flying-type suppression is part of the recovery move, not
                    // an independent generic status reward. The native engine can value its type
                    // impact; this fallback must not assign it a flat +20 at full HP.
                    (effect.kind == BattleMoveEffectKind.VOLATILE_STATUS &&
                        effect.target == BattleMoveEffectTarget.USER &&
                        canonicalEffectId(effect.valueId.orEmpty()) == "roost")
            }

    private fun canonicalEffectId(effectId: String): String =
        PublicIds.canonical(effectId)

    /**
     * Status moves whose whole effect is in a callback, so no declared effect scores them: what each one is worth
     * on this board, in the same units as recovery (a full HP bar is 100). Null for every other move.
     */
    private fun calloutValue(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actor: BattlePokemonStateView?,
        target: BattlePokemonStateView?,
    ): Double? {
        actor ?: return null
        val missing = (1.0 - actor.hpFraction).coerceIn(0.0, 1.0)
        return when (PublicIds.canonical(candidate.moveId.orEmpty())) {
            // Half HP at the end of next turn, to whoever is in the slot then: discounted for the wait.
            "wish" -> minOf(0.5, missing) * 100.0 * WISH_DELAY_DISCOUNT
            // Both take the average: what the user gains plus what the target loses.
            "painsplit" -> target?.let { ((it.hpFraction - actor.hpFraction) * 100.0).coerceAtLeast(0.0) }
            // Every statused Pokemon on the team cured.
            "healbell", "aromatherapy" -> context.state.pokemon.count {
                it.side == BattleSide.ALLY && !it.fainted && it.statusId != null
            } * PARTY_CURE_VALUE
            // An eighth of the target's HP every turn it stays in, back to the user.
            "leechseed" -> target?.takeIf { foe -> foe.knownTypeIds.none { PublicIds.canonical(it) == "grass" } }
                ?.let { LEECH_SEED_TURNS * 12.5 }
            else -> null
        }
    }

    private const val WISH_DELAY_DISCOUNT = 0.6
    private const val PARTY_CURE_VALUE = 30.0
    private const val LEECH_SEED_TURNS = 2.5
    private const val GENERIC_STATUS_PRESSURE = 20.0
    private const val MAJOR_STATUS_PRESSURE = 35.0
    private const val ADDITIONAL_SCREEN_OPPORTUNITY_COST = 10.0
    private const val EXPIRING_EFFECT_TURNS = 1
    private val SCREEN_EFFECTS = setOf("reflect", "lightscreen", "auroraveil")
}
