package jbro.cobblemon.morebattlecontent.betterai.brain

import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrain
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainCloseResult
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSession
import jbro.cobblemon.morebattlecontent.api.ai.BattleCandidateFactsView
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecision
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanIntent
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyObjective
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalSituationalEvaluator
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionChoiceSeed
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleMind
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalRootDecisionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.morebattlecontent.betterai.policy.forPlanOwner
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import kotlin.math.roundToInt

private const val WEAKER_CHOICE_MARGIN = 0.05

internal class LocalTacticalBrain(
    private val actionSelector: LocalActionSelector = LocalWeightedActionSelector(),
    private val tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
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
        // Assessed before ranking, not after, because the risk budget it resolves belongs in the
        // scoring rather than only in the draw at the end.
        //
        // `riskBudget` is the trainer's personality risk, shifted by a style offset derived stably
        // from their persona id, shifted again by how far ahead or behind they are. All three were
        // being computed and then handed only to the weighted selector - which was measured to have
        // almost nothing to tilt, so three separate trainers played identically in 40 of 40 recorded
        // positions. Carrying it as the effective personality means every consumer of
        // `personality.riskTolerance` sees the resolved value without a new parameter on any of them.
        val battleId = active?.battleId ?: calculatedContext.state.battleId
        val mind = LocalBattleMind.assess(
            trainerPersonaId = active?.trainerPersonaId,
            battleId = battleId,
            context = difficultyContext,
            profile = profile,
        )
        val decidingProfile = profile.copy(
            personality = profile.personality.copy(riskTolerance = mind.riskBudget),
        )
        val baseRanked = LocalBattleActionPolicy.rank(difficultyContext, strategy, decidingProfile, tuning)
        baseRanked.singleOrNull()?.let { selected ->
            return CompletableFuture.completedFuture(
                BattleDecision(
                    requestId = context.requestId,
                    actionId = selected.outcome.candidate.actionId,
                    confidence = 1.0,
                    advice = LocalBattleMind.advice(selected, difficultyContext, strategy, profile),
                    tags = setOf(
                        "local_tactical_v4",
                        "tuning_${tuning.id}",
                        "single_legal_action",
                        "difficulty_${profile.difficulty.tier.name.lowercase()}",
                    ),
                ),
            )
        }
        val lookahead = LocalRecursiveLookaheadEvaluator.evaluate(
            baseRanked,
            difficultyContext,
            decidingProfile,
            tuning,
            strategy = strategy,
        )
        val rootDecision = LocalRootDecisionPolicy.refine(lookahead.ranked, difficultyContext)
        val ranked = rootDecision.ranked
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
                decisionRegretBand = decidingProfile.difficulty.decisionRegretBand,
                uncertainConditionalActionIds = ranked.asSequence()
                    .filter {
                        LocalTacticalSituationalEvaluator.pendingDamagingMoveRiskPenalty(
                            it.outcome.candidate,
                            difficultyContext,
                        ) > 0.0
                    }
                    .map { it.outcome.candidate.actionId }
                    .toSet(),
                alreadyBoostedSetupActionIds = ranked.asSequence()
                    .filter {
                        LocalTacticalSituationalEvaluator.alreadyBoostedSelfSetup(
                            it.outcome.candidate,
                            difficultyContext,
                        )
                    }
                    .map { it.outcome.candidate.actionId }
                    .toSet(),
                overcommittedSetupActionIds = ranked.asSequence()
                    .filter {
                        LocalTacticalSituationalEvaluator.overcommittedSelfSetup(
                            it.outcome.candidate,
                            difficultyContext,
                        )
                    }
                    .map { it.outcome.candidate.actionId }
                    .toSet(),
                tuning = tuning,
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
                    "tuning_${tuning.id}",
                    "mixed_top40",
                    "contextual_human_mix",
                    "persistent_intent",
                    "evidence_gated_mixup",
                    "position_risk_budget",
                    "choice_pool_${selection.shortlistSize}",
                    "choice_seed_${selection.seed.toULong().toString(16)}",
                    "difficulty_${profile.difficulty.tier.name.lowercase()}",
                     "lookahead_requested_${profile.difficulty.lookaheadPlies}",
                     "lookahead_turns_${lookahead.depthCompleted}",
                     "lookahead_nodes_${lookahead.nodesVisited}",
                    "lookahead_pruned_${lookahead.branchesPruned}",
                    "lookahead_coverage_${(lookahead.publicResponseCoverage * 100).roundToInt()}",
                     ))
                    if (lookahead.truncated) add("lookahead_truncated")
                    if (lookahead.publicResponseIncomplete) add("lookahead_public_response_incomplete")
                    // What the trainer could see of the Pokemon in front of it. A move is ranked almost
                    // entirely on the damage it projects, and that needs the defender's types: without
                    // them every attack scores on base power alone, four moves look nearly equal, and
                    // the draw can land on one the type chart would have ruled out. When that happens
                    // the choice looks like a broken evaluation and is really a blind one, so the two
                    // have to be distinguishable in a log after the fact.
                    val opponentActive = calculatedContext.state.pokemon.filter {
                        it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted
                    }
                    when {
                        opponentActive.isEmpty() -> add("opponent_active_absent")
                        opponentActive.any { it.knownTypeIds.isEmpty() } -> add("opponent_types_unknown")
                        else -> add("opponent_types_known")
                    }
                    if (opponentActive.any { it.combatStats == null }) add("opponent_stats_unknown")
                    selected.outcome.candidate.let { chosen ->
                        add("chose_${chosen.kind.name.lowercase()}")
                        chosen.moveDetails?.typeId?.let { add("chose_type_$it") }
                    }
                    val chosenFacts = calculatedContext.candidates
                        .firstOrNull { it.actionId == selected.outcome.candidate.actionId }?.facts
                    chosenFacts?.typeChartMultiplier
                        ?.let { add("chose_type_multiplier_${(it * 100).roundToInt()}") }
                    // Why this move and not the stronger one, recorded at the moment it is chosen.
                    //
                    // A trainer picking a weak attack while a far better one sits in the same move set
                    // is the complaint that cannot be reproduced on demand: it needs that opponent,
                    // that team and that matchup to come round again, and in a battle tower it may not.
                    // So the decision states its own case whenever the move played is not the hardest
                    // hitting one available - every candidate, its type, what the chart said, and what
                    // damage was projected. Silent on every turn where the obvious move was taken.
                    val damaging = calculatedContext.candidates.filter {
                        it.moveDetails?.damageCategory != null &&
                            it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS
                    }
                    fun expectedDamage(candidate: BattleActionCandidate): Double =
                        candidate.facts?.standardDamageFractionRange
                            ?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0
                    val chosenCandidate = calculatedContext.candidates
                        .firstOrNull { it.actionId == selected.outcome.candidate.actionId }
                    val strongest = damaging.maxByOrNull(::expectedDamage)
                    // Only when an attack was chosen over a better attack. Switching or using a status
                    // move projects no damage by definition, so comparing those against the hardest
                    // hitting option fired the tag on almost every decision and buried the ones worth
                    // reading. A trainer that switches instead of attacking is answering a different
                    // question, not making the mistake this looks for.
                    val chosenIsAttack = chosenCandidate != null && chosenCandidate in damaging
                    if (chosenIsAttack && chosenCandidate != null && strongest != null &&
                        expectedDamage(strongest) > expectedDamage(chosenCandidate) + WEAKER_CHOICE_MARGIN
                    ) {
                        add("weaker_attack_chosen")
                        calculatedContext.candidates.forEach { candidate ->
                            val moveType = candidate.moveDetails?.typeId ?: return@forEach
                            val name = candidate.moveId?.substringAfter(':') ?: candidate.actionId
                            val multiplier = candidate.facts?.typeChartMultiplier
                                ?.let { (it * 100).roundToInt().toString() } ?: "none"
                            val damage = (expectedDamage(candidate) * 1000).roundToInt()
                            add("cand_${name}_${moveType}_x${multiplier}_dmg$damage")
                        }
                    }
                    rootDecision.switchReasonsByActionId[selected.outcome.candidate.actionId]
                        .orEmpty()
                        .forEach { add("switch_reason_${it.name.lowercase()}") }
                    rootDecision.switchVetoes.forEach { add("switch_veto_${it.name.lowercase()}") }
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
            switchPressure = memory.switchPressure,
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
