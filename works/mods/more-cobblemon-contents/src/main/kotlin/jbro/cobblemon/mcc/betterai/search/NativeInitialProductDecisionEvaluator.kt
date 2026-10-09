package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.mcc.betterai.evaluation.LocalSetupMovePreference
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionOutcome
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlan
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanIssue
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.betterai.simulation.NativeMechanicAllowance
import jbro.cobblemon.mcc.betterai.simulation.NativeMidBattleStateRules
import jbro.cobblemon.mcc.betterai.simulation.NativeOpeningStateRules
import jbro.cobblemon.mcc.betterai.simulation.NativeProductSeedPolicy
import jbro.cobblemon.mcc.betterai.evaluation.LocalMechanicOptionValue
import jbro.cobblemon.mcc.betterai.evaluation.LocalOpponentThreat
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache

internal enum class NativeInitialProductDecisionStatus {
    NOT_APPLICABLE,
    AVAILABLE,
    PLANNING_FAILED,
    RECONCILIATION_FAILED,
    SEARCH_FAILED,
}

internal data class NativeInitialProductDecisionEvaluation(
    val status: NativeInitialProductDecisionStatus,
    val ranked: List<LocalBattleActionRank> = emptyList(),
    val depthCompleted: Int = 0,
    val nodesVisited: Int = 0,
    val truncated: Boolean = false,
    val planIssues: List<NativeInitialProductWorldPlanIssue> = emptyList(),
    val reconciliationStatus: NativeProductSessionReconcileStatus? = null,
    val searchStatus: NativeProductWorldSearchStatus? = null,
    val failedWorldId: String? = null,
    val failedRunStatus: NativeProductSearchRunStatus? = null,
    val failedRunDetail: String? = null,
    val sessionState: NativeProductSessionState? = null,
    /**
     * Reconciled roots that still match the board after a failed continued search. The Brain keeps
     * them through the legacy fallback so the next turn can continue natively.
     */
    val retainedSessionState: NativeProductSessionState? = null,
    /** The worlds were rebuilt from this mid-battle board rather than carried from the opening. */
    val rebuilt: Boolean = false,
) {
    init {
        require(depthCompleted >= 0)
        require(nodesVisited >= 0)
        require((status == NativeInitialProductDecisionStatus.AVAILABLE) == ranked.isNotEmpty())
        require(status != NativeInitialProductDecisionStatus.PLANNING_FAILED || planIssues.isNotEmpty())
        require(status != NativeInitialProductDecisionStatus.RECONCILIATION_FAILED || reconciliationStatus != null)
        require(status != NativeInitialProductDecisionStatus.AVAILABLE ||
            searchStatus == NativeProductWorldSearchStatus.COMPLETED ||
            searchStatus == NativeProductWorldSearchStatus.PARTIAL_DEPTH
        )
        require(!truncated || searchStatus == NativeProductWorldSearchStatus.PARTIAL_DEPTH)
        require((status == NativeInitialProductDecisionStatus.AVAILABLE) == (sessionState != null))
        require(retainedSessionState == null || status == NativeInitialProductDecisionStatus.SEARCH_FAILED)
    }
}

/**
 * What the matchup rules hand the native search: root candidates it spends nothing on, and material
 * multipliers multiplied into the AI-only threat weights.
 */
internal data class NativeRulePriorities(
    val excludedActionIds: Set<String> = emptySet(),
    val weights: Map<java.util.UUID, Double> = emptyMap(),
    /** The decision's matchup scores; the search tries actions in the order they suggest. */
    val scores: jbro.cobblemon.mcc.betterai.matchup.MatchupScores? = null,
) {
    companion object {
        val NONE = NativeRulePriorities()
    }
}

private typealias NativeWorldPlanner = (
    context: BattleDecisionContext,
    tier: BattleTrainerTier,
) -> NativeInitialProductWorldPlan

private typealias NativeWorldSearcher = (
    request: NativeProductWorldSearchRequest,
) -> NativeProductWorldSearchResult

private typealias NativeSessionReconciler = (
    session: NativeProductSessionState,
    context: BattleDecisionContext,
    deadlineNanos: Long,
) -> NativeProductSessionReconciliation

private typealias NativeLeafEvaluator = (
    state: BattleStateView,
    source: BattleDecisionContext,
    tuning: LocalDecisionTuning,
    /** Shared by every leaf of one world, whose [source] is the same: positions a roll apart reuse its work. */
    cache: LocalProjectedActionCalculationCache,
    shouldContinue: () -> Boolean,
) -> Double

/** Converts the opening public posterior into native product ranks on the existing score scale. */
internal class NativeInitialProductDecisionEvaluator(
    private val planWorlds: NativeWorldPlanner = NativeInitialProductWorldPlanner()::plan,
    private val planMidBattleWorlds: NativeWorldPlanner = NativeInitialProductWorldPlanner()::planMidBattle,
    private val searchWorlds: NativeWorldSearcher = NativeInformationSetSearch()::search,
    private val reconcileSession: NativeSessionReconciler = NativeProductSessionReconciler()::reconcile,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val leafEvaluator: NativeLeafEvaluator = { state, source, tuning, cache, shouldContinue ->
        LocalLookaheadStateEvaluator.evaluate(
            state = state,
            source = source,
            calculationCache = cache,
            shouldContinue = shouldContinue,
            tuning = tuning,
            includePositionEffects = true,
        )
    },
) {
    fun evaluate(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        budget: LocalLookaheadBudget,
        sessionState: NativeProductSessionState? = null,
        rules: NativeRulePriorities = NativeRulePriorities.NONE,
    ): NativeInitialProductDecisionEvaluation {
        if (sessionState != null) {
            return evaluateContinuation(context, profile, tuning, budget, sessionState, rules)
        }
        if (!isOpeningCandidate(context)) return rebuild(context, profile, tuning, budget, rules)
        return evaluateFresh(context, profile, tuning, budget, midBattle = false, rules)
    }

    /**
     * Starts native search over from the current board when no session carries on: the opening was not
     * native, or the public battle contradicted every retained world.
     */
    private fun rebuild(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        budget: LocalLookaheadBudget,
        rules: NativeRulePriorities,
    ): NativeInitialProductDecisionEvaluation {
        if (context.opponentTeamPreview == null || context.exactOwnTeam == null) {
            return NativeInitialProductDecisionEvaluation(NativeInitialProductDecisionStatus.NOT_APPLICABLE)
        }
        NativeMidBattleStateRules.blocker(context.state)?.let { blocker ->
            return NativeInitialProductDecisionEvaluation(
                NativeInitialProductDecisionStatus.NOT_APPLICABLE,
                failedRunDetail = "rebuild:$blocker",
            )
        }
        return evaluateFresh(context, profile, tuning, budget, midBattle = true, rules).copy(rebuilt = true)
    }

    private fun evaluateFresh(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        budget: LocalLookaheadBudget,
        midBattle: Boolean,
        rules: NativeRulePriorities,
    ): NativeInitialProductDecisionEvaluation {
        val allowedMechanics = NativeMechanicAllowance.merge(null, context.candidates)
        val plan = (if (midBattle) planMidBattleWorlds else planWorlds)(context, profile.difficulty.tier)
        if (plan.issues.isNotEmpty()) {
            return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.PLANNING_FAILED,
                planIssues = plan.issues,
            )
        }

        val deadlineNanos = nativeDeadline(context.deadlineEpochMillis, budget.timeMillis)
            ?: return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                searchStatus = NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH,
            )
        var rootBaseline = 0.0
        plan.worlds.forEach { world ->
            if (nanoTime() - deadlineNanos >= 0L) {
                return NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                    searchStatus = NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH,
                )
            }
            val value = leafEvaluator(world.publicContext.state, world.publicContext, tuning, LocalProjectedActionCalculationCache()) {
                nanoTime() - deadlineNanos < 0L
            }
            if (!value.isFinite() || nanoTime() - deadlineNanos >= 0L) {
                return NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                    searchStatus = NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH,
                )
            }
            rootBaseline += world.probability * value
        }
        val sampledWorlds = NativeChanceSampleAllocator.allocate(
            plan.worlds,
            budget.chanceBranchesPerMove,
        ).flatMap { allocation ->
            val world = allocation.world
            (0 until allocation.sampleCount).map { sampleIndex ->
                world.copy(
                    probability = world.probability / allocation.sampleCount.toDouble(),
                    definition = world.definition.copy(
                        seed = NativeProductSeedPolicy.derive(
                            context.state.battleId,
                            world.hypothesisId,
                            sampleIndex,
                        ),
                    ),
                ) to sampleIndex
            }
        }
        val search = searchWorlds(
            NativeProductWorldSearchRequest(
                worlds = sampledWorlds.map { (world, sampleIndex) ->
                    val cache = LocalProjectedActionCalculationCache()
                    NativeProductWorldSearchInput(
                        key = NativeSearchWorldKey(world.hypothesisId, sampleIndex),
                        probability = world.probability,
                        definition = world.definition,
                        publicState = world.publicContext.state,
                        publicActionCatalog = world.publicContext.publicActionCatalog,
                        evaluate = { state ->
                            leafEvaluator(state, world.publicContext, tuning, cache) {
                                nanoTime() - deadlineNanos < 0L
                            }
                        },
                    )
                },
                productActions = context.candidates,
                maxDepth = if (tuning.doublesSingleTurn && context.state.format == BattleFormat.DOUBLE) 1
                    else (budget.nativePlies ?: profile.difficulty.lookaheadPlies).coerceAtLeast(1),
                responseMemory = context.memory,
                responseInformation = profile.personality.information,
                allowSetupAttackExtension = profile.difficulty.tier == BattleTrainerTier.BOSS &&
                    context.candidates.any { LocalSetupMovePreference.bonus(it, context) > 0.0 },
                excludeFutureAllyVoluntarySwitches = profile.difficulty.tier == BattleTrainerTier.ADVANCED,
                opponentResponseLimit = budget.opponentResponseLimit,
                finalPlyAttacksOnly = budget.finalPlyAttacksOnly,
                allowedMechanics = allowedMechanics,
                opponentThreatWeights = threatWeights(context, profile, budget, rules),
                excludedRootActionIds = rootExclusions(context, rules),
                actionPrior = rules.scores?.let(::NativeMatchupPrior),
                nodeLimit = budget.nativeNodeLimit,
                deadlineNanos = deadlineNanos,
                nanoTime = nanoTime,
            ),
        )
        if (search.status != NativeProductWorldSearchStatus.COMPLETED &&
            search.status != NativeProductWorldSearchStatus.PARTIAL_DEPTH
        ) {
            return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                depthCompleted = search.depthCompleted,
                nodesVisited = search.nodesVisited,
                searchStatus = search.status,
                failedWorldId = search.failedWorldId,
                failedRunStatus = search.failedRunStatus,
                failedRunDetail = search.failedRunDetail,
            )
        }
        return NativeInitialProductDecisionEvaluation(
            status = NativeInitialProductDecisionStatus.AVAILABLE,
            ranked = NativeProductRankAdapter.rank(search.rootValues, rootBaseline, context, profile),
            depthCompleted = search.depthCompleted,
            nodesVisited = search.nodesVisited,
            truncated = search.status == NativeProductWorldSearchStatus.PARTIAL_DEPTH,
            searchStatus = search.status,
            sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = search.rootSnapshots.values.first().rulesFingerprint,
                // Only the worlds the clock let the search finish carry on, renormalized.
                worlds = sampledWorlds.mapNotNull { (world, sampleIndex) ->
                    val key = NativeSearchWorldKey(world.hypothesisId, sampleIndex)
                    search.rootSnapshots[key]?.let { rootSnapshot ->
                        NativeProductSessionWorld(
                            key = key,
                            probability = world.probability,
                            definition = world.definition,
                            rootSnapshot = rootSnapshot,
                            publicContext = world.publicContext,
                        )
                    }
                }.renormalized(),
                publicTurn = context.state.turn,
                lastObservedEventSequence = context.state.observedEvents.lastOrNull()?.sequence,
                trainerTier = profile.difficulty.tier,
                allowedMechanics = allowedMechanics,
            ),
        )
    }

    private fun evaluateContinuation(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        budget: LocalLookaheadBudget,
        sessionState: NativeProductSessionState,
        rules: NativeRulePriorities,
    ): NativeInitialProductDecisionEvaluation {
        val deadlineNanos = nativeDeadline(context.deadlineEpochMillis, budget.timeMillis)
            ?: return reconciliationFailure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
        // A session made without an allowance stays unrestricted; otherwise the set only grows.
        val allowedMechanics = sessionState.allowedMechanics?.let {
            NativeMechanicAllowance.merge(it, context.candidates)
        }
        val reconciliation = reconcileSession(sessionState, context, deadlineNanos)
        if (reconciliation.status != NativeProductSessionReconcileStatus.AVAILABLE) {
            val reconciliationDetail = (reconciliation.observedActionIssues
                .takeIf { reconciliation.inconsistencies.isEmpty() }.orEmpty().map {
                    "${it.code.name}@event${it.eventSequence}" + (it.detail?.let { detail -> " $detail" } ?: "")
                } +
                reconciliation.rootIssues.map { it.code.name } +
                reconciliation.inconsistencies.entries.sortedByDescending { it.value }.take(3)
                    .map { (reason, worlds) -> "$reason x$worlds" } +
                listOfNotNull(reconciliation.failure?.let { "${it.javaClass.simpleName}:${it.message?.take(200)}" }))
                .joinToString(",").ifEmpty { null }
            // The board moved away from every retained world: rebuild them from the board itself.
            val rebuilt = rebuild(context, profile, tuning, budget, rules)
            if (rebuilt.status == NativeInitialProductDecisionStatus.AVAILABLE) {
                return rebuilt.copy(reconciliationStatus = reconciliation.status, failedRunDetail = reconciliationDetail)
            }
            val rebuildDetail = rebuilt.failedRunDetail?.takeIf { it.startsWith("rebuild:") }
                ?: "rebuild:" + (listOfNotNull(rebuilt.status.name, rebuilt.searchStatus?.name, rebuilt.failedRunStatus?.name) +
                    rebuilt.planIssues.take(2).map { it.code.name + (it.detailCode?.let { code -> "/$code" } ?: "") })
                    .joinToString("/")
            return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.RECONCILIATION_FAILED,
                reconciliationStatus = reconciliation.status,
                failedWorldId = reconciliation.failedWorldId,
                failedRunDetail = listOfNotNull(reconciliationDetail, rebuildDetail).joinToString(","),
            )
        }
        val reconciled = requireNotNull(reconciliation.sessionState) {
            "An available native reconciliation must return its conditioned session"
        }
        val retained = reconciled.copy(allowedMechanics = allowedMechanics)
        if (budget.nativeNodeLimit < reconciled.worlds.size) {
            return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                searchStatus = NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH,
                retainedSessionState = retained,
            )
        }

        var rootBaseline = 0.0
        reconciled.worlds.forEach { world ->
            if (nanoTime() - deadlineNanos >= 0L) {
                return NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                    searchStatus = NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH,
                    retainedSessionState = retained,
                )
            }
            val value = leafEvaluator(world.publicContext.state, world.publicContext, tuning, LocalProjectedActionCalculationCache()) {
                nanoTime() - deadlineNanos < 0L
            }
            if (!value.isFinite() || nanoTime() - deadlineNanos >= 0L) {
                return NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                    searchStatus = NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH,
                    retainedSessionState = retained,
                )
            }
            rootBaseline += world.probability * value
        }
        val search = searchWorlds(
            NativeProductWorldSearchRequest(
                worlds = reconciled.worlds.map { world ->
                    val cache = LocalProjectedActionCalculationCache()
                    NativeProductWorldSearchInput(
                        key = world.key,
                        probability = world.probability,
                        definition = world.definition,
                        publicState = world.publicContext.state,
                        publicActionCatalog = world.publicContext.publicActionCatalog,
                        rootSnapshot = world.rootSnapshot,
                        evaluate = { state ->
                            leafEvaluator(state, world.publicContext, tuning, cache) {
                                nanoTime() - deadlineNanos < 0L
                            }
                        },
                    )
                },
                productActions = context.candidates,
                maxDepth = if (tuning.doublesSingleTurn && context.state.format == BattleFormat.DOUBLE) 1
                    else (budget.nativePlies ?: profile.difficulty.lookaheadPlies).coerceAtLeast(1),
                responseMemory = context.memory,
                responseInformation = profile.personality.information,
                allowSetupAttackExtension = profile.difficulty.tier == BattleTrainerTier.BOSS &&
                    context.candidates.any { LocalSetupMovePreference.bonus(it, context) > 0.0 },
                excludeFutureAllyVoluntarySwitches = profile.difficulty.tier == BattleTrainerTier.ADVANCED,
                opponentResponseLimit = budget.opponentResponseLimit,
                finalPlyAttacksOnly = budget.finalPlyAttacksOnly,
                allowedMechanics = allowedMechanics,
                opponentThreatWeights = threatWeights(context, profile, budget, rules),
                excludedRootActionIds = rootExclusions(context, rules),
                actionPrior = rules.scores?.let(::NativeMatchupPrior),
                nodeLimit = budget.nativeNodeLimit,
                deadlineNanos = deadlineNanos,
                nanoTime = nanoTime,
            ),
        )
        if (search.status != NativeProductWorldSearchStatus.COMPLETED &&
            search.status != NativeProductWorldSearchStatus.PARTIAL_DEPTH
        ) {
            return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                depthCompleted = search.depthCompleted,
                nodesVisited = search.nodesVisited,
                searchStatus = search.status,
                failedWorldId = search.failedWorldId,
                failedRunStatus = search.failedRunStatus,
                failedRunDetail = search.failedRunDetail,
                retainedSessionState = retained,
            )
        }
        val expectedWorldKeys = reconciled.worlds.mapTo(linkedSetOf()) { it.key }
        if (!expectedWorldKeys.containsAll(search.rootSnapshots.keys)) {
            return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                depthCompleted = search.depthCompleted,
                nodesVisited = search.nodesVisited,
                searchStatus = NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED,
                retainedSessionState = retained,
            )
        }
        val searchedWorlds = reconciled.worlds.mapNotNull { world ->
            search.rootSnapshots[world.key]?.let { world.copy(rootSnapshot = it) }
        }.renormalized()
        return NativeInitialProductDecisionEvaluation(
            status = NativeInitialProductDecisionStatus.AVAILABLE,
            ranked = NativeProductRankAdapter.rank(search.rootValues, rootBaseline, context, profile),
            depthCompleted = search.depthCompleted,
            nodesVisited = search.nodesVisited,
            truncated = search.status == NativeProductWorldSearchStatus.PARTIAL_DEPTH,
            searchStatus = search.status,
            sessionState = retained.copy(worlds = searchedWorlds),
        )
    }

    private fun List<NativeProductSessionWorld>.renormalized(): List<NativeProductSessionWorld> {
        val mass = sumOf(NativeProductSessionWorld::probability)
        return map { it.copy(probability = it.probability / mass) }
    }

    /** Computed from the real decision context, so weights key the real battle Pokemon IDs. */
    private fun threatWeights(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        budget: LocalLookaheadBudget,
        rules: NativeRulePriorities,
    ): Map<java.util.UUID, Double> {
        val stopAt = nanoTime() + minOf(budget.timeMillis, THREAT_TIME_LIMIT_MILLIS) * NANOS_PER_MILLI
        val threats = LocalOpponentThreat.weights(context, profile.difficulty.tier) { nanoTime() - stopAt < 0L }
        if (rules.weights.isEmpty()) return threats
        // The rules' roles multiply the threat weights, kept inside the threat band.
        val band = LocalOpponentThreat.band(profile.difficulty.tier)
        return (threats.keys + rules.weights.keys).associateWith { id ->
            ((threats[id] ?: 1.0) * (rules.weights[id] ?: 1.0)).coerceIn(band.minimum, band.maximum)
        }
    }

    /** A turn the rules would empty keeps every candidate, as the legacy search does. */
    private fun rootExclusions(context: BattleDecisionContext, rules: NativeRulePriorities): Set<String> =
        rules.excludedActionIds.takeIf { excluded -> context.candidates.any { it.actionId !in excluded } }.orEmpty()

    private fun reconciliationFailure(
        status: NativeProductSessionReconcileStatus,
    ) = NativeInitialProductDecisionEvaluation(
        status = NativeInitialProductDecisionStatus.RECONCILIATION_FAILED,
        reconciliationStatus = status,
    )

    private fun isOpeningCandidate(context: BattleDecisionContext): Boolean =
        context.state.turn in 0..1 && NativeOpeningStateRules.acceptsObservations(context.state) &&
            context.opponentTeamPreview != null && context.exactOwnTeam != null

    private fun nativeDeadline(externalDeadlineMillis: Long, budgetMillis: Long): Long? {
        val nowMillis = nowEpochMillis()
        val remainingMillis = externalDeadlineMillis - nowMillis
        if (remainingMillis <= 0L || budgetMillis <= 0L) return null
        val allowedMillis = minOf(remainingMillis, budgetMillis)
        val now = nanoTime()
        val nanos = if (allowedMillis > Long.MAX_VALUE / NANOS_PER_MILLI) {
            Long.MAX_VALUE
        } else {
            allowedMillis * NANOS_PER_MILLI
        }
        return if (now > Long.MAX_VALUE - nanos) Long.MAX_VALUE else now + nanos
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val THREAT_TIME_LIMIT_MILLIS = 300L
    }
}

/** Keeps product selection metadata but does not import any handmade transition score. */
internal object NativeProductRankAdapter {
    fun rank(
        values: List<NativeRootActionValue>,
        rootBaseline: Double = 0.0,
        context: BattleDecisionContext? = null,
        profile: BattleTrainerProfile? = null,
    ): List<LocalBattleActionRank> {
        require(rootBaseline.isFinite())
        return LocalBattleActionPolicy.sort(values.map { value ->
            val scaled = (value.value - rootBaseline) * BOARD_TO_SCORE
            // Native search sees a mechanic only within its horizon; the value of keeping it for a
            // later turn is priced here, the same way the legacy scorer prices it.
            val mechanicCost = if (context != null && profile != null) {
                LocalMechanicOptionValue.cost(value.action, context, profile)
            } else 0.0
            val setupBonus = (context?.let { LocalSetupMovePreference.bonus(value.action, it) } ?: 0.0) - mechanicCost
            LocalBattleActionRank(
                outcome = neutralOutcome(value.action, scaled + setupBonus).let { outcome ->
                    // A move the target is publicly immune to does nothing whatever the search found around it;
                    // the selector drops it as it does for the legacy scorer.
                    if (context != null && publiclyNullified(value.action, context)) {
                        outcome.copy(publiclyInert = true, executableDamageActions = 0)
                    } else outcome
                },
                decisionTier = 0,
                comparisonValue = scaled + setupBonus,
                lookaheadUtility = scaled,
                executionProbability = 1.0,
                worstResponseHpRetention = 1.0,
            )
        })
    }

    private fun publiclyNullified(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean =
        candidate.kind == BattleActionKind.USE_MOVE &&
            LocalPublicMechanicsKernel.projectMove(candidate, context).publiclyNullified

    private fun neutralOutcome(
        candidate: BattleActionCandidate,
        tacticalUtility: Double = 0.0,
    ): LocalBattleActionOutcome {
        val componentOutcomes = candidate.componentActions.map(::neutralOutcome)
        val executableDamageActions = if (candidate.kind == BattleActionKind.COMPOSITE) {
            componentOutcomes.sumOf(LocalBattleActionOutcome::executableDamageActions)
        } else if (candidate.kind == BattleActionKind.USE_MOVE &&
            candidate.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS &&
            (candidate.moveDetails?.power ?: 0.0) > 0.0
        ) {
            1
        } else {
            0
        }
        return LocalBattleActionOutcome(
            candidate = candidate,
            tacticalUtility = tacticalUtility,
            expectedDamageFraction = 0.0,
            secureStandardKnockouts = 0,
            executableDamageActions = executableDamageActions,
            publiclyInert = false,
            entryFaints = false,
            switchPostEntryHp = null,
            currentDefensiveExposure = null,
            resultingDefensiveExposure = null,
            survivalPositionImprovement = null,
            effectiveAccuracyProbability = candidate.moveDetails?.accuracy?.div(100.0),
            componentOutcomes = componentOutcomes,
        )
    }

    private const val BOARD_TO_SCORE = 100.0
}
