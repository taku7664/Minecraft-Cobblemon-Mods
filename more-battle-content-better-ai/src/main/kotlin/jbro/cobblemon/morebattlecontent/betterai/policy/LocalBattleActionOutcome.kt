package jbro.cobblemon.morebattlecontent.betterai.policy

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnockoutAssessment
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalPublicPositionFacts
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalSituationalEvaluator
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicActionOutcomeProjection
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalRiskAttitude
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicActionOutcomeProjector

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
    /**
     * Knockout material already baked into [tacticalUtility].
     *
     * The recursive search re-derives knockout value from the actual damage rolls, so it has to
     * remove the root scorer's copy. It used to subtract a hard-coded `250` per secure knockout while
     * the scorer had also added up to `50` more from the situational evaluator, so the correction
     * never matched what was there.
     */
    val knockoutUtility: Double = 0.0,
    val componentOutcomes: List<LocalBattleActionOutcome> = emptyList(),
)

internal data class LocalBattleActionRank(
    val outcome: LocalBattleActionOutcome,
    val decisionTier: Int,
    val comparisonValue: Double,
    val lookaheadUtility: Double = 0.0,
    val executionProbability: Double = 1.0,
    val worstResponseHpRetention: Double = 1.0,
)

internal object LocalBattleActionPolicy {
    fun rank(
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): List<LocalBattleActionRank> {
        val outcomes = context.candidates.map { candidate ->
            LocalBattleActionOutcomeEvaluator.evaluate(candidate, context, strategy, profile, tuning)
        }
        val executableDamageSlots = outcomes.asSequence()
            .flatMap(::atomicOutcomes)
            .filter { it.executableDamageActions > 0 && !it.publiclyInert }
            .mapNotNull { it.candidate.actorSlot }
            .toSet()
        return sort(outcomes.map { outcome ->
            val tier = decisionTier(outcome, context, executableDamageSlots, tuning)
            // Knockout material is credited once, inside tacticalUtility, by the situational
            // evaluator. The legacy path added a second flat SECURE_KNOCKOUT_BONUS here on top of
            // damage pressure that already contained the target's remaining HP, so a guaranteed
            // knockout was priced at up to 3.0 board points when the board says it is worth 2.0 plus
            // the HP taken.
            val legacySecureKnockoutBonus = if (tuning.legacyRawPowerFallback) {
                outcome.secureStandardKnockouts * SECURE_KNOCKOUT_BONUS
            } else {
                0.0
            }
            LocalBattleActionRank(
                outcome = outcome,
                decisionTier = tier,
                comparisonValue = tierAdjustment(tier) + legacySecureKnockoutBonus + outcome.tacticalUtility,
            )
        })
    }

    fun sort(ranks: List<LocalBattleActionRank>): List<LocalBattleActionRank> = ranks.sortedWith(
        compareByDescending<LocalBattleActionRank>(LocalBattleActionRank::comparisonValue)
            .thenBy { it.outcome.candidate.actionId },
    )

    private fun tierAdjustment(tier: Int): Double = when (tier) {
        TIER_FORFEIT -> FORFEIT_TIER_ADJUSTMENT
        TIER_PUBLICLY_BAD -> PUBLICLY_BAD_TIER_ADJUSTMENT
        TIER_TEMPO_LOSS -> TEMPO_LOSS_TIER_ADJUSTMENT
        TIER_ORDINARY,
        TIER_SECURE_KNOCKOUT,
        -> 0.0
        else -> 0.0
    }

    private fun decisionTier(
        outcome: LocalBattleActionOutcome,
        context: BattleDecisionContext,
        executableDamageSlots: Set<Int>,
        tuning: LocalDecisionTuning,
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
                tuning,
            )
            BattleActionKind.COMPOSITE -> if (outcome.componentOutcomes.any { component ->
                    component.candidate.kind == BattleActionKind.SWITCH &&
                        switchTier(
                            component,
                            context,
                            component.candidate.actorSlot in executableDamageSlots,
                            tuning,
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
        tuning: LocalDecisionTuning,
    ): Int {
        if (!executableDamageExists) return TIER_ORDINARY
        val improvement = outcome.survivalPositionImprovement
        if (improvement == null) return TIER_ORDINARY
        val recentlySwitched = context.memory.turnsSinceLastSwitch?.let { it <= 1 } == true
        val offensiveImprovement = LocalLookaheadStateEvaluator
            .switchOffensivePressureImprovement(outcome.candidate, context)
            ?.coerceAtLeast(0.0)
            ?: 0.0
        val requiredOffensiveImprovement = if (recentlySwitched) {
            RECENT_SWITCH_OFFENSIVE_IMPROVEMENT +
                context.memory.switchesThisBattle * REPEATED_SWITCH_OFFENSIVE_STEP
        } else {
            MATERIAL_OFFENSIVE_IMPROVEMENT
        }
        // Stated in survival turns, the same unit survivalPositionImprovement now reports.
        val requiredImprovement = if (recentlySwitched) {
            tuning.recentSwitchSurvivalTurnGain +
                (context.memory.switchesThisBattle - 1).coerceAtLeast(0) * tuning.repeatedSwitchTurnGainStep
        } else {
            tuning.materialSurvivalTurnGain
        }
        val overwhelmingPublicEscape = recentlySwitched &&
            LocalPublicPositionFacts.activeAlly(outcome.candidate, context)?.let { active ->
                LocalPublicPositionFacts.isOverwhelmingPublicThreat(active, context, tuning)
            } == true &&
            improvement >= tuning.recentSwitchSurvivalTurnGain
        return if (
            improvement >= requiredImprovement ||
            offensiveImprovement >= requiredOffensiveImprovement ||
            overwhelmingPublicEscape
        ) {
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
    internal const val SECURE_KNOCKOUT_BONUS = 250.0
    private const val TEMPO_LOSS_TIER_ADJUSTMENT = -250.0
    private const val PUBLICLY_BAD_TIER_ADJUSTMENT = -1_000.0
    private const val FORFEIT_TIER_ADJUSTMENT = -10_000.0
    private const val MATERIAL_OFFENSIVE_IMPROVEMENT = 0.25
    private const val RECENT_SWITCH_OFFENSIVE_IMPROVEMENT = 0.60
    private const val REPEATED_SWITCH_OFFENSIVE_STEP = 0.10
}

internal object LocalBattleActionOutcomeEvaluator {
    fun evaluate(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): LocalBattleActionOutcome = when (candidate.kind) {
        BattleActionKind.USE_MOVE -> move(candidate, context, strategy, profile, tuning)
        BattleActionKind.SWITCH -> switch(candidate, context, strategy, profile, tuning)
        BattleActionKind.COMPOSITE -> composite(candidate, context, strategy, profile, tuning)
        BattleActionKind.WAIT,
        BattleActionKind.FORFEIT,
        -> empty(candidate, LocalTacticalScorer.score(candidate, context, strategy, profile, tuning))
    }

    private fun move(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
    ): LocalBattleActionOutcome {
        val facts = candidate.facts
        val details = candidate.moveDetails
        val accuracy = facts?.baseAccuracyProbability ?: details?.accuracy?.div(100.0) ?: 0.0
        val damageRange = facts?.standardDamageFractionRange
        // How this trainer expects the dice to fall. The projector and the cancellation below have to
        // read the same attitude, or the scorer's pressure stops cancelling and the move is paid for
        // twice.
        val riskAttitude = profile.personality.riskTolerance
        val projection = PublicActionOutcomeProjector.project(candidate, context, riskAttitude = riskAttitude)
        val baseExpectedDamage = damageRange?.let {
            LocalRiskAttitude.expectedFraction(it.minimum, it.maximum, riskAttitude) * accuracy
        } ?: 0.0
        val expectedDamage = projection.expectedDamageFraction ?: 0.0
        // When the facts carry a damage range, `- baseExpectedDamage` cancels the scorer's unclamped
        // pressure and leaves the clamped, mechanics-aware figure. When they do not, the scorer used
        // its raw-power fallback instead, and that estimate has to be cancelled here for exactly the
        // same reason - otherwise the projector's declared damage lands on top of it and the move is
        // paid for twice.
        val unprojectedPressureAlreadyScored = if (damageRange == null && projection.expectedDamageFraction != null) {
            LocalTacticalScorer.unprojectedPressureOf(candidate, context, tuning)
        } else {
            0.0
        }
        val mechanicsUtilityAdjustment = (expectedDamage - baseExpectedDamage) * DAMAGE_UTILITY_SCALE -
            unprojectedPressureAlreadyScored -
            (projection.expectedSelfRecoilFraction ?: 0.0) * SELF_HP_UTILITY_SCALE
        val publiclyInert = isPubliclyInert(candidate, context) ||
            projection.publiclyNullified ||
            isWastedPureRecovery(candidate, projection)
        val executableDamage = if (isExecutableDamage(candidate, context) && !projection.publiclyNullified) 1 else 0
        val adjustedMinimumDamage = projection.damageOnHitFractionRange?.minimum
        val secureKnockout = if (
            facts?.standardKnockoutAssessment == BattleKnockoutAssessment.GUARANTEED &&
            accuracy >= CERTAIN_ACCURACY && executableDamage > 0 &&
            adjustedMinimumDamage != null && projection.targetHpBefore?.let { adjustedMinimumDamage >= it } == true
        ) {
            1
        } else {
            0
        }
        return LocalBattleActionOutcome(
            candidate = candidate,
            tacticalUtility = LocalTacticalScorer.score(candidate, context, strategy, profile, tuning) +
                mechanicsUtilityAdjustment,
            expectedDamageFraction = expectedDamage,
            secureStandardKnockouts = secureKnockout,
            executableDamageActions = executableDamage,
            publiclyInert = publiclyInert,
            entryFaints = false,
            switchPostEntryHp = null,
            currentDefensiveExposure = null,
            resultingDefensiveExposure = null,
            survivalPositionImprovement = null,
            knockoutUtility = LocalTacticalScorer.knockoutUtility(candidate, tuning, context),
        )
    }

    private fun switch(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
    ): LocalBattleActionOutcome {
        val active = LocalPublicPositionFacts.activeAlly(candidate, context)
        val target = LocalPublicPositionFacts.switchTarget(candidate, context)
        val postEntryHp = target?.let { LocalTacticalSituationalEvaluator.postEntryHp(candidate, it.hpFraction) }
        val currentExposure = active?.let {
            LocalPublicPositionFacts.defensiveExposure(it, context, tuning = tuning)
        }
        val targetExposure = target?.let {
            LocalPublicPositionFacts.defensiveExposure(it, context, candidate.actorSlot, tuning)
        }
        val improvement = if (active != null && target != null && postEntryHp != null &&
            currentExposure != null && targetExposure != null
        ) {
            LocalPublicPositionFacts.survivalPosition(postEntryHp, targetExposure, tuning) -
                LocalPublicPositionFacts.survivalPosition(active.hpFraction, currentExposure, tuning)
        } else {
            null
        }
        return LocalBattleActionOutcome(
            candidate = candidate,
            tacticalUtility = LocalTacticalScorer.score(candidate, context, strategy, profile, tuning),
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
        tuning: LocalDecisionTuning,
    ): LocalBattleActionOutcome {
        val components = candidate.componentActions.map { evaluate(it, context, strategy, profile, tuning) }
        val secureTargets = candidate.componentActions.zip(components)
            .filter { (_, outcome) -> outcome.secureStandardKnockouts > 0 }
            .flatMap { (action, _) -> action.targets.filter { it.side == BattleSide.OPPONENT } }
            .distinct()
            .size
        return LocalBattleActionOutcome(
            candidate = candidate,
            tacticalUtility = LocalTacticalScorer.score(candidate, context, strategy, profile, tuning),
            expectedDamageFraction = components.sumOf(LocalBattleActionOutcome::expectedDamageFraction),
            secureStandardKnockouts = secureTargets,
            executableDamageActions = components.sumOf(LocalBattleActionOutcome::executableDamageActions),
            publiclyInert = components.isNotEmpty() && components.all(LocalBattleActionOutcome::publiclyInert),
            entryFaints = components.any(LocalBattleActionOutcome::entryFaints),
            switchPostEntryHp = null,
            currentDefensiveExposure = null,
            resultingDefensiveExposure = null,
            survivalPositionImprovement = null,
            knockoutUtility = components.sumOf(LocalBattleActionOutcome::knockoutUtility),
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

    private fun isWastedPureRecovery(
        candidate: BattleActionCandidate,
        projection: PublicActionOutcomeProjection,
    ): Boolean {
        val effects = candidate.moveDetails?.effects?.effects.orEmpty()
        if (effects.isEmpty() || effects.any {
                it.kind != BattleMoveEffectKind.HEAL_FRACTION || it.target != BattleMoveEffectTarget.USER
            }
        ) return false
        return projection.actorHpBefore?.let { it >= 1.0 - FULL_HP_EPSILON } == true &&
            (projection.expectedSelfHealingFraction ?: 0.0) <= FULL_HP_EPSILON
    }

    private const val CERTAIN_ACCURACY = 0.999
    private const val DAMAGE_UTILITY_SCALE = 100.0
    private const val SELF_HP_UTILITY_SCALE = 100.0
    private const val FULL_HP_EPSILON = 1e-9
}
