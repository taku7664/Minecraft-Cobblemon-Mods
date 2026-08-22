package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

/**
 * Values a non-damaging move by the progress it can still make from public state.
 *
 * This keeps recovery, setup and screen decisions from treating every legal status move as the
 * same flat benefit. It does not predict a hidden opponent set or feed recommendations to Router.
 */
internal object LocalNonDamagingMoveEvaluator {
    fun pressure(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        accuracy: Double,
    ): Double {
        val actor = actor(candidate, context)
        val missingHp = (1.0 - (actor?.hpFraction ?: 1.0)).coerceIn(0.0, 1.0)
        val recovery = candidate.facts?.selfHealingFractionRange?.let { range ->
            val averageHealing = (range.minimum + range.maximum) / 2.0
            val effectiveHealing = minOf(averageHealing, missingHp)
            val publicLossAfterPreviousUse = repeatedRecoveryLoss(candidate, context, actor)
            val repeatedHabitLoss = repeatedPureRecoveryHabitLoss(candidate, context, actor)
            (effectiveHealing - publicLossAfterPreviousUse - repeatedHabitLoss).coerceAtLeast(0.0) * 100.0
        } ?: 0.0

        val effects = candidate.moveDetails?.effects?.effects.orEmpty()
        val declaresMajorStatus = effects.any {
            it.kind == BattleMoveEffectKind.STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET
        }
        val declaresPureRecovery = candidate.facts?.selfHealingFractionRange != null &&
            effects.all {
                it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER
            }
        val setupPressure = selfSetupPressure(effects, actor, accuracy)
        val target = selectedOpponent(candidate, context)
        val statusProbability = candidate.facts?.statusEffectProbability
        val status = when {
            declaresMajorStatus && target?.statusId != null -> 0.0
            statusProbability != null -> {
                val targetHpWeight = target?.hpFraction?.let { 0.5 + it * 0.5 } ?: 1.0
                statusProbability * MAJOR_STATUS_PRESSURE * targetHpWeight
            }
            declaresPureRecovery -> 0.0
            setupPressure != null -> setupPressure
            else -> (GENERIC_STATUS_PRESSURE - additionalScreenOpportunityCost(effects, context))
                .coerceAtLeast(0.0) * accuracy
        }
        return maxOf(recovery, status)
    }

    private fun repeatedRecoveryLoss(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actor: BattlePokemonStateView?,
    ): Double {
        val actorId = actor?.battlePokemonId ?: return 0.0
        val moveId = candidate.moveId ?: return 0.0
        if (context.memory.sameMoveRepeatCount < 1 || !sameEffect(moveId, context.memory.lastMoveId)) return 0.0
        val previousUse = context.state.observedEvents.lastOrNull { event ->
            event.kind == BattleObservedEventKind.MOVE_USED &&
                event.actorPokemonId == actorId &&
                sameEffect(event.publicValueId, moveId)
        } ?: return 0.0
        return context.state.observedEvents.asSequence()
            .filter { it.sequence > previousUse.sequence }
            .filter { it.kind == BattleObservedEventKind.HP_CHANGED && it.actorPokemonId == actorId }
            .mapNotNull { it.hpFractionDelta }
            .filter { it < 0.0 }
            .sumOf { -it }
            .coerceIn(0.0, 1.0)
    }

    /**
     * Stops a healthy actor from endlessly taking the same low-progress recovery line when the
     * public event stream is too sparse to reconstruct the preceding damage. The pressure scales
     * smoothly with HP and repetition, and disappears below the survival threshold so that a
     * genuinely endangered actor can still keep healing.
     */
    private fun repeatedPureRecoveryHabitLoss(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        actor: BattlePokemonStateView?,
    ): Double {
        val moveId = candidate.moveId ?: return 0.0
        if (context.memory.sameMoveRepeatCount < MINIMUM_RECOVERY_REPEATS_FOR_HABIT_LOSS) return 0.0
        if (!sameEffect(moveId, context.memory.lastMoveId)) return 0.0
        val effects = candidate.moveDetails?.effects?.effects.orEmpty()
        val pureRecovery = candidate.facts?.selfHealingFractionRange != null && effects.all {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER
        }
        if (!pureRecovery) return 0.0
        val hp = actor?.hpFraction ?: return 0.0
        if (hp <= RECOVERY_SURVIVAL_HP_THRESHOLD) return 0.0
        val healthyScale = ((hp - RECOVERY_SURVIVAL_HP_THRESHOLD) /
            (1.0 - RECOVERY_SURVIVAL_HP_THRESHOLD)).coerceIn(0.0, 1.0)
        val repeatPressure = (context.memory.sameMoveRepeatCount - 1)
            .coerceIn(1, MAXIMUM_RECOVERY_REPEAT_PRESSURE)
        return healthyScale * repeatPressure * RECOVERY_HABIT_LOSS_PER_REPEAT
    }

    private fun selfSetupPressure(
        effects: List<jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectView>,
        actor: BattlePokemonStateView?,
        accuracy: Double,
    ): Double? {
        val boostedStats = effects.asSequence()
            .filter { it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER }
            .flatMap { it.statStages.asSequence() }
            .filter { it.value > 0 }
            .map { canonicalEffectId(it.key) }
            .toSet()
        if (boostedStats.isEmpty()) return null
        val currentPositiveStage = actor?.statStages.orEmpty()
            .filterKeys { canonicalEffectId(it) in boostedStats }
            .values
            .maxOfOrNull { it.coerceAtLeast(0) }
            ?: 0
        return (GENERIC_STATUS_PRESSURE - currentPositiveStage * SETUP_PRESSURE_LOSS_PER_STAGE)
            .coerceAtLeast(0.0) * accuracy
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

    private fun selectedOpponent(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): BattlePokemonStateView? {
        val targetSlot = candidate.targets.singleOrNull { it.side == BattleSide.OPPONENT }?.slot ?: return null
        return context.state.pokemon.firstOrNull {
            it.side == BattleSide.OPPONENT && it.activeSlot == targetSlot && !it.fainted
        }
    }

    private fun sameEffect(first: String?, second: String?): Boolean =
        first != null && second != null && canonicalEffectId(first) == canonicalEffectId(second)

    private fun canonicalEffectId(effectId: String): String =
        effectId.substringAfter(':').lowercase().filter { it.isLetterOrDigit() }

    private const val GENERIC_STATUS_PRESSURE = 20.0
    private const val MAJOR_STATUS_PRESSURE = 35.0
    private const val SETUP_PRESSURE_LOSS_PER_STAGE = 6.0
    private const val ADDITIONAL_SCREEN_OPPORTUNITY_COST = 10.0
    private const val EXPIRING_EFFECT_TURNS = 1
    private const val MINIMUM_RECOVERY_REPEATS_FOR_HABIT_LOSS = 2
    private const val RECOVERY_SURVIVAL_HP_THRESHOLD = 0.65
    private const val RECOVERY_HABIT_LOSS_PER_REPEAT = 0.18
    private const val MAXIMUM_RECOVERY_REPEAT_PRESSURE = 4
    private val SCREEN_EFFECTS = setOf("reflect", "lightscreen", "auroraveil")
}
