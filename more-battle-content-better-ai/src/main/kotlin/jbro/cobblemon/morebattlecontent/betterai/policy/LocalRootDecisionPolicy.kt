package jbro.cobblemon.morebattlecontent.betterai.policy

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.mechanics.PublicSwitchEntryHazardCalculator
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector

internal enum class LocalSwitchDecisionReason {
    SURVIVAL_GAIN,
    SAFE_ENTRY,
    TACTICAL_GAIN,
}

internal enum class LocalSwitchVetoReason {
    FATAL_REENTRY,
    PRESERVE_SETUP,
}

internal data class LocalRootDecisionRefinement(
    val ranked: List<LocalBattleActionRank>,
    val switchReasonsByActionId: Map<String, Set<LocalSwitchDecisionReason>> = emptyMap(),
    val switchVetoes: Set<LocalSwitchVetoReason> = emptySet(),
)

/**
 * Applies root-only switch intent and veto rules after lookahead has measured execution odds.
 *
 * This does not invent another utility score. It removes an optional switch only when a useful
 * same-slot action can still execute and public state proves that switching discards a concrete
 * resource: the outgoing Pokemon's last usable turn or its accumulated setup.
 */
internal object LocalRootDecisionPolicy {
    fun refine(
        ranked: List<LocalBattleActionRank>,
        context: BattleDecisionContext,
    ): LocalRootDecisionRefinement {
        if (ranked.size < 2) {
            return LocalRootDecisionRefinement(ranked)
        }

        val switchReasonsByActionId = linkedMapOf<String, Set<LocalSwitchDecisionReason>>()
        val switchVetoes = linkedSetOf<LocalSwitchVetoReason>()
        val blockedActionIds = linkedSetOf<String>()
        ranked.forEach { switchRank ->
            switchComponents(switchRank).forEach componentLoop@{ (switchAction, switchOutcome) ->
            val actorSlot = switchAction.actorSlot ?: return@componentLoop
            val active = context.state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.activeSlot == actorSlot && !it.fainted && it.hpFraction > 0.0
            } ?: return@componentLoop
            val credibleStays = ranked.mapNotNull { rank ->
                if (switchRank.comparisonValue - rank.comparisonValue > MAXIMUM_CREDIBLE_STAY_SCORE_GAP) {
                    return@mapNotNull null
                }
                val stay = componentForSlot(rank, actorSlot) ?: return@mapNotNull null
                if (!sameOtherSlotActions(switchRank, rank, actorSlot) ||
                    !isCredibleProgress(stay, rank.executionProbability)
                ) {
                    return@mapNotNull null
                }
                rank to stay
            }
            if (credibleStays.isEmpty()) return@componentLoop

            val reasons = linkedSetOf<LocalSwitchDecisionReason>()
            switchOutcome.survivalPositionImprovement?.takeIf {
                it >= MATERIAL_SURVIVAL_GAIN
            }?.let { reasons += LocalSwitchDecisionReason.SURVIVAL_GAIN }
            if (switchRank.worstResponseHpRetention >= SAFE_ENTRY_RETENTION) {
                reasons += LocalSwitchDecisionReason.SAFE_ENTRY
            }
            val bestCredibleStay = credibleStays.maxOf { it.first.comparisonValue }
            if (switchRank.comparisonValue - bestCredibleStay >= MATERIAL_TACTICAL_GAIN_SCORE) {
                reasons += LocalSwitchDecisionReason.TACTICAL_GAIN
            }
            if (reasons.isNotEmpty()) {
                val rootActionId = switchRank.outcome.candidate.actionId
                switchReasonsByActionId[rootActionId] = switchReasonsByActionId[rootActionId].orEmpty() + reasons
            }

            val vetoes = linkedSetOf<LocalSwitchVetoReason>()
            val reentryLoss = PublicSwitchEntryHazardCalculator.hpLoss(
                context.state.field,
                BattleSide.ALLY,
                active,
            )
            val projectedReentryHp = LocalSwitchStateProjector.projectedSwitchOutHp(active)
            if (reentryLoss != null && reentryLoss > 0.0 && projectedReentryHp <= reentryLoss + HP_EPSILON) {
                vetoes += LocalSwitchVetoReason.FATAL_REENTRY
            }

            val positiveStages = active.statStages.values.sumOf { it.coerceAtLeast(0) }
            val stayCanSurviveKnownResponse = credibleStays.any { it.first.worstResponseHpRetention > 0.0 }
            if (positiveStages >= SETUP_PRESERVATION_STAGES && stayCanSurviveKnownResponse) {
                vetoes += LocalSwitchVetoReason.PRESERVE_SETUP
            }

            if (vetoes.isNotEmpty()) {
                blockedActionIds += switchRank.outcome.candidate.actionId
                switchVetoes += vetoes
            }
            }
        }

        val eligible = ranked.filterNot { it.outcome.candidate.actionId in blockedActionIds }
        return LocalRootDecisionRefinement(
            ranked = eligible.ifEmpty { ranked },
            switchReasonsByActionId = switchReasonsByActionId,
            switchVetoes = switchVetoes,
        )
    }

    private fun switchComponents(rank: LocalBattleActionRank): List<Pair<jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate, LocalBattleActionOutcome>> =
        atomicComponents(rank).filter { it.first.kind == BattleActionKind.SWITCH }

    private fun componentForSlot(
        rank: LocalBattleActionRank,
        slot: Int,
    ): LocalBattleActionOutcome? = atomicComponents(rank).firstOrNull { it.first.actorSlot == slot }?.second

    private fun atomicComponents(
        rank: LocalBattleActionRank,
    ): List<Pair<jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate, LocalBattleActionOutcome>> {
        val candidate = rank.outcome.candidate
        if (candidate.kind != BattleActionKind.COMPOSITE) return listOf(candidate to rank.outcome)
        val outcomesById = rank.outcome.componentOutcomes.associateBy { it.candidate.actionId }
        return candidate.componentActions.mapNotNull { component ->
            outcomesById[component.actionId]?.let { component to it }
        }
    }

    private fun sameOtherSlotActions(
        first: LocalBattleActionRank,
        second: LocalBattleActionRank,
        excludedSlot: Int,
    ): Boolean {
        fun signatures(rank: LocalBattleActionRank) = atomicComponents(rank)
            .filter { it.first.actorSlot != excludedSlot }
            .associate { it.first.actorSlot to it.first.actionId }
        return signatures(first) == signatures(second)
    }

    private fun isCredibleProgress(
        outcome: LocalBattleActionOutcome,
        executionProbability: Double,
    ): Boolean {
        val action = outcome.candidate
        if (action.kind != BattleActionKind.USE_MOVE || outcome.publiclyInert ||
            executionProbability < MINIMUM_CREDIBLE_EXECUTION_PROBABILITY
        ) return false
        if (outcome.executableDamageActions > 0) return true
        if (action.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS) return false
        return action.moveDetails?.effects?.effects.orEmpty().any { effect ->
            when (effect.kind) {
                BattleMoveEffectKind.STATUS,
                BattleMoveEffectKind.VOLATILE_STATUS,
                BattleMoveEffectKind.SIDE_CONDITION,
                BattleMoveEffectKind.FIELD_CONDITION,
                BattleMoveEffectKind.WEATHER,
                BattleMoveEffectKind.TERRAIN,
                BattleMoveEffectKind.SWITCH_USER,
                BattleMoveEffectKind.SWITCH_TARGET,
                -> true
                BattleMoveEffectKind.STAT_STAGE -> when (effect.target) {
                    BattleMoveEffectTarget.USER -> effect.statStages.values.any { it > 0 }
                    BattleMoveEffectTarget.SELECTED_TARGET -> effect.statStages.values.any { it < 0 }
                    else -> false
                }
                else -> false
            }
        }
    }

    private const val MINIMUM_CREDIBLE_EXECUTION_PROBABILITY = 0.25
    private const val MAXIMUM_CREDIBLE_STAY_SCORE_GAP = 199.0
    private const val SETUP_PRESERVATION_STAGES = 2
    private const val MATERIAL_SURVIVAL_GAIN = 0.25
    private const val MATERIAL_TACTICAL_GAIN_SCORE = 20.0
    private const val SAFE_ENTRY_RETENTION = 0.50
    private const val HP_EPSILON = 1e-9
}
