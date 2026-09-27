package jbro.cobblemon.mcc.betterai.brain

import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.slf4j.LoggerFactory
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleBrain
import jbro.cobblemon.mcc.internal.ai.BattleLeadChoiceContext
import jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds
import jbro.cobblemon.mcc.internal.ai.BattleBrainCloseResult
import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleBrainSession
import jbro.cobblemon.mcc.internal.ai.BattleCandidateFactsView
import jbro.cobblemon.mcc.internal.ai.BattleDecision
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattlePlanIntent
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStrategyBrief
import jbro.cobblemon.mcc.internal.ai.BattleStrategyObjective
import jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalSituationalEvaluator
import jbro.cobblemon.mcc.betterai.policy.LocalActionChoiceSeed
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelection
import jbro.cobblemon.mcc.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelector
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.policy.LocalBattleMind
import jbro.cobblemon.mcc.betterai.policy.LocalRootDecisionPolicy
import jbro.cobblemon.mcc.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.mcc.betterai.policy.forPlanOwner
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadDecisionSignature
import jbro.cobblemon.mcc.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.mcc.betterai.simulation.LocalOpponentStatAssumption
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluation
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionState
import jbro.cobblemon.mcc.betterai.evaluation.LocalOpponentThreat
import jbro.cobblemon.mcc.betterai.policy.LocalLeadChoice
import jbro.cobblemon.mcc.betterai.state.LocalOpponentMoveUsage
import jbro.cobblemon.mcc.betterai.state.LocalStatusMoveBinder
import kotlin.math.roundToInt

private const val WEAKER_CHOICE_MARGIN = 0.05
private const val NATIVE_TEST_TIME_LIMIT_MILLIS = 10_000L
private const val THREAT_TIME_LIMIT_NANOS = 300_000_000L
private val logger = LoggerFactory.getLogger(LocalTacticalBrain::class.java)

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

private fun nativeFailureMessage(
    evaluation: NativeInitialProductDecisionEvaluation,
    context: BattleDecisionContext,
) : String =
    buildString {
        append("Native product decision failed: ")
        append(evaluation.status.name)
        evaluation.reconciliationStatus?.let { append(" reconcile=").append(it.name) }
        evaluation.searchStatus?.let { append(" search=").append(it.name) }
        evaluation.failedRunStatus?.let { append(" run=").append(it.name) }
        evaluation.failedRunDetail?.let { append(" detail=").append(it) }
        evaluation.failedWorldId?.let { append(" world=").append(it.take(120)) }
        if (evaluation.planIssues.isNotEmpty()) {
            append(" issues=")
            append(evaluation.planIssues.joinToString(",") { issue ->
                issue.code.name + (issue.detailCode?.let { "/$it" } ?: "")
            })
            if (evaluation.planIssues.any { it.code.name == "ROSTER_COMPILATION_FAILED" }) {
                append(" public_roster=")
                append("turn:").append(context.state.turn)
                append(";remaining:").append(context.state.remainingPokemonBySide[BattleSide.OPPONENT])
                append(";selection:").append(context.opponentTeamPreview?.selectionSize)
                append(";seen:").append(context.state.pokemon.asSequence()
                    .filter { it.side == BattleSide.OPPONENT }
                    .joinToString("|") { "${it.speciesId}/${it.formId}@${it.activeSlot}" })
                append(";events:").append(context.state.observedEvents.size).append(':')
                append(context.state.observedEvents.asSequence().take(16).joinToString("|") {
                    "${it.kind}@${it.turn}#${it.actorPokemonId?.toString()?.take(8) ?: "-"}"
                })
            }
        }
    }

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
        val decisionStartedAtNanos = System.nanoTime()
        val active = session as? Session
        val profile = active?.trainerProfile ?: BattleTrainerProfile.balanced()
        val strategy = active?.strategy.takeUnless {
            profile.difficulty.tier == BattleTrainerTier.INTRODUCTORY
        }
        // Name the opponent status moves this tier believes in before anything reads the catalog, so
        // the legacy search, the native worlds and the session reconciliation all see the same slots.
        val boundContext = context.copy(publicActionCatalog = LocalStatusMoveBinder.bindCatalog(
            context.state,
            context.publicActionCatalog,
            profile.difficulty.tier,
            LocalOpponentMoveUsage.forFormat(context.state.format),
        ))
        val assumedContext = LocalOpponentStatAssumption.applyToPublicState(boundContext, profile.difficulty.tier)
        val calculatedContext = PublicBattleTacticalCalculator.calculate(assumedContext)
            .forPlanOwner(jbro.cobblemon.mcc.internal.ai.BattlePlanOwner.LOCAL_BRAIN)
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
        val unboundedTestDecision = active?.trainerPersonaId
            ?.startsWith(BattleBrainContentIds.AI_TEST_PERSONA_PREFIX) == true
        if (unboundedTestDecision) {
            AiTestDecisionSnapshot.write(AiTestDecisionSnapshot(
                battleId = battleId,
                turn = context.state.turn,
                trainerPersonaId = active?.trainerPersonaId,
                trainerProfile = profile,
                strategy = active?.strategy,
                nativeContinuation = active?.nativeProductState != null,
                capturedAtEpochMillis = System.currentTimeMillis(),
                context = context,
            ))?.let { path -> logger.info("[BetterAI Trace] battle={} turn={} phase=snapshot path={}", battleId, context.state.turn, path) }
        }
        val decisionTrace = AiTestDecisionTrace.forTestPersona(
            active?.trainerPersonaId, difficultyContext, decisionStartedAtNanos,
        )
        val configuredBudget = lookaheadBudget(profile.difficulty.tier)
        val budget = if (unboundedTestDecision) configuredBudget.copy(timeMillis = Long.MAX_VALUE) else configuredBudget
        // Native nodes are full Showdown turns, orders of magnitude costlier than legacy projections,
        // so the node limit alone never stops them in time. AI test battles lift the wall clock for
        // the legacy search only; the native search keeps a bounded clock and falls back when it
        // completes no depth, exactly as it would in a real battle.
        val nativeBudget = if (unboundedTestDecision) {
            configuredBudget.copy(timeMillis = NATIVE_TEST_TIME_LIMIT_MILLIS)
        } else {
            configuredBudget
        }
        val continuingNative = active?.nativeProductState != null
        val nativeInitial = nativeInitialDecision.evaluate(
            difficultyContext,
            decidingProfile,
            tuning,
            nativeBudget,
            active?.nativeProductState,
        )
        decisionTrace?.nativeSearch(nativeInitial, profile.difficulty.lookaheadPlies, nativeBudget)
        var nativeFallbackStatus: NativeInitialProductDecisionStatus? = null
        // Roots that reconciled with the current board survive a failed native search. The legacy
        // choice made this turn becomes their pending action, so the next turn can continue natively
        // instead of losing native search for the rest of the battle.
        var retainedNativeState: NativeProductSessionState? = null
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
                decisionTrace?.resolved(if (continuingNative) "native_continuation" else "native_initial", ranked, selection)
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
                            if (unboundedTestDecision) add("lookahead_time_unbounded_test")
                            if (nativeInitial.truncated) add("lookahead_truncated")
                            addAll(decisionDiagnostics(calculatedContext, selected))
                        },
                    ),
                )
            }
            NativeInitialProductDecisionStatus.PLANNING_FAILED,
            NativeInitialProductDecisionStatus.RECONCILIATION_FAILED,
            NativeInitialProductDecisionStatus.SEARCH_FAILED,
            -> {
                nativeFallbackStatus = nativeInitial.status
                active?.nativeProductState = null
                retainedNativeState = nativeInitial.retainedSessionState
                decisionTrace?.nativeFallback(nativeInitial.status.name, nativeInitial.planIssues.joinToString(",") {
                    it.code.name + (it.detailCode?.let { detail -> "/$detail" } ?: "")
                })
                logger.warn("Native product decision unavailable; using legacy lookahead: {}",
                    nativeFailureMessage(nativeInitial, difficultyContext))
            }
            NativeInitialProductDecisionStatus.NOT_APPLICABLE -> Unit
        }
        difficultyContext.candidates.singleOrNull()?.let {
            val selected = LocalBattleActionPolicy.rank(
                difficultyContext,
                strategy,
                decidingProfile,
                tuning,
            ).single()
            decisionTrace?.resolved("single_legal", listOf(selected), LocalActionSelection(selected, 0L, 1, 1.0))
            retainedNativeState?.let { active?.nativeProductState = it.withPendingOwnAction(selected.outcome.candidate) }
            return CompletableFuture.completedFuture(
                BattleDecision(
                    requestId = context.requestId,
                    actionId = selected.outcome.candidate.actionId,
                    confidence = 1.0,
                    advice = LocalBattleMind.advice(selected, difficultyContext, strategy, profile),
                    tags = buildSet {
                        add("local_tactical_v4")
                        add("tuning_${tuning.id}")
                        add("single_legal_action")
                        add("difficulty_${profile.difficulty.tier.name.lowercase()}")
                        nativeFallbackStatus?.let { add("native_fallback_${it.name.lowercase(Locale.ROOT)}") }
                        if (retainedNativeState != null) add("native_session_retained")
                    },
                ),
            )
        }
        val baseRanked = LocalBattleActionPolicy.rank(difficultyContext, strategy, decidingProfile, tuning)
        val threatStartedAtNanos = System.nanoTime()
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
            opponentThreatWeights = LocalOpponentThreat.weights(
                difficultyContext,
                profile.difficulty.tier,
                shouldContinue = { System.nanoTime() - threatStartedAtNanos < THREAT_TIME_LIMIT_NANOS },
            ),
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
        decisionTrace?.legacySearch(lookahead, profile.difficulty.lookaheadPlies, budget)
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
        decisionTrace?.resolved("legacy_lookahead", ranked, selection)
        retainedNativeState?.let { active?.nativeProductState = it.withPendingOwnAction(selected.outcome.candidate) }
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
                    nativeFallbackStatus?.let { add("native_fallback_${it.name.lowercase(Locale.ROOT)}") }
                    if (retainedNativeState != null) add("native_session_retained")
                    if (unboundedTestDecision) add("lookahead_time_unbounded_test")
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

    override fun chooseLeads(context: BattleLeadChoiceContext): List<UUID>? {
        val choice = LocalLeadChoice.choose(context) ?: return null
        val species = context.ownTeam.associate { it.battlePokemonId to it.speciesId.substringAfter(':') }
        logger.info(
            "[BetterAI Lead] persona={} tier={} leads={} scores={}",
            context.trainerPersonaId,
            context.trainerProfile.difficulty.tier,
            choice.leads.map { species[it] },
            choice.scores.entries.joinToString(prefix = "{", postfix = "}") { (id, score) ->
                "${species[id]}=${String.format(Locale.ROOT, "%.2f", score)}"
            },
        )
        return choice.leads
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
