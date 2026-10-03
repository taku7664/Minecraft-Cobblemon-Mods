package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.betterai.mechanics.LocalRevivalBlessing
import jbro.cobblemon.mcc.internal.ai.BattleTargetSlot
import jbro.cobblemon.mcc.internal.ai.BattleKnockoutAssessment

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import java.util.Locale
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleCandidateFactsView
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattlePlanIntent
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStrategyBrief
import jbro.cobblemon.mcc.internal.ai.BattleStrategyObjective
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveDamageInputs
import jbro.cobblemon.mcc.betterai.mechanics.LocalMechanicActivationProjector
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.mcc.betterai.mechanics.LocalDeclaredMultiHit
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveTargets
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStab
import jbro.cobblemon.mcc.betterai.mechanics.LocalRiskAttitude
import jbro.cobblemon.mcc.betterai.mechanics.LocalStallingProtectionRules
import jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness

/**
 * The value model: every legal action reduced to one comparable number.
 *
 * This is the single most consequential piece of the local path and it used to sit unnamed at the
 * bottom of the Brain file, below the class that calls it, where no file listing would suggest it
 * existed. Both symptoms this module was rebuilt for - resisted moves being preferred and switches
 * appearing without a reason - were produced here.
 */
internal data class LocalTacticalScore(
    val total: Double,
    /** Root value re-derived from projected stat stages and therefore replaceable by search. */
    val statStageUtility: Double = 0.0,
    /** Item value already priced by immediate projection, withdrawn once when search takes over. */
    val itemUtility: Double = 0.0,
)

internal object LocalTacticalScorer {
    fun score(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief? = null,
        profile: BattleTrainerProfile = BattleTrainerProfile.balanced(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double = scoreBreakdown(candidate, context, strategy, profile, tuning).total

    fun scoreBreakdown(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief? = null,
        profile: BattleTrainerProfile = BattleTrainerProfile.balanced(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): LocalTacticalScore {
        val moveContext = LocalMechanicActivationProjector.forMegaCandidate(context, BattleSide.ALLY, candidate)
        return when (candidate.kind) {
            BattleActionKind.USE_MOVE -> scoreMove(candidate, moveContext, strategy, profile, tuning)
            BattleActionKind.SWITCH -> LocalTacticalScore(scoreSwitch(candidate, moveContext, strategy, profile, tuning))
            BattleActionKind.COMPOSITE -> {
                val components = candidate.componentActions.map { scoreBreakdown(it, moveContext, strategy, profile, tuning) }
                LocalTacticalScore(
                    total = components.sumOf(LocalTacticalScore::total) +
                        LocalTacticalSituationalEvaluator.compositeCoordinationAdjustment(candidate, moveContext) +
                        partnerActionCollateralRefund(candidate, moveContext, tuning) -
                        duplicateCertainKnockoutCredit(candidate, moveContext, tuning),
                    statStageUtility = components.sumOf(LocalTacticalScore::statStageUtility),
                    itemUtility = components.sumOf(LocalTacticalScore::itemUtility),
                )
            }
            BattleActionKind.WAIT -> LocalTacticalScore(-100.0)
            BattleActionKind.FORFEIT -> LocalTacticalScore(-10_000.0)
        }
    }

    /**
     * Knockout material credited inside [score] for this candidate.
     *
     * Exposed so the recursive search can subtract exactly what the root scorer added instead of
     * subtracting a constant that no longer matches.
     */
    fun knockoutUtility(
        candidate: BattleActionCandidate,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        /** Passed through so a screen or the weather can take the knockout off the table. */
        context: BattleDecisionContext? = null,
    ): Double {
        if (candidate.kind != BattleActionKind.USE_MOVE) return 0.0
        val details = candidate.moveDetails ?: return 0.0
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0
        val moveContext = context?.let { LocalMechanicActivationProjector.forMegaCandidate(it, BattleSide.ALLY, candidate) }
        val accuracy = moveContext?.let { LocalPublicAccuracy.probability(candidate, it, BattleSide.ALLY) }
            ?: candidate.facts?.baseAccuracyProbability
            ?: details.accuracy / 100.0
        return LocalTacticalSituationalEvaluator.knockoutAdjustment(candidate, accuracy, tuning, moveContext)
    }

    /** Combine the existing per-action removal credits by target, including partial and spread hits. */
    fun duplicateCertainKnockoutCredit(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double {
        if (tuning.legacyRawPowerFallback || tuning.knockoutMaterialScore <= 0.0) return 0.0
        val credits = candidate.componentActions.flatMap { action ->
            if (action.kind != BattleActionKind.USE_MOVE) return@flatMap emptyList()
            val spread = action.facts?.spreadTargets.orEmpty()
            val primary = spread.firstOrNull()?.let { BattleTargetSlot(it.side, it.slot) }
                ?: action.targets.singleOrNull()
                ?: context.state.pokemon.singleOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
                    ?.let { BattleTargetSlot(it.side, requireNotNull(it.activeSlot)) }
            val accuracy = LocalPublicAccuracy.probability(action, context, BattleSide.ALLY)
            buildList {
                if (primary?.side == BattleSide.OPPONENT) add(primary to (knockoutUtility(action, tuning, context) / tuning.knockoutMaterialScore))
                spread.drop(1).filter { it.side == BattleSide.OPPONENT }.forEach { extra ->
                    val target = context.state.pokemon.firstOrNull { it.side == extra.side && it.activeSlot == extra.slot && !it.fainted }
                    val decoy = target?.let {
                        jbro.cobblemon.mcc.betterai.calculation.PublicSubstituteDamageProjection.summary(
                            jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveTargets.spreadHitOn(action, it, action.actionId + ":extra"), context, BattleSide.ALLY)
                    }
                    val probability = decoy?.knockoutProbability?.let { if (accuracy > 0.0) it / accuracy else 0.0 } ?: when (extra.standardKnockoutAssessment) {
                        BattleKnockoutAssessment.GUARANTEED -> 1.0
                        BattleKnockoutAssessment.POSSIBLE -> extra.standardDamageRollKoProbabilityRange
                            ?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0
                        else -> 0.0
                    }
                    add(BattleTargetSlot(extra.side, extra.slot) to probability * accuracy)
                }
            }
        }
        return credits.groupBy({ it.first }, { it.second.coerceIn(0.0, 1.0) }).values.sumOf { probabilities ->
            val union = 1.0 - probabilities.fold(1.0) { survival, probability -> survival * (1.0 - probability) }
            (probabilities.sum() - union).coerceAtLeast(0.0) * tuning.knockoutMaterialScore
        }
    }

    /**
     * The half of a move's score that is a statement about the candidate rather than about value.
     *
     * Penalties, ally collateral, mechanic cost, self-pattern and strategy alignment. None of these
     * is a claim about how good the resulting board is - they are reasons this particular action is
     * or is not available in good conscience - so none of them is something a board search can
     * re-derive, and all of them must survive when the search takes over the value half.
     *
     * Subtracting this from `tacticalUtility` leaves exactly the part the search does re-derive.
     */
    fun candidateAdjustments(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief? = null,
        profile: BattleTrainerProfile = BattleTrainerProfile.balanced(),
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double {
        val moveContext = LocalMechanicActivationProjector.forMegaCandidate(context, BattleSide.ALLY, candidate)
        return when (candidate.kind) {
            BattleActionKind.USE_MOVE -> moveAdjustments(candidate, moveContext, strategy, profile, tuning)
            BattleActionKind.COMPOSITE ->
                candidate.componentActions.sumOf { candidateAdjustments(it, moveContext, strategy, profile, tuning) } +
                    LocalTacticalSituationalEvaluator.compositeCoordinationAdjustment(candidate, moveContext) +
                    partnerActionCollateralRefund(candidate, moveContext, tuning)
            else -> 0.0
        }
    }

    private fun moveAdjustments(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
    ): Double {
        if (candidate.moveDetails == null) {
            return mechanicResourceAdjustment(candidate, context, profile, tuning) + strategyMoveAdjustment(candidate, context, strategy)
        }
        return -publicAllyCollateral(candidate, context, tuning) -
            (if (LocalPublicMechanicsKernel.hasUnconfirmedAbilityImmunity(candidate, context)) {
                UNCERTAIN_ABILITY_IMMUNITY_PENALTY
            } else {
                0.0
            }) + LocalSetupMovePreference.bonus(candidate, context) -
            LocalTacticalSituationalEvaluator.activePersistentEffectRefreshPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.expiredFirstActiveTurnPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.saturatedStatStagePenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.unmetPublicRequirementPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.recentPublicFailurePenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.repeatedProtectionPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.pendingDamagingMoveRiskPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.consecutiveUseForbiddenPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.forcedTempoPenalty(candidate, context) +
            LocalInferredMoveGuessScorer.score(candidate, context) +
            mechanicResourceAdjustment(candidate, context, profile, tuning) +
            selfPatternAdjustment(candidate, context, profile) +
            strategyMoveAdjustment(candidate, context, strategy)
    }

    private fun scoreMove(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
    ): LocalTacticalScore {
        val details = candidate.moveDetails
            ?: return LocalTacticalScore(
                5.0 + mechanicResourceAdjustment(candidate, context, profile, tuning) + strategyMoveAdjustment(candidate, context, strategy),
            )
        val facts = candidate.facts
        val damageRange = facts?.standardDamageFractionRange
        val accuracy = LocalPublicAccuracy.probability(candidate, context, BattleSide.ALLY)
        val nonDamagingScore = if (details.damageCategory == BattleMoveDamageCategory.STATUS) {
            LocalNonDamagingMoveEvaluator.score(
                candidate, context, accuracy, tuning,
                statusFitScale = LocalStatusTargetFit.scale(profile.difficulty.tier),
            )
        } else {
            null
        }
        val damagingStageUtility = if (details.damageCategory == BattleMoveDamageCategory.STATUS) {
            0.0
        } else {
            LocalStatStageMarginalEvaluator.candidateScore(candidate, context, accuracy, tuning = tuning) ?: 0.0
        }
        val itemScore = if (nonDamagingScore != null) null else LocalRootItemEffectEvaluator.damagingScore(candidate, context, accuracy, tuning)
        val pressure = if (nonDamagingScore != null) {
            nonDamagingScore.total
        } else if (damageRange != null) {
            // HP-fraction pressure. This is deliberately the unclamped projection: the outcome
            // evaluator adds `(clampedExpectedDamage - thisValue / 100) * 100`, so the two terms
            // cancel into the clamped, mechanics-aware expected damage. Changing the constant here
            // without changing DAMAGE_UTILITY_SCALE would break that cancellation.
            //
            // The risk attitude has to match the one the outcome evaluator hands the projector, for
            // the same reason: these two are one term split across two files.
            LocalRiskAttitude.expectedFraction(
                damageRange.minimum,
                damageRange.maximum,
                profile.personality.riskTolerance,
            ) * 100.0 * accuracy + damagingStageUtility
        } else {
            unprojectedPressure(candidate, context, details, facts, tuning) + damagingStageUtility
        }
        val effectivePriority = LocalPublicTurnOrder.effectivePriority(context.state, BattleSide.ALLY, candidate)
        val prioritySuccessProbability = if (LocalStallingProtectionRules.isStallingProtection(candidate)) {
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
        val priorityBonus = when {
            effectivePriority <= 0 -> effectivePriority * 2.0
            priorityTargetHp(candidate, context) <= CRITICAL_HP -> effectivePriority * 25.0
            priorityUserHp(candidate, context) <= CRITICAL_HP -> effectivePriority * 8.0
            else -> effectivePriority * 2.0
        } * prioritySuccessProbability
        val knockoutBonus = LocalTacticalSituationalEvaluator.knockoutAdjustment(candidate, accuracy, tuning, context)
        // A spread move's other targets. Zero for every single-target move, so this changes nothing
        // outside doubles.
        val spreadBonus = LocalTacticalSituationalEvaluator.spreadAdjustment(candidate, accuracy, context, tuning)
        // Recoil is charged once, by the outcome evaluator, from the accuracy-weighted and
        // HP-clamped projection. Charging `selfRecoilFractionRange` here as well double-billed every
        // recoil attacker - which is exactly the class of move a physical sweeper wants to click.
        val recoilPenalty = if (tuning.legacyRawPowerFallback) {
            facts?.selfRecoilFractionRange?.let { (it.minimum + it.maximum) * 50.0 } ?: 0.0
        } else {
            0.0
        }
        val total = pressure + (itemScore?.total ?: 0.0) + priorityBonus + knockoutBonus + spreadBonus -
            recoilPenalty -
            (if (LocalPublicMechanicsKernel.hasUnconfirmedAbilityImmunity(candidate, context)) {
                UNCERTAIN_ABILITY_IMMUNITY_PENALTY
            } else {
                0.0
            }) + LocalSetupMovePreference.bonus(candidate, context) -
            publicAllyCollateral(candidate, context, tuning) -
            contactRecoilCost(candidate, context, tuning) -
            LocalTacticalSituationalEvaluator.activePersistentEffectRefreshPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.expiredFirstActiveTurnPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.saturatedStatStagePenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.unmetPublicRequirementPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.recentPublicFailurePenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.repeatedProtectionPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.pendingDamagingMoveRiskPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.consecutiveUseForbiddenPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.forcedTempoPenalty(candidate, context) +
            LocalInferredMoveGuessScorer.score(candidate, context) +
            mechanicResourceAdjustment(candidate, context, profile, tuning) +
            selfPatternAdjustment(candidate, context, profile) +
            strategyMoveAdjustment(candidate, context, strategy)
        return LocalTacticalScore(
            total = total,
            statStageUtility = nonDamagingScore?.statStageUtility ?: (damagingStageUtility + (itemScore?.statStageUtility ?: 0.0)),
            itemUtility = nonDamagingScore?.itemUtility ?: itemScore?.itemUtility ?: 0.0,
        )
    }

    /**
     * Pressure for a damaging move the Showdown projection could not model.
     *
     * The projection drops out for a whole class of legal moves - a battle mechanic is attached, the
     * power is not a positive whole number, the target's defensive stats are not public, the target
     * pattern is unsupported. Those moves still have to be comparable against projected ones.
     *
     * The legacy fallback returned `power * accuracy * stab * typeMultiplier`, a `0..300+` raw-power
     * number, into the same comparison as `0..100` HP-fraction pressure. A 120 BP resisted STAB move
     * scored `90` while a projected super-effective move taking 60% of the bar scored `60`, so the
     * resisted move won every time the projection succeeded for one and failed for the other. That is
     * the resisted-move bug: not a bad type chart, a scale collision.
     *
     * Converting power to an HP fraction first keeps everything on one axis. It is a coarse estimate
     * and it is meant to be - the point is that it is coarse *in the same unit*.
     */
    /**
     * The fallback pressure [score] used for this candidate, or `0.0` if it took the projected path.
     *
     * Exposed for the same reason as [knockoutUtility]: the outcome evaluator adds the projector's own
     * expected damage on top, and when both describe the same hit one of them has to go. Without this
     * the unprojected branch was billed twice - once as converted raw power, once as declared damage -
     * which is why a high-power resisted move could still out-score a super-effective one even after
     * the scales were unified.
     */
    fun unprojectedPressureOf(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double {
        if (candidate.kind != BattleActionKind.USE_MOVE) return 0.0
        val details = candidate.moveDetails ?: return 0.0
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0
        val facts = candidate.facts
        if (facts?.standardDamageFractionRange != null) return 0.0
        return unprojectedPressure(candidate, context, details, facts, tuning)
    }

    private fun unprojectedPressure(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        details: BattleMoveCandidateView,
        facts: BattleCandidateFactsView?,
        tuning: LocalDecisionTuning,
    ): Double {
        if (LocalPublicMoveDamageInputs.isUnresolvedDynamicDamage(candidate)) return 0.0
        val sameTypeBonus = facts?.baseSameTypeAttackBonus ?: publicSameTypeBonus(candidate, context)
        val typeMultiplier = facts?.typeChartMultiplier ?: publicTypeMultiplier(candidate, context)
        val accuracy = LocalPublicAccuracy.probability(candidate, context, BattleSide.ALLY)
        val effectivePower = LocalPublicAccuracy.weightedPower(details, accuracy)
        if (tuning.legacyRawPowerFallback) {
            // Preserve the established operation order for deterministic tie-breaking.
            return effectivePower * sameTypeBonus * typeMultiplier
        }
        val hpFraction = effectivePower / tuning.unprojectedPowerPerHpBar * sameTypeBonus * typeMultiplier
        return hpFraction.coerceIn(0.0, 1.5) * tuning.boardToScore
    }

    private fun selfPatternAdjustment(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
    ): Double {
        if (candidate.moveId == null || candidate.moveId != context.memory.lastMoveId) return 0.0
        if (context.memory.sameMoveRepeatCount < 2) return 0.0
        if (context.memory.patternExposureCount < 2 || context.memory.patternResponseShiftEvidence < 0.35) return 0.0
        return -SELF_PATTERN_BREAK_PENALTY * profile.personality.information *
            context.memory.patternResponseShiftEvidence
    }

    private fun scoreSwitch(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
    ): Double {
        if (LocalRevivalBlessing.applies(candidate)) {
            return LocalRevivalBlessing.score(candidate, context, tuning)
        }
        val active = LocalPublicPositionFacts.activeAlly(candidate, context)
        val activeHp = active?.hpFraction ?: allyActiveHp(context)
        val target = LocalPublicPositionFacts.switchTarget(candidate, context)
        val targetPostEntryHp = target?.let {
            LocalTacticalSituationalEvaluator.postEntryHp(candidate, it.hpFraction)
        }
        // Exposure is "expected fraction of this Pokemon's HP the opponent removes per turn" in both
        // the revealed-move and type-chart branches. See LocalPublicPositionFacts.defensiveExposure.
        val neutralExposure = tuning.neutralHitHpFraction
        val currentRisk = active?.let { LocalPublicPositionFacts.defensiveExposure(it, context, tuning = tuning) }
            ?: neutralExposure
        val targetRisk = target?.let {
            LocalPublicPositionFacts.defensiveExposure(it, context, candidate.actorSlot, tuning)
        } ?: neutralExposure
        val riskImprovement = (currentRisk - targetRisk).coerceAtLeast(0.0)
        val riskWorsening = (targetRisk - currentRisk).coerceAtLeast(0.0)
        val healthAdvantage = targetPostEntryHp?.let { (it - activeHp).coerceAtLeast(0.0) } ?: 0.0
        val hasPublicPositioningGain = target != null && (
            riskImprovement > 0.0 ||
                healthAdvantage >= MEANINGFUL_SWITCH_HEALTH_ADVANTAGE ||
                activeHp <= CRITICAL_HP
            )
        val baseScore = if (target == null) {
            -tuning.switchTempoPenalty +
                (if (activeHp <= CRITICAL_HP) tuning.criticalSwitchBonus else 0.0)
        } else {
            -tuning.switchTempoPenalty +
                healthAdvantage * tuning.switchHealthAdvantageWeight +
                riskImprovement * tuning.switchExposureImprovementWeight +
                (
                    if (active != null &&
                        LocalPublicPositionFacts.isPublicKnockoutThreat(active, context, tuning) &&
                        riskImprovement > 0.0
                    ) {
                        tuning.publicKnockoutThreatSwitchBonus
                    } else {
                        0.0
                    }
                    ) +
                (if (activeHp <= CRITICAL_HP) tuning.criticalSwitchBonus else 0.0) -
                riskWorsening * tuning.switchExposureWorseningWeight -
                targetRisk * tuning.residualExposureWeight
        }
        val personalityAdjustment = (profile.personality.switching - 0.5) * 20.0
        val offensivePressureImprovement = LocalLookaheadStateEvaluator
            .switchOffensivePressureImprovement(candidate, context)
            ?.coerceAtLeast(0.0)
            ?.times(SWITCH_OFFENSIVE_PRESSURE_WEIGHT)
            ?: 0.0
        val initiativeImprovement = LocalLookaheadStateEvaluator
            .switchInitiativeImprovement(candidate, context)
            ?.times(SWITCH_INITIATIVE_WEIGHT)
            ?: 0.0
        val entryPenalty = candidate.facts?.switchEntryHpLossFraction?.times(100.0) ?: 0.0
        val criticalTargetPenalty = if (
            targetPostEntryHp != null && targetPostEntryHp <= CRITICAL_HP && targetRisk > 0.0
        ) {
            CRITICAL_SWITCH_TARGET_PENALTY
        } else {
            0.0
        }
        val entryKnockoutPenalty = target?.let {
            LocalTacticalSituationalEvaluator.entryKnockoutPenalty(candidate, it.hpFraction)
        } ?: 0.0
        return baseScore - entryPenalty - criticalTargetPenalty - entryKnockoutPenalty +
            personalityAdjustment + offensivePressureImprovement + switchMemoryAdjustment(context) +
            initiativeImprovement +
            strategySwitchAdjustment(candidate, context, strategy, hasPublicPositioningGain) +
            planSwitchAdjustment(candidate, context, strategy)
    }

    private fun switchMemoryAdjustment(context: BattleDecisionContext): Double {
        val recencyPenalty = when (context.memory.turnsSinceLastSwitch) {
            0 -> SAME_TURN_SWITCH_PENALTY
            1 -> NEXT_TURN_SWITCH_PENALTY
            2 -> TWO_TURN_SWITCH_PENALTY
            3 -> THREE_TURN_SWITCH_PENALTY
            else -> 0.0
        }
        val frequencyPenalty = (context.memory.switchPressure * SWITCH_FATIGUE_PER_PRESSURE)
            .coerceAtMost(MAX_SWITCH_FATIGUE_PENALTY)
        return -recencyPenalty - frequencyPenalty
    }

    private fun planSwitchAdjustment(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
    ): Double {
        val plan = context.memory.activePlan ?: return 0.0
        if (plan.intent != BattlePlanIntent.CREATE_SAFE_ENTRY || plan.targetRole == null || strategy == null) return 0.0
        val targetSpecies = candidate.switchPokemonId?.let { targetId ->
            context.state.pokemon.firstOrNull { it.battlePokemonId == targetId && it.side == BattleSide.ALLY }?.speciesId
        } ?: return 0.0
        val member = strategy.members.firstOrNull {
            canonicalResourceId(it.speciesId) == canonicalResourceId(targetSpecies)
        }
        return if (member != null && plan.targetRole in member.roles) PLAN_TARGET_BONUS else 0.0
    }

    /** The situational value of keeping the mechanic, not a flat charge; see [LocalMechanicOptionValue]. */
    private fun mechanicResourceAdjustment(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
    ): Double = if (candidate.mechanic == null || !tuning.mechanicReserve) 0.0 else -LocalMechanicOptionValue.cost(candidate, context, profile)

    private fun strategyMoveAdjustment(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
    ): Double {
        if (strategy == null) return 0.0
        val details = candidate.moveDetails
        val moveId = candidate.moveId
        val member = strategyMember(candidate, context, strategy)
        var adjustment = if (moveId != null && member?.preferredMoveIds?.any {
                canonicalResourceId(it) == canonicalResourceId(moveId)
            } == true
        ) {
            PREFERRED_MOVE_BONUS
        } else {
            0.0
        }
        if (BattleStrategyObjective.STATUS_PRESSURE in strategy.objectives &&
            details?.damageCategory == BattleMoveDamageCategory.STATUS &&
            declaresOpponentStatusPressure(candidate)
        ) {
            adjustment += STATUS_PRESSURE_BONUS
        }
        if (BattleStrategyObjective.SPREAD_PRESSURE in strategy.objectives &&
            details?.targetPattern in SPREAD_PATTERNS
        ) {
            adjustment += SPREAD_PRESSURE_BONUS
        }
        if (BattleStrategyObjective.FOCUS_FIRE in strategy.objectives &&
            details?.targetPattern in setOf(
                BattleMoveTargetPattern.SELECTED,
                BattleMoveTargetPattern.SELECTED_OPPONENT,
            )
        ) {
            adjustment += FOCUS_FIRE_BONUS
        }
        return adjustment
    }

    private fun declaresOpponentStatusPressure(candidate: BattleActionCandidate): Boolean =
        candidate.moveDetails?.effects?.effects.orEmpty().any { effect ->
            when (effect.target) {
                BattleMoveEffectTarget.SELECTED_TARGET -> when (effect.kind) {
                    BattleMoveEffectKind.STATUS,
                    BattleMoveEffectKind.VOLATILE_STATUS,
                    BattleMoveEffectKind.SWITCH_TARGET,
                    BattleMoveEffectKind.SLOT_CONDITION,
                    -> true
                    BattleMoveEffectKind.STAT_STAGE -> effect.statStages.values.any { it < 0 }
                    else -> false
                }
                BattleMoveEffectTarget.TARGET_SIDE -> effect.kind == BattleMoveEffectKind.SIDE_CONDITION
                else -> false
            }
        }

    private fun strategySwitchAdjustment(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        hasPublicPositioningGain: Boolean,
    ): Double {
        if (strategy == null) return 0.0
        var adjustment = if (
            BattleStrategyObjective.PIVOTING in strategy.objectives && hasPublicPositioningGain
        ) {
            PIVOTING_BONUS
        } else {
            0.0
        }
        val active = candidate.actorSlot?.let { actorSlot ->
            context.state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.activeSlot == actorSlot && !it.fainted
            }
        }
        val member = active?.let { activePokemon ->
            strategy.members.firstOrNull {
                canonicalResourceId(it.speciesId) == canonicalResourceId(activePokemon.speciesId)
            }
        }
        if (active != null &&
            member != null &&
            active.hpFraction <= PRESERVE_CORE_HP &&
            member.preservationPriority > 0
        ) {
            adjustment += PRESERVE_CORE_BONUS * member.preservationPriority / 100.0
        }
        return adjustment
    }

    private fun strategyMember(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief,
    ) = candidate.actorSlot?.let { actorSlot ->
        val speciesId = context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == actorSlot && !it.fainted
        }?.speciesId ?: return@let null
        strategy.members.firstOrNull {
            canonicalResourceId(it.speciesId) == canonicalResourceId(speciesId)
        }
    }

    /** A partner that gets its move off before the knockout lands loses nothing but health. */
    private const val DOOMED_ALLY_FULL_DISCOUNT = 1.0

    /** Reading one turn ahead is not free, so a partner struck before it acts keeps half its price. */
    private const val DOOMED_ALLY_PARTIAL_DISCOUNT = 0.5

    /** How sure the public speed reading has to be before the full discount applies. */
    private const val DOOMED_ALLY_ORDER_CONFIDENCE = 0.8

    private fun canonicalResourceId(id: String): String =
        PublicIds.canonical(id)

    private fun publicTypeMultiplier(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val moveType = candidate.moveDetails?.typeId ?: return 1.0
        val activeOpponents = activePokemon(context, BattleSide.OPPONENT)
        val targetPattern = candidate.moveDetails?.targetPattern
        val targets = when (targetPattern) {
            BattleMoveTargetPattern.ALL_ACTIVE,
            BattleMoveTargetPattern.ALL_ADJACENT,
            BattleMoveTargetPattern.ALL_OPPONENTS,
            -> activeOpponents

            BattleMoveTargetPattern.SELECTED,
            BattleMoveTargetPattern.SELECTED_OPPONENT,
            -> {
                if (candidate.targets.isEmpty()) {
                    activeOpponents.take(1)
                } else {
                    val slots = candidate.targets.filter { it.side == BattleSide.OPPONENT }
                        .mapTo(linkedSetOf()) { it.slot }
                    activeOpponents.filter { it.activeSlot in slots }
                }
            }

            BattleMoveTargetPattern.RANDOM_OPPONENT -> activeOpponents

            else -> null
        }
        // `null` means the move does not aim at an opponent at all, so opponent effectiveness is
        // genuinely zero. An empty list for a pattern that *does* aim at one means the slot could not
        // be resolved - that is unknown, not immune, and collapsing it to 0.0 silently deleted the
        // move from consideration.
        if (targets == null) return 0.0
        if (targets.isEmpty()) return 1.0
        val effectivenessSum = targets.sumOf { target ->
            if (target.knownTypeIds.isEmpty()) {
                1.0
            } else {
                StandardTypeEffectiveness.multiplierAgainst(
                    attackingTypeId = moveType,
                    defendingTypeIds = target.knownTypeIds,
                    defenderAbilityId = LocalPublicAbilityState.effectiveKnownAbility(context.state, target),
                    moveId = candidate.moveId,
                )
            }
        }
        val targetAverage = if (targetPattern == BattleMoveTargetPattern.RANDOM_OPPONENT) {
            effectivenessSum / targets.size
        } else {
            effectivenessSum
        }
        return targetAverage * spreadDamageModifier(candidate, context)
    }

    /**
     * What this move costs by landing on one's own side.
     *
     * The same scale collision as the resisted-move bug, at the site that fix did not reach. This
     * returned `power * accuracy * stab * typeMultiplier` - a raw-power number - and it was
     * subtracted from a score whose damage half is an HP fraction times a hundred. A hundred-power
     * same-type spread move was therefore charged about a hundred and twelve for grazing a partner
     * while earning about thirty-two for hitting two opponents, so Earthquake in doubles scored
     * around minus forty-six against plus forty-six for hitting one opponent. The AI was not being
     * careful with its partner; it was comparing two different units.
     *
     * Converted the same way [unprojectedPressure] converts, through the same tuning constants, so
     * the two coarse estimates stay coarse in the same unit. `legacyRawPowerFallback` keeps the old
     * arithmetic for the legacy tuning, which is measured against the old numbers.
     *
     * Health that is already gone is not charged for. pokeemerald-expansion #6357 describes the two
     * ways a doubles AI gets friendly fire wrong, and refusing it whenever a partner is in the blast
     * is one of them: a partner the opponent is publicly certain to knock out this turn has no health
     * left to protect, so declining the spread move buys nothing and costs the second target.
     */
    /**
     * What a contact move costs its user against a Rocky Helmet, Rough Skin or Iron Barbs: a sixth or an eighth of
     * its HP. The search has always charged it; the ranking did not, so a contact move into a helmet looked free to
     * a trainer that leans on the ranking.
     */
    private fun contactRecoilCost(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
    ): Double {
        val details = candidate.moveDetails ?: return 0.0
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0
        if (details.effects?.mechanicFlags.orEmpty().none { PublicIds.canonical(it) == "contact" }) return 0.0
        val actor = LocalPublicPositionFacts.activeAlly(candidate, context) ?: return 0.0
        if (LocalPublicAbilityState.effectiveKnownAbility(context.state, actor) in CONTACT_SAFE_ABILITIES) return 0.0
        if (LocalPublicItemState.activeItemId(context.state, actor) == "protectivepads") return 0.0
        val target = LocalPublicMoveTargets.resolve(candidate, context, BattleSide.ALLY).firstOrNull() ?: return 0.0
        var lost = 0.0
        if (LocalPublicItemState.activeItemId(context.state, target) == "rockyhelmet") lost += 1.0 / 6.0
        if (LocalPublicAbilityState.effectiveKnownAbility(context.state, target) in setOf("roughskin", "ironbarbs")) lost += 1.0 / 8.0
        if (lost <= 0.0) return 0.0
        val accuracy = LocalPublicAccuracy.probability(candidate, context, BattleSide.ALLY)
        val hits = LocalDeclaredMultiHit.representativeCount(candidate, actor, context.state)
        val total = minOf(lost * hits, actor.hpFraction)
        val knockout = if (lost * hits >= actor.hpFraction) tuning.knockoutMaterialScore else 0.0
        return (total * tuning.boardToScore + knockout) * accuracy
    }

    private val CONTACT_SAFE_ABILITIES = setOf("magicguard", "longreach")

    private fun publicAllyCollateral(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        /** Prices the hit on these instead, such as a partner switching into the slot. */
        targetsOverride: List<BattlePokemonStateView>? = null,
    ): Double {
        val details = candidate.moveDetails ?: return 0.0
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0
        val targets = targetsOverride ?: allyCollateralTargets(candidate, context)
        if (targets.isEmpty()) return 0.0
        val effectivePower = LocalPublicAccuracy.weightedPower(
            details,
            LocalPublicAccuracy.probability(candidate, context, BattleSide.ALLY),
        )
        val sameTypeBonus = publicSameTypeBonus(candidate, context)
        // A Pokemon switching in has no knockout coming at it yet, so nothing of it is already spoken for.
        val doomedDiscount = { ally: BattlePokemonStateView ->
            if (ally.activeSlot == null) 0.0 else doomedAllyDiscount(ally, context, tuning)
        }
        val spread = spreadDamageModifier(candidate, context)
        return targets.sumOf { target ->
            allyCollateralOn(candidate, context, tuning, target, effectivePower, sameTypeBonus, spread, doomedDiscount)
        }
    }

    /**
     * The collateral a joint action's own partner move takes back. Each slot's collateral is charged with no
     * view of what the partner does, so Earthquake beside a partner's Protect was billed as if the partner
     * stood in it, and beside a partner switching out it billed the Pokemon leaving instead of the one coming in.
     */
    private fun partnerActionCollateralRefund(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
    ): Double {
        val parts = candidate.componentActions
        if (parts.size < 2 || tuning.legacyRawPowerFallback) return 0.0
        return parts.sumOf { attack ->
            if (attack.kind != BattleActionKind.USE_MOVE) return@sumOf 0.0
            allyCollateralTargets(attack, context).sumOf { partner ->
                val partnerAction = parts.firstOrNull { it !== attack && it.actorSlot == partner.activeSlot }
                    ?: return@sumOf 0.0
                when {
                    // Protect's +4 priority puts it ahead of every attack the partner can choose.
                    partnerAction.kind == BattleActionKind.USE_MOVE &&
                        LocalStallingProtectionRules.isStallingProtection(partnerAction) -> {
                        val shielded = LocalStallingProtectionRules.nextSuccessProbability(
                            LocalStallingProtectionRules.consecutiveSuccessfulUses(context.state, BattleSide.ALLY, partner.activeSlot),
                        )
                        publicAllyCollateral(attack, context, tuning, listOf(partner)) * shielded
                    }
                    partnerAction.kind == BattleActionKind.SWITCH -> {
                        val incoming = context.state.pokemon.firstOrNull {
                            it.battlePokemonId == partnerAction.switchPokemonId && !it.fainted
                        } ?: return@sumOf 0.0
                        publicAllyCollateral(attack, context, tuning, listOf(partner)) -
                            publicAllyCollateral(attack, context, tuning, listOf(incoming))
                    }
                    else -> 0.0
                }
            }
        }
    }

    private fun allyCollateralTargets(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): List<BattlePokemonStateView> {
        val details = candidate.moveDetails ?: return emptyList()
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return emptyList()
        val actorSlot = candidate.actorSlot
        val activeAllies = activePokemon(context, BattleSide.ALLY)
        return when (details.targetPattern) {
            BattleMoveTargetPattern.ALL_ACTIVE -> activeAllies
            BattleMoveTargetPattern.ALL_ADJACENT -> activeAllies.filter { it.activeSlot != actorSlot }
            BattleMoveTargetPattern.ALL_ALLIES -> activeAllies
            BattleMoveTargetPattern.SELECTED,
            BattleMoveTargetPattern.SELECTED_OPPONENT,
            BattleMoveTargetPattern.SELECTED_ALLY,
            BattleMoveTargetPattern.SELECTED_ALLY_OR_SELF,
            -> {
                val slots = candidate.targets.filter { it.side == BattleSide.ALLY }
                    .mapTo(linkedSetOf()) { it.slot }
                activeAllies.filter { it.activeSlot in slots }
            }
            else -> emptyList()
        }
    }

    private fun allyCollateralOn(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
        target: BattlePokemonStateView,
        effectivePower: Double,
        sameTypeBonus: Double,
        spread: Double,
        doomedDiscount: (BattlePokemonStateView) -> Double,
    ): Double {
        val multiplier = if (target.knownTypeIds.isEmpty()) {
            1.0
        } else {
            LocalPublicMechanicsKernel.publicDamageMultiplierAgainst(
                candidate,
                context,
                BattleSide.ALLY,
                target,
            )
        }
        // Immunities (types, Levitate, Telepathy) are the kernel's call; the damage formula below does
        // not know every partner-protecting mechanic.
        if (multiplier == 0.0) return 0.0
        // The partner's stats are exact, so the real damage formula prices the hit, and a hit that can
        // knock the partner out is charged as a lost Pokemon, not only as lost HP. Earthquake from a
        // Guts Ursaluna left a full-HP Volcanion partner at 19%, fainted or at 8% in the engine, while
        // the power-only estimate below charged it about 75 points.
        val rolls = if (tuning.legacyRawPowerFallback) null else {
            PublicBattleTacticalCalculator.partnerDamageRollFractions(candidate, context, target, spread)
        }
        if (!rolls.isNullOrEmpty()) {
            val lost = rolls.map { minOf(it, target.hpFraction) }.average()
            val knockoutChance = rolls.count { it >= target.hpFraction }.toDouble() / rolls.size
            return (lost * tuning.boardToScore + knockoutChance * tuning.knockoutMaterialScore) *
                (1.0 - doomedDiscount(target))
        }
        return spread * if (tuning.legacyRawPowerFallback) {
            effectivePower * sameTypeBonus * multiplier
        } else {
            val hpFraction = effectivePower / tuning.unprojectedPowerPerHpBar * sameTypeBonus * multiplier
            hpFraction.coerceIn(0.0, 1.5) * tuning.boardToScore * (1.0 - doomedDiscount(target))
        }
    }

    /**
     * How much of this partner's health is already spoken for by the opponent.
     *
     * Nothing is discounted unless the opponent is a *publicly certain* knockout on that partner -
     * a revealed move that reaches its remaining health - so this never fires on a guess about a
     * hidden set. When it does fire, the order of the turn decides how much:
     *
     * - the partner acts before the incoming knockout, so it gets its move off either way, and the
     *   spread move costs only health that was leaving anyway;
     * - the partner acts after it, so hitting it can only cost health, not a turn - but the read
     *   itself is one turn of prediction, so half is discounted rather than all of it.
     *
     * The conservative direction here is to charge for the health, and that is what an absent or
     * unresolved action order falls back to.
     */
    private fun doomedAllyDiscount(
        ally: BattlePokemonStateView,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
    ): Double {
        if (!LocalPublicPositionFacts.isPublicKnockoutThreat(ally, context, tuning)) return 0.0
        val actsFirst = LocalPublicTurnOrder.actsFirstProbability(
            state = context.state,
            actorSide = BattleSide.ALLY,
            actorSlot = ally.activeSlot,
            actorPriority = 0,
            opponentPriority = 0,
        ) ?: return DOOMED_ALLY_PARTIAL_DISCOUNT
        return if (actsFirst >= DOOMED_ALLY_ORDER_CONFIDENCE) {
            DOOMED_ALLY_FULL_DISCOUNT
        } else {
            DOOMED_ALLY_PARTIAL_DISCOUNT
        }
    }

    private fun spreadDamageModifier(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val pattern = candidate.moveDetails?.targetPattern ?: return 1.0
        if (pattern !in SPREAD_PATTERNS) return 1.0
        val activeOpponents = activePokemon(context, BattleSide.OPPONENT).size
        val actorSlot = candidate.actorSlot
        val activeAllies = activePokemon(context, BattleSide.ALLY).count { ally ->
            when (pattern) {
                BattleMoveTargetPattern.ALL_ACTIVE,
                BattleMoveTargetPattern.ALL_ALLIES,
                -> true
                BattleMoveTargetPattern.ALL_ADJACENT -> ally.activeSlot != actorSlot
                else -> false
            }
        }
        return if (activeOpponents + activeAllies > 1) SPREAD_DAMAGE_MODIFIER else 1.0
    }

    private fun activePokemon(context: BattleDecisionContext, side: BattleSide) =
        context.state.pokemon.filter { it.side == side && it.activeSlot != null && !it.fainted }

    private fun publicSameTypeBonus(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val actorSlot = candidate.actorSlot ?: return 1.0
        val moveType = candidate.moveDetails?.typeId ?: return 1.0
        val actor = context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == actorSlot && !it.fainted
        } ?: return 1.0
        return LocalPublicStab.multiplier(candidate, actor, moveType) ?: 1.0
    }

    private fun allyActiveHp(context: BattleDecisionContext): Double = activeHp(context, BattleSide.ALLY)

    /**
     * The HP of the foe this priority move lands on. In doubles the lower of the two foes is not
     * the one a single-target move strikes, so it reads the move's own target.
     */
    private fun priorityTargetHp(candidate: BattleActionCandidate, context: BattleDecisionContext): Double =
        LocalPublicMoveTargets.resolve(candidate, context, BattleSide.ALLY)
            .filter { it.side == BattleSide.OPPONENT }
            .minOfOrNull { it.hpFraction }
            ?: if (context.state.format == BattleFormat.DOUBLE) 1.0 else opponentActiveHp(context)

    /** The HP of the Pokemon using this priority move, not of whichever partner is lower. */
    private fun priorityUserHp(candidate: BattleActionCandidate, context: BattleDecisionContext): Double =
        candidate.actorSlot?.let { slot ->
            context.state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == slot && !it.fainted }
        }?.hpFraction ?: allyActiveHp(context)

    private fun opponentActiveHp(context: BattleDecisionContext): Double = activeHp(context, BattleSide.OPPONENT)

    private fun activeHp(context: BattleDecisionContext, side: BattleSide): Double =
        context.state.pokemon.filter { it.side == side && it.activeSlot != null && !it.fainted }
            .minOfOrNull { it.hpFraction } ?: 1.0

    private const val CRITICAL_HP = 0.2
    private const val PRESERVE_CORE_HP = 0.5
    private const val SWITCH_TEMPO_PENALTY = 20.0
    private const val SWITCH_HEALTH_ADVANTAGE_WEIGHT = 40.0
    private const val PUBLIC_RISK_IMPROVEMENT_WEIGHT = 50.0
    private const val PUBLIC_RISK_WORSENING_WEIGHT = PUBLIC_RISK_IMPROVEMENT_WEIGHT
    private const val SWITCH_OFFENSIVE_PRESSURE_WEIGHT = 40.0
    private const val SWITCH_INITIATIVE_WEIGHT = 12.0
    private const val PUBLIC_KO_THREAT_SWITCH_BONUS = 25.0
    private const val UNCERTAIN_ABILITY_IMMUNITY_PENALTY = 20.0
    private const val CRITICAL_SWITCH_BONUS = 60.0
    private const val CRITICAL_SWITCH_TARGET_PENALTY = CRITICAL_SWITCH_BONUS
    private const val MEANINGFUL_SWITCH_HEALTH_ADVANTAGE = 0.15
    private const val SAME_TURN_SWITCH_PENALTY = 30.0
    private const val NEXT_TURN_SWITCH_PENALTY = 20.0
    private const val TWO_TURN_SWITCH_PENALTY = 12.0
    private const val THREE_TURN_SWITCH_PENALTY = 5.0
    private const val SWITCH_FATIGUE_PER_PRESSURE = 3.0
    private const val MAX_SWITCH_FATIGUE_PENALTY = 15.0
    private const val PLAN_TARGET_BONUS = 30.0
    private const val SELF_PATTERN_BREAK_PENALTY = 20.0
    private const val SPREAD_DAMAGE_MODIFIER = 0.75
    private const val PREFERRED_MOVE_BONUS = 35.0
    private const val STATUS_PRESSURE_BONUS = 20.0
    private const val SPREAD_PRESSURE_BONUS = 15.0
    private const val FOCUS_FIRE_BONUS = 10.0
    private const val PIVOTING_BONUS = 15.0
    private const val PRESERVE_CORE_BONUS = 35.0
    private val SPREAD_PATTERNS = setOf(
        BattleMoveTargetPattern.ALL_ACTIVE,
        BattleMoveTargetPattern.ALL_ADJACENT,
        BattleMoveTargetPattern.ALL_OPPONENTS,
    )
}
