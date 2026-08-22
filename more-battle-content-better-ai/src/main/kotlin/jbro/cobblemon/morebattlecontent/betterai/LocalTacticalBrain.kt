package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrain
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainCloseResult
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSession
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecision
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanIntent
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal class LocalTacticalBrain(
    private val actionSelector: LocalActionSelector = LocalWeightedActionSelector(),
) : BattleBrain {
    override fun openSession(context: BattleBrainOpenContext): BattleBrainSession =
        Session(
            UUID.randomUUID(),
            context.battleId,
            context.trainerPersonaId,
            context.strategy,
            context.trainerProfile,
        )

    override fun decide(
        session: BattleBrainSession,
        context: BattleDecisionContext,
    ): CompletionStage<BattleDecision> {
        val active = session as? Session
        val profile = active?.trainerProfile ?: BattleTrainerProfile.balanced()
        val strategy = active?.strategy.takeUnless {
            profile.difficulty.tier == BattleTrainerTier.INTRODUCTORY
        }
        val calculatedContext = PublicBattleTacticalCalculator.calculate(context)
            .forPlanOwner(jbro.cobblemon.morebattlecontent.api.ai.BattlePlanOwner.LOCAL_BRAIN)
        val difficultyContext = if (profile.difficulty.tier == BattleTrainerTier.INTRODUCTORY) {
            calculatedContext.withoutActivePlan()
        } else {
            calculatedContext
        }
        val baseRanked = LocalBattleActionPolicy.rank(difficultyContext, strategy, profile)
        baseRanked.singleOrNull()?.let { selected ->
            return CompletableFuture.completedFuture(
                BattleDecision(
                    requestId = context.requestId,
                    actionId = selected.outcome.candidate.actionId,
                    confidence = 1.0,
                    advice = LocalBattleMind.advice(selected, difficultyContext, strategy, profile),
                    tags = setOf(
                        "local_tactical_v4",
                        "single_legal_action",
                        "difficulty_${profile.difficulty.tier.name.lowercase()}",
                    ),
                ),
            )
        }
        val battleId = active?.battleId ?: calculatedContext.state.battleId
        val mind = LocalBattleMind.assess(
            trainerPersonaId = active?.trainerPersonaId,
            battleId = battleId,
            context = difficultyContext,
            profile = profile,
        )
        val lookahead = LocalRecursiveLookaheadEvaluator.evaluate(baseRanked, difficultyContext, profile)
        val ranked = lookahead.ranked
        val seed = LocalActionChoiceSeed.derive(
            battleId = battleId,
            turn = calculatedContext.state.turn,
            ranked = ranked,
        )
        val selection = actionSelector.choose(
            ranked,
            seed,
            LocalActionMixingContext(
                personality = profile.personality,
                memory = difficultyContext.memory,
                style = mind.trainerStyle,
                riskBudget = mind.riskBudget,
            ),
        )
        val selected = selection.rank
        val confidence = (0.35 + selection.probability * 0.6).coerceIn(0.35, 0.99)
        return CompletableFuture.completedFuture(
            BattleDecision(
                requestId = context.requestId,
                actionId = selected.outcome.candidate.actionId,
                confidence = confidence,
                advice = LocalBattleMind.advice(selected, difficultyContext, strategy, profile),
                tags = buildSet {
                    addAll(setOf(
                    "local_tactical_v4",
                    "mixed_top40",
                    "contextual_human_mix",
                    "persistent_intent",
                    "evidence_gated_mixup",
                    "position_risk_budget",
                    "choice_pool_${selection.shortlistSize}",
                    "choice_seed_${selection.seed.toULong().toString(16)}",
                    "difficulty_${profile.difficulty.tier.name.lowercase()}",
                     "lookahead_turns_${lookahead.depthCompleted}",
                     "lookahead_nodes_${lookahead.nodesVisited}",
                    "lookahead_pruned_${lookahead.branchesPruned}",
                     ))
                    if (lookahead.truncated) add("lookahead_truncated")
                    if (lookahead.publicResponseIncomplete) add("lookahead_public_response_incomplete")
                },
            ),
        )
    }

    override fun closeSession(session: BattleBrainSession, result: BattleBrainCloseResult) = Unit

    private data class Session(
        override val sessionId: UUID,
        val battleId: UUID,
        val trainerPersonaId: String?,
        val strategy: BattleStrategyBrief?,
        val trainerProfile: BattleTrainerProfile,
    ) : BattleBrainSession

    private fun BattleDecisionContext.withoutActivePlan(): BattleDecisionContext = BattleDecisionContext(
        requestId = requestId,
        state = state,
        candidates = candidates,
            deadlineEpochMillis = deadlineEpochMillis,
            memory = BattleTacticalMemoryView(
            activePlan = null,
            activePlanOwner = null,
            tendencies = memory.tendencies,
            predictionCalibration = memory.predictionCalibration,
            turnsSinceLastSwitch = memory.turnsSinceLastSwitch,
            switchesThisBattle = memory.switchesThisBattle,
            lastMoveId = memory.lastMoveId,
            sameMoveRepeatCount = memory.sameMoveRepeatCount,
            patternExposureCount = memory.patternExposureCount,
            patternResponseShiftEvidence = memory.patternResponseShiftEvidence,
            opponentResponseVolatility = memory.opponentResponseVolatility,
            nonProgressControlStreak = memory.nonProgressControlStreak,
            ),
            publicActionCatalog = publicActionCatalog,
        )
}

internal object LocalTacticalScorer {
    fun score(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief? = null,
        profile: BattleTrainerProfile = BattleTrainerProfile.balanced(),
    ): Double = when (candidate.kind) {
        BattleActionKind.USE_MOVE -> scoreMove(candidate, context, strategy, profile)
        BattleActionKind.SWITCH -> scoreSwitch(candidate, context, strategy, profile)
        BattleActionKind.COMPOSITE -> candidate.componentActions.sumOf { score(it, context, strategy, profile) } +
            LocalTacticalSituationalEvaluator.compositeCoordinationAdjustment(candidate, context)
        BattleActionKind.WAIT -> -100.0
        BattleActionKind.FORFEIT -> -10_000.0
    }

    private fun scoreMove(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
    ): Double {
        val details = candidate.moveDetails
            ?: return 5.0 +
                strategyMoveAdjustment(candidate, context, strategy)
        val facts = candidate.facts
        val damageRange = facts?.standardDamageFractionRange
        val accuracy = facts?.baseAccuracyProbability ?: details.accuracy / 100.0
        val pressure = if (details.damageCategory == BattleMoveDamageCategory.STATUS) {
            LocalTacticalSituationalEvaluator.statusPressure(candidate, context, accuracy)
        } else if (damageRange != null) {
            (damageRange.minimum + damageRange.maximum) * 50.0 * accuracy
        } else {
            // Preserve the established operation order for deterministic tie-breaking.
            // The published value is the same base accuracy fact, not a resolved hit chance.
            (details.power * details.accuracy / 100.0) *
                (facts?.baseSameTypeAttackBonus ?: publicSameTypeBonus(candidate, context)) *
                (facts?.typeChartMultiplier ?: publicTypeMultiplier(candidate, context))
        }
        val priorityBonus = when {
            details.priority <= 0 -> details.priority * 2.0
            opponentActiveHp(context) <= CRITICAL_HP -> details.priority * 25.0
            allyActiveHp(context) <= CRITICAL_HP -> details.priority * 8.0
            else -> details.priority * 2.0
        }
        val knockoutBonus = LocalTacticalSituationalEvaluator.knockoutAdjustment(candidate, accuracy)
        val recoilPenalty = facts?.selfRecoilFractionRange?.let { (it.minimum + it.maximum) * 50.0 } ?: 0.0
        return pressure + priorityBonus + knockoutBonus - recoilPenalty - publicAllyCollateral(candidate, context) -
            LocalTacticalSituationalEvaluator.activePersistentEffectRefreshPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.expiredFirstActiveTurnPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.saturatedStatStagePenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.unmetPublicRequirementPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.recentPublicFailurePenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.repeatedProtectionPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.consecutiveUseForbiddenPenalty(candidate, context) -
            LocalTacticalSituationalEvaluator.forcedTempoPenalty(candidate, context) +
            mechanicResourceAdjustment(candidate) +
            selfPatternAdjustment(candidate, context, profile) +
            strategyMoveAdjustment(candidate, context, strategy)
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
    ): Double {
        val active = LocalPublicPositionFacts.activeAlly(candidate, context)
        val activeHp = active?.hpFraction ?: allyActiveHp(context)
        val target = LocalPublicPositionFacts.switchTarget(candidate, context)
        val targetPostEntryHp = target?.let {
            LocalTacticalSituationalEvaluator.postEntryHp(candidate, it.hpFraction)
        }
        val currentRisk = active?.let { LocalPublicPositionFacts.defensiveExposure(it, context) } ?: 1.0
        val targetRisk = target?.let {
            LocalPublicPositionFacts.defensiveExposure(it, context, candidate.actorSlot)
        } ?: 1.0
        val riskImprovement = (currentRisk - targetRisk).coerceAtLeast(0.0)
        val riskWorsening = (targetRisk - currentRisk).coerceAtLeast(0.0)
        val healthAdvantage = targetPostEntryHp?.let { (it - activeHp).coerceAtLeast(0.0) } ?: 0.0
        val hasPublicPositioningGain = target != null && (
            riskImprovement > 0.0 ||
                healthAdvantage >= MEANINGFUL_SWITCH_HEALTH_ADVANTAGE ||
                activeHp <= CRITICAL_HP
            )
        val baseScore = if (target == null) {
            -SWITCH_TEMPO_PENALTY +
                (if (activeHp <= CRITICAL_HP) CRITICAL_SWITCH_BONUS else 0.0)
        } else {
            -SWITCH_TEMPO_PENALTY +
                healthAdvantage * SWITCH_HEALTH_ADVANTAGE_WEIGHT +
                riskImprovement * PUBLIC_RISK_IMPROVEMENT_WEIGHT +
                (if (active != null && LocalPublicPositionFacts.isPublicKnockoutThreat(active, context) && riskImprovement > 0.0) {
                    PUBLIC_KO_THREAT_SWITCH_BONUS
                } else {
                    0.0
                }) +
                (if (activeHp <= CRITICAL_HP) CRITICAL_SWITCH_BONUS else 0.0) -
                riskWorsening * PUBLIC_RISK_WORSENING_WEIGHT -
                targetRisk * 10.0
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
        val frequencyPenalty = (context.memory.switchesThisBattle * SWITCH_FATIGUE_PER_USE)
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

    private fun mechanicResourceAdjustment(candidate: BattleActionCandidate): Double =
        if (candidate.mechanic == null) 0.0 else UNJUSTIFIED_MECHANIC_PENALTY

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
            details?.targetPattern == BattleMoveTargetPattern.SELECTED
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

    private fun canonicalResourceId(id: String): String =
        id.substringAfter(':').lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

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

            BattleMoveTargetPattern.SELECTED -> {
                if (candidate.targets.isEmpty()) {
                    activeOpponents.take(1)
                } else {
                    val slots = candidate.targets.filter { it.side == BattleSide.OPPONENT }
                        .mapTo(linkedSetOf()) { it.slot }
                    activeOpponents.filter { it.activeSlot in slots }
                }
            }

            else -> emptyList()
        }
        if (targets.isEmpty()) return 0.0
        val effectivenessSum = targets.sumOf { target ->
            if (target.knownTypeIds.isEmpty()) {
                1.0
            } else {
                StandardTypeEffectiveness.multiplier(moveType, target.knownTypeIds)
            }
        }
        return effectivenessSum * spreadDamageModifier(candidate, context)
    }

    private fun publicAllyCollateral(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
    ): Double {
        val details = candidate.moveDetails ?: return 0.0
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0
        val actorSlot = candidate.actorSlot
        val activeAllies = activePokemon(context, BattleSide.ALLY)
        val targets = when (details.targetPattern) {
            BattleMoveTargetPattern.ALL_ACTIVE -> activeAllies
            BattleMoveTargetPattern.ALL_ADJACENT -> activeAllies.filter { it.activeSlot != actorSlot }
            BattleMoveTargetPattern.ALL_ALLIES -> activeAllies
            BattleMoveTargetPattern.SELECTED -> {
                val slots = candidate.targets.filter { it.side == BattleSide.ALLY }
                    .mapTo(linkedSetOf()) { it.slot }
                activeAllies.filter { it.activeSlot in slots }
            }
            else -> emptyList()
        }
        val basePressure = details.power * details.accuracy / 100.0 * publicSameTypeBonus(candidate, context)
        return targets.sumOf { target ->
            val multiplier = if (target.knownTypeIds.isEmpty()) {
                1.0
            } else {
                StandardTypeEffectiveness.multiplier(details.typeId, target.knownTypeIds)
            }
            basePressure * multiplier
        } * spreadDamageModifier(candidate, context)
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
        return if (actor.knownTypeIds.any { it.equals(moveType, ignoreCase = true) }) 1.5 else 1.0
    }

    private fun allyActiveHp(context: BattleDecisionContext): Double = activeHp(context, BattleSide.ALLY)

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
    private const val CRITICAL_SWITCH_BONUS = 60.0
    private const val CRITICAL_SWITCH_TARGET_PENALTY = CRITICAL_SWITCH_BONUS
    private const val MEANINGFUL_SWITCH_HEALTH_ADVANTAGE = 0.15
    private const val SAME_TURN_SWITCH_PENALTY = 30.0
    private const val NEXT_TURN_SWITCH_PENALTY = 20.0
    private const val TWO_TURN_SWITCH_PENALTY = 12.0
    private const val THREE_TURN_SWITCH_PENALTY = 5.0
    private const val SWITCH_FATIGUE_PER_USE = 3.0
    private const val MAX_SWITCH_FATIGUE_PENALTY = 15.0
    private const val UNJUSTIFIED_MECHANIC_PENALTY = -25.0
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
