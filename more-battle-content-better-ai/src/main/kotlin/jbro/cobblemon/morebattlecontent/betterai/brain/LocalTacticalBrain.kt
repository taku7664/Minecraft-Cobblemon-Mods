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
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleMind
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalRootDecisionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.morebattlecontent.betterai.policy.forPlanOwner
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadDecisionSignature
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluation
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import kotlin.math.roundToInt

private const val WEAKER_CHOICE_MARGIN = 0.05

internal fun interface NativeInitialDecisionSource {
    fun evaluate(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        budget: LocalLookaheadBudget,
        sessionState: NativeProductSessionState?,
    ): NativeInitialProductDecisionEvaluation
}

private val defaultNativeInitialDecisionEvaluator = NativeInitialProductDecisionEvaluator()

internal class NativeInitialProductDecisionException(
    val evaluation: NativeInitialProductDecisionEvaluation,
) : IllegalStateException(
    buildString {
        append("Native product decision failed: ")
        append(evaluation.status.name)
        evaluation.reconciliationStatus?.let { append(" reconcile=").append(it.name) }
        evaluation.searchStatus?.let { append(" search=").append(it.name) }
        evaluation.failedWorldId?.let { append(" world=").append(it) }
        if (evaluation.planIssues.isNotEmpty()) {
            append(" issues=")
            append(evaluation.planIssues.joinToString(",") { it.code.name })
        }
    },
)

internal class LocalTacticalBrain(
    private val actionSelector: LocalActionSelector = LocalWeightedActionSelector(),
    private val tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    private val lookaheadBudget: (BattleTrainerTier) -> LocalLookaheadBudget = LocalLookaheadBudgetPolicy::forTier,
    private val nativeInitialDecision: NativeInitialDecisionSource =
        NativeInitialDecisionSource { context, profile, localTuning, budget, sessionState ->
            defaultNativeInitialDecisionEvaluator.evaluate(context, profile, localTuning, budget, sessionState)
        },
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
        fun mixingContext(
            ranked: List<LocalBattleActionRank>,
            authoritativeSimulationScores: Boolean = false,
        ): LocalActionMixingContext {
            return LocalActionMixingContext(
                personality = profile.personality,
                memory = difficultyContext.memory,
                style = mind.trainerStyle,
                riskBudget = mind.riskBudget,
                decisionRegretBand = decidingProfile.difficulty.decisionRegretBand,
                decisionShortlistWidth = decidingProfile.difficulty.decisionShortlistWidth,
                uncertainConditionalActionIds = if (authoritativeSimulationScores) emptySet() else ranked.asSequence()
                    .filter {
                        LocalTacticalSituationalEvaluator.pendingDamagingMoveRiskPenalty(
                            it.outcome.candidate,
                            difficultyContext,
                        ) > 0.0
                    }
                    .map { it.outcome.candidate.actionId }
                    .toSet(),
                alreadyBoostedSetupActionIds = if (authoritativeSimulationScores) emptySet() else ranked.asSequence()
                    .filter {
                        LocalTacticalSituationalEvaluator.alreadyBoostedSelfSetup(
                            it.outcome.candidate,
                            difficultyContext,
                        )
                    }
                    .map { it.outcome.candidate.actionId }
                    .toSet(),
                overcommittedSetupActionIds = if (authoritativeSimulationScores) emptySet() else ranked.asSequence()
                    .filter {
                        LocalTacticalSituationalEvaluator.overcommittedSelfSetup(
                            it.outcome.candidate,
                            difficultyContext,
                        )
                    }
                    .map { it.outcome.candidate.actionId }
                    .toSet(),
                tuning = tuning,
                authoritativeSimulationScores = authoritativeSimulationScores,
            )
        }
        val perspectivePokemonIds = calculatedContext.state.pokemon.asSequence()
            .filter { it.side == BattleSide.ALLY }
            .map { it.battlePokemonId }
            .toList()
        val budget = lookaheadBudget(profile.difficulty.tier)
        val continuingNative = active?.nativeProductState != null
        val nativeInitial = nativeInitialDecision.evaluate(
            difficultyContext,
            decidingProfile,
            tuning,
            budget,
            active?.nativeProductState,
        )
        when (nativeInitial.status) {
            NativeInitialProductDecisionStatus.AVAILABLE -> {
                val ranked = nativeInitial.ranked
                val nativeSearchStatus = requireNotNull(nativeInitial.searchStatus)
                val seed = LocalActionChoiceSeed.derive(
                    battleId = battleId,
                    turn = calculatedContext.state.turn,
                    ranked = ranked,
                    perspectivePokemonIds = perspectivePokemonIds,
                )
                val selection = actionSelector.choose(
                    ranked,
                    seed,
                    mixingContext(ranked, authoritativeSimulationScores = true),
                )
                val selected = selection.rank
                active?.nativeProductState = requireNotNull(nativeInitial.sessionState) {
                    "An available native product decision must preserve its reusable roots"
                }.withPendingOwnAction(selected.outcome.candidate)
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
                                "lookahead_turns_${nativeInitial.depthCompleted}",
                                "lookahead_nodes_${nativeInitial.nodesVisited}",
                                "native_search_${nativeSearchStatus.name.lowercase(Locale.ROOT)}",
                            ))
                            add(if (continuingNative) {
                                "native_showdown_continuation"
                            } else {
                                "native_showdown_initial"
                            })
                            if (nativeInitial.truncated) add("lookahead_truncated")
                            addAll(decisionDiagnostics(calculatedContext, selected))
                        },
                    ),
                )
            }
            NativeInitialProductDecisionStatus.PLANNING_FAILED,
            NativeInitialProductDecisionStatus.RECONCILIATION_FAILED,
            NativeInitialProductDecisionStatus.SEARCH_FAILED,
            -> return CompletableFuture.failedFuture(NativeInitialProductDecisionException(nativeInitial))
            NativeInitialProductDecisionStatus.NOT_APPLICABLE -> Unit
        }
        difficultyContext.candidates.singleOrNull()?.let {
            val selected = LocalBattleActionPolicy.rank(
                difficultyContext,
                strategy,
                decidingProfile,
                tuning,
            ).single()
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
        val baseRanked = LocalBattleActionPolicy.rank(difficultyContext, strategy, decidingProfile, tuning)
        val rootRanked = baseRanked
        val lookahead = LocalRecursiveLookaheadEvaluator.evaluate(
            rootRanked,
            difficultyContext,
            decidingProfile,
            tuning,
            strategy = strategy,
            rootChoicePool = if (!tuning.revalidateRootChoicePool) null else { tentative ->
                val refined = LocalRootDecisionPolicy.refine(tentative, difficultyContext).ranked
                LocalWeightedActionSelector().shortlist(refined, mixingContext(refined))
                    .mapTo(linkedSetOf()) { it.outcome.candidate.actionId }
            },
            budget = budget,
            decisionSignature = if (actionSelector !is LocalWeightedActionSelector) null else { tentative ->
                val refined = LocalRootDecisionPolicy.refine(tentative, difficultyContext).ranked
                val tentativeSeed = LocalActionChoiceSeed.derive(
                    battleId = battleId,
                    turn = calculatedContext.state.turn,
                    ranked = refined,
                    perspectivePokemonIds = perspectivePokemonIds,
                )
                val tentativeSelection = actionSelector.choose(
                    refined,
                    tentativeSeed,
                    mixingContext(refined),
                )
                LocalLookaheadDecisionSignature(
                    topActionId = refined.first().outcome.candidate.actionId,
                    selectedActionId = tentativeSelection.rank.outcome.candidate.actionId,
                    shortlistSize = tentativeSelection.shortlistSize,
                )
            },
        )
        val rootDecision = LocalRootDecisionPolicy.refine(lookahead.ranked, difficultyContext)
        val ranked = rootDecision.ranked
        val seed = LocalActionChoiceSeed.derive(
            battleId = battleId,
            turn = calculatedContext.state.turn,
            ranked = ranked,
            perspectivePokemonIds = perspectivePokemonIds,
        )
        val selection = actionSelector.choose(
            ranked,
            seed,
            mixingContext(ranked),
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
                    "lookahead_stop_${lookahead.terminationReason.name.lowercase(Locale.ROOT)}",
                    "lookahead_elapsed_ms_${lookahead.elapsedMillis}",
                     ))
                    if (lookahead.truncated) add("lookahead_truncated")
                    if (lookahead.publicResponseIncomplete) add("lookahead_public_response_incomplete")
                    addAll(decisionDiagnostics(calculatedContext, selected))
                    rootDecision.switchReasonsByActionId[selected.outcome.candidate.actionId]
                        .orEmpty()
                        .forEach { add("switch_reason_${it.name.lowercase()}") }
                    rootDecision.switchVetoes.forEach { add("switch_veto_${it.name.lowercase()}") }
                },
            ),
        )
    }

    override fun closeSession(session: BattleBrainSession, result: BattleBrainCloseResult) {
        (session as? Session)?.nativeProductState = null
    }

    private fun decisionDiagnostics(
        calculatedContext: BattleDecisionContext,
        selected: LocalBattleActionRank,
    ): Set<String> = buildSet {
        // What the trainer could see of the Pokemon in front of it. Without the defender's public
        // types a weak-looking choice can be blindness rather than a bad evaluation, so preserve the
        // distinction on native and legacy decisions alike.
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

        // Record the public one-turn damage comparison only as diagnostics. It never feeds the
        // native rank back into the handmade projector.
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
        if (chosenCandidate != null && chosenCandidate in damaging && strongest != null &&
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
    }

    private class Session(
        override val sessionId: UUID,
        val battleId: UUID,
        val trainerPersonaId: String?,
        val strategy: BattleStrategyBrief?,
        val trainerProfile: BattleTrainerProfile,
        var nativeProductState: NativeProductSessionState? = null,
    ) : BattleBrainSession

    private fun BattleDecisionContext.withoutActivePlan(): BattleDecisionContext = copy(
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
    )
}
