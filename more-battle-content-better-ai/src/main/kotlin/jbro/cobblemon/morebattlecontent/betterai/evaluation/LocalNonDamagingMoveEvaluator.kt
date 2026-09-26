package jbro.cobblemon.morebattlecontent.betterai.evaluation

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalStallingProtectionRules

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
    ): Score {
        val actor = actor(candidate, context)
        val missingHp = (1.0 - (actor?.hpFraction ?: 1.0)).coerceIn(0.0, 1.0)
        val recovery = candidate.facts?.selfHealingFractionRange?.let { range ->
            val averageHealing = (range.minimum + range.maximum) / 2.0
            val effectiveHealing = minOf(averageHealing, missingHp)
            effectiveHealing * 100.0
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
                statusProbability * MAJOR_STATUS_PRESSURE * targetHpWeight
            }
            declaresPureRecovery -> 0.0
            setupPressure != null -> {
                usesSetupPressure = true
                setupPressure
            }
            else -> (GENERIC_STATUS_PRESSURE - additionalScreenOpportunityCost(effects, context))
                .coerceAtLeast(0.0) * accuracy * protectionSuccessProbability
        }
        val total = maxOf(recovery, status)
        return Score(
            total = total,
            statStageUtility = if (usesSetupPressure && status >= recovery) status else 0.0,
        )
    }

    private fun additionalScreenOpportunityCost(
        effects: List<jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectView>,
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
        effectId.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }

    private const val GENERIC_STATUS_PRESSURE = 20.0
    private const val MAJOR_STATUS_PRESSURE = 35.0
    private const val ADDITIONAL_SCREEN_OPPORTUNITY_COST = 10.0
    private const val EXPIRING_EFFECT_TURNS = 1
    private val SCREEN_EFFECTS = setOf("reflect", "lightscreen", "auroraveil")
}
