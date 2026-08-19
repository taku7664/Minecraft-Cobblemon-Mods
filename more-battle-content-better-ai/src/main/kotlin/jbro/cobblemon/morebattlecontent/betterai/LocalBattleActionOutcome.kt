package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnockoutAssessment
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile

/**
 * Local-only projection used to compare every legal action on one outcome scale.
 *
 * It consumes the same public state and mechanical facts as the Router, but utility, tiers and
 * preferences stay inside the local Brain and never cross the Router request boundary.
 */
internal data class LocalBattleActionOutcome(
    val candidate: BattleActionCandidate,
    val tacticalUtility: Double,
    val expectedDamageFraction: Double,
    val secureStandardKnockouts: Int,
    val executableDamageActions: Int,
    val publiclyInert: Boolean,
    val entryFaints: Boolean,
    val switchPostEntryHp: Double?,
    val currentDefensiveExposure: Double?,
    val resultingDefensiveExposure: Double?,
    val survivalPositionImprovement: Double?,
    val componentOutcomes: List<LocalBattleActionOutcome> = emptyList(),
)

internal data class LocalBattleActionRank(
    val outcome: LocalBattleActionOutcome,
    val decisionTier: Int,
    val comparisonValue: Double,
)

internal object LocalBattleActionPolicy {
    fun rank(
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
    ): List<LocalBattleActionRank> {
        val outcomes = context.candidates.map { candidate ->
            LocalBattleActionOutcomeEvaluator.evaluate(candidate, context, strategy, profile)
        }
        val executableDamageSlots = outcomes.asSequence()
            .flatMap(::atomicOutcomes)
            .filter { it.executableDamageActions > 0 && !it.publiclyInert }
            .mapNotNull { it.candidate.actorSlot }
            .toSet()
        return outcomes.map { outcome ->
            val tier = decisionTier(outcome, context, executableDamageSlots)
            LocalBattleActionRank(
                outcome = outcome,
                decisionTier = tier,
                comparisonValue = tier * TIER_DISTANCE +
                    outcome.secureStandardKnockouts * SECURE_KNOCKOUT_DISTANCE +
                    outcome.tacticalUtility,
            )
        }.sortedWith(
            compareByDescending<LocalBattleActionRank> { it.decisionTier }
                .thenByDescending { it.outcome.secureStandardKnockouts }
                .thenByDescending { it.outcome.tacticalUtility }
                .thenBy { it.outcome.candidate.actionId },
        )
    }

    private fun decisionTier(
        outcome: LocalBattleActionOutcome,
        context: BattleDecisionContext,
        executableDamageSlots: Set<Int>,
    ): Int {
        if (outcome.entryFaints || outcome.publiclyInert) return TIER_PUBLICLY_BAD
        if (outcome.secureStandardKnockouts > 0) return TIER_SECURE_KNOCKOUT
        return when (outcome.candidate.kind) {
            BattleActionKind.FORFEIT -> TIER_FORFEIT
            BattleActionKind.WAIT -> TIER_PUBLICLY_BAD
            BattleActionKind.SWITCH -> switchTier(
                outcome,
                context,
                outcome.candidate.actorSlot in executableDamageSlots,
            )
            BattleActionKind.COMPOSITE -> if (outcome.componentOutcomes.any { component ->
                    component.candidate.kind == BattleActionKind.SWITCH &&
                        switchTier(
                            component,
                            context,
                            component.candidate.actorSlot in executableDamageSlots,
                        ) == TIER_TEMPO_LOSS
                }
            ) {
                TIER_TEMPO_LOSS
            } else {
                TIER_ORDINARY
            }
            BattleActionKind.USE_MOVE -> TIER_ORDINARY
        }
    }

    private fun atomicOutcomes(outcome: LocalBattleActionOutcome): Sequence<LocalBattleActionOutcome> =
        if (outcome.componentOutcomes.isEmpty()) sequenceOf(outcome) else outcome.componentOutcomes.asSequence()

    private fun switchTier(
        outcome: LocalBattleActionOutcome,
        context: BattleDecisionContext,
        executableDamageExists: Boolean,
    ): Int {
        if (!executableDamageExists) return TIER_ORDINARY
        val improvement = outcome.survivalPositionImprovement
        if (improvement == null) return TIER_ORDINARY
        val recentlySwitched = context.memory.turnsSinceLastSwitch?.let { it <= 1 } == true
        val requiredImprovement = if (recentlySwitched) {
            RECENT_SWITCH_SURVIVAL_IMPROVEMENT +
                (context.memory.switchesThisBattle - 1).coerceAtLeast(0) * REPEATED_SWITCH_IMPROVEMENT_STEP
        } else {
            MATERIAL_SURVIVAL_IMPROVEMENT
        }
        val overwhelmingPublicEscape = recentlySwitched &&
            outcome.currentDefensiveExposure?.let { it >= OVERWHELMING_PUBLIC_EXPOSURE } == true &&
            improvement >= RECENT_SWITCH_SURVIVAL_IMPROVEMENT
        return if (improvement >= requiredImprovement || overwhelmingPublicEscape) {
            TIER_ORDINARY
        } else {
            TIER_TEMPO_LOSS
        }
    }

    private const val TIER_FORFEIT = 0
    private const val TIER_PUBLICLY_BAD = 1
    private const val TIER_TEMPO_LOSS = 2
    private const val TIER_ORDINARY = 3
    private const val TIER_SECURE_KNOCKOUT = 4
    private const val TIER_DISTANCE = 10_000.0
    private const val SECURE_KNOCKOUT_DISTANCE = 1_000.0
    private const val MATERIAL_SURVIVAL_IMPROVEMENT = 0.25
    private const val RECENT_SWITCH_SURVIVAL_IMPROVEMENT = 0.75
    private const val REPEATED_SWITCH_IMPROVEMENT_STEP = 2.0
    private const val OVERWHELMING_PUBLIC_EXPOSURE = 4.0
}

internal object LocalBattleActionOutcomeEvaluator {
    fun evaluate(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
    ): LocalBattleActionOutcome = when (candidate.kind) {
        BattleActionKind.USE_MOVE -> move(candidate, context, strategy, profile)
        BattleActionKind.SWITCH -> switch(candidate, context, strategy, profile)
        BattleActionKind.COMPOSITE -> composite(candidate, context, strategy, profile)
        BattleActionKind.WAIT,
        BattleActionKind.FORFEIT,
        -> empty(candidate, LocalTacticalScorer.score(candidate, context, strategy, profile))
    }

    private fun move(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
    ): LocalBattleActionOutcome {
        val facts = candidate.facts
        val details = candidate.moveDetails
        val accuracy = facts?.baseAccuracyProbability ?: details?.accuracy?.div(100.0) ?: 0.0
        val damageRange = facts?.standardDamageFractionRange
        val mechanics = LocalPublicMechanicsKernel.projectMove(candidate, context)
        val baseExpectedDamage = damageRange?.let { (it.minimum + it.maximum) / 2.0 * accuracy } ?: 0.0
        val expectedDamage = baseExpectedDamage * mechanics.knownDamageMultiplier
        val mechanicsUtilityAdjustment = (expectedDamage - baseExpectedDamage) * DAMAGE_UTILITY_SCALE
        val publiclyInert = isPubliclyInert(candidate, context) || mechanics.publiclyNullified
        val executableDamage = if (isExecutableDamage(candidate, context) && !mechanics.publiclyNullified) 1 else 0
        val adjustedMinimumDamage = damageRange?.minimum?.times(mechanics.knownDamageMultiplier)
        val secureKnockout = if (
            facts?.standardKnockoutAssessment == BattleKnockoutAssessment.GUARANTEED &&
            accuracy >= CERTAIN_ACCURACY && executableDamage > 0 &&
            adjustedMinimumDamage != null && mechanics.targetHpFraction?.let { adjustedMinimumDamage >= it } == true
        ) {
            1
        } else {
            0
        }
        return LocalBattleActionOutcome(
            candidate = candidate,
            tacticalUtility = LocalTacticalScorer.score(candidate, context, strategy, profile) + mechanicsUtilityAdjustment,
            expectedDamageFraction = expectedDamage,
            secureStandardKnockouts = secureKnockout,
            executableDamageActions = executableDamage,
            publiclyInert = publiclyInert,
            entryFaints = false,
            switchPostEntryHp = null,
            currentDefensiveExposure = null,
            resultingDefensiveExposure = null,
            survivalPositionImprovement = null,
        )
    }

    private fun switch(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
    ): LocalBattleActionOutcome {
        val active = LocalPublicPositionFacts.activeAlly(candidate, context)
        val target = LocalPublicPositionFacts.switchTarget(candidate, context)
        val postEntryHp = target?.let { LocalTacticalSituationalEvaluator.postEntryHp(candidate, it.hpFraction) }
        val currentExposure = active?.let { LocalPublicPositionFacts.defensiveExposure(it.knownTypeIds, context) }
        val targetExposure = target?.let { LocalPublicPositionFacts.defensiveExposure(it.knownTypeIds, context) }
        val improvement = if (active != null && target != null && postEntryHp != null &&
            currentExposure != null && targetExposure != null
        ) {
            LocalPublicPositionFacts.survivalPosition(postEntryHp, targetExposure) -
                LocalPublicPositionFacts.survivalPosition(active.hpFraction, currentExposure)
        } else {
            null
        }
        return LocalBattleActionOutcome(
            candidate = candidate,
            tacticalUtility = LocalTacticalScorer.score(candidate, context, strategy, profile),
            expectedDamageFraction = 0.0,
            secureStandardKnockouts = 0,
            executableDamageActions = 0,
            publiclyInert = target != null && postEntryHp == null,
            entryFaints = target != null && target.hpFraction > 0.0 && postEntryHp == 0.0,
            switchPostEntryHp = postEntryHp,
            currentDefensiveExposure = currentExposure,
            resultingDefensiveExposure = targetExposure,
            survivalPositionImprovement = improvement,
        )
    }

    private fun composite(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
    ): LocalBattleActionOutcome {
        val components = candidate.componentActions.map { evaluate(it, context, strategy, profile) }
        val secureTargets = candidate.componentActions.zip(components)
            .filter { (_, outcome) -> outcome.secureStandardKnockouts > 0 }
            .flatMap { (action, _) -> action.targets.filter { it.side == BattleSide.OPPONENT } }
            .distinct()
            .size
        return LocalBattleActionOutcome(
            candidate = candidate,
            tacticalUtility = LocalTacticalScorer.score(candidate, context, strategy, profile),
            expectedDamageFraction = components.sumOf(LocalBattleActionOutcome::expectedDamageFraction),
            secureStandardKnockouts = secureTargets,
            executableDamageActions = components.sumOf(LocalBattleActionOutcome::executableDamageActions),
            publiclyInert = components.isNotEmpty() && components.all(LocalBattleActionOutcome::publiclyInert),
            entryFaints = components.any(LocalBattleActionOutcome::entryFaints),
            switchPostEntryHp = null,
            currentDefensiveExposure = null,
            resultingDefensiveExposure = null,
            survivalPositionImprovement = null,
            componentOutcomes = components,
        )
    }

    private fun empty(candidate: BattleActionCandidate, tacticalUtility: Double) = LocalBattleActionOutcome(
        candidate = candidate,
        tacticalUtility = tacticalUtility,
        expectedDamageFraction = 0.0,
        secureStandardKnockouts = 0,
        executableDamageActions = 0,
        publiclyInert = candidate.kind == BattleActionKind.WAIT,
        entryFaints = false,
        switchPostEntryHp = null,
        currentDefensiveExposure = null,
        resultingDefensiveExposure = null,
        survivalPositionImprovement = null,
    )

    private fun isExecutableDamage(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        val details = candidate.moveDetails ?: return false
        if (details.damageCategory == BattleMoveDamageCategory.STATUS || details.power <= 0.0) return false
        if (candidate.facts?.typeChartMultiplier == 0.0) return false
        if (candidate.facts?.standardDamageFractionRange?.maximum == 0.0) return false
        return !isPubliclyInert(candidate, context)
    }

    private fun isPubliclyInert(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean =
        LocalTacticalSituationalEvaluator.activePersistentEffectRefreshPenalty(candidate, context) > 0.0 ||
            LocalTacticalSituationalEvaluator.expiredFirstActiveTurnPenalty(candidate, context) > 0.0 ||
            LocalTacticalSituationalEvaluator.saturatedStatStagePenalty(candidate, context) > 0.0 ||
            LocalTacticalSituationalEvaluator.unmetPublicRequirementPenalty(candidate, context) > 0.0 ||
            LocalTacticalSituationalEvaluator.recentPublicFailurePenalty(candidate, context) > 0.0 ||
            LocalTacticalSituationalEvaluator.consecutiveUseForbiddenPenalty(candidate, context) > 0.0

    private const val CERTAIN_ACCURACY = 0.999
    private const val DAMAGE_UTILITY_SCALE = 100.0
}

internal object LocalPublicPositionFacts {
    fun activeAlly(candidate: BattleActionCandidate, context: BattleDecisionContext): BattlePokemonStateView? =
        candidate.actorSlot?.let { actorSlot ->
            context.state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.activeSlot == actorSlot && !it.fainted
            }
        } ?: context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted
        }

    fun switchTarget(candidate: BattleActionCandidate, context: BattleDecisionContext): BattlePokemonStateView? =
        candidate.switchPokemonId?.let { targetId ->
            context.state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.battlePokemonId == targetId && !it.fainted
            }
        }

    fun defensiveExposure(defenderTypes: Set<String>, context: BattleDecisionContext): Double? =
        if (defenderTypes.isEmpty()) {
            null
        } else {
            PublicSwitchTypeFactsCalculator.activeOpponentTypeChartMultipliers(defenderTypes, context)
                .maxOfOrNull(PublicActiveOpponentTypeChartMultiplier::multiplier)
        }

    fun survivalPosition(hpFraction: Double, defensiveExposure: Double): Double =
        hpFraction / defensiveExposure.coerceAtLeast(IMMUNITY_EXPOSURE_FLOOR)

    private const val IMMUNITY_EXPOSURE_FLOOR = 0.25
}
