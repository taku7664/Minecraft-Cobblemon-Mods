package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionOutcome
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlan
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlanIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeProductSeedPolicy

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
    val sessionState: NativeProductSessionState? = null,
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
    shouldContinue: () -> Boolean,
) -> Double

/** Converts the opening public posterior into native product ranks on the existing score scale. */
internal class NativeInitialProductDecisionEvaluator(
    private val planWorlds: NativeWorldPlanner = NativeInitialProductWorldPlanner()::plan,
    private val searchWorlds: NativeWorldSearcher = NativeProductWorldSearchAggregator()::search,
    private val reconcileSession: NativeSessionReconciler = NativeProductSessionReconciler()::reconcile,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val leafEvaluator: NativeLeafEvaluator = { state, source, tuning, shouldContinue ->
        LocalLookaheadStateEvaluator.evaluate(
            state = state,
            source = source,
            shouldContinue = shouldContinue,
            tuning = tuning,
        )
    },
) {
    fun evaluate(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        budget: LocalLookaheadBudget,
        sessionState: NativeProductSessionState? = null,
    ): NativeInitialProductDecisionEvaluation {
        if (sessionState != null) {
            return evaluateContinuation(context, profile, tuning, budget, sessionState)
        }
        if (!isOpeningCandidate(context)) {
            return NativeInitialProductDecisionEvaluation(NativeInitialProductDecisionStatus.NOT_APPLICABLE)
        }
        val plan = planWorlds(context, profile.difficulty.tier)
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
            val value = leafEvaluator(world.publicContext.state, world.publicContext, tuning) {
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
                    NativeProductWorldSearchInput(
                        key = NativeSearchWorldKey(world.hypothesisId, sampleIndex),
                        probability = world.probability,
                        definition = world.definition,
                        publicState = world.publicContext.state,
                        evaluate = { state ->
                            leafEvaluator(state, world.publicContext, tuning) {
                                nanoTime() - deadlineNanos < 0L
                            }
                        },
                    )
                },
                productActions = context.candidates,
                maxDepth = profile.difficulty.lookaheadPlies.coerceAtLeast(1),
                nodeLimit = budget.nodeLimit,
                deadlineNanos = deadlineNanos,
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
            )
        }
        return NativeInitialProductDecisionEvaluation(
            status = NativeInitialProductDecisionStatus.AVAILABLE,
            ranked = NativeProductRankAdapter.rank(search.rootValues, rootBaseline),
            depthCompleted = search.depthCompleted,
            nodesVisited = search.nodesVisited,
            truncated = search.status == NativeProductWorldSearchStatus.PARTIAL_DEPTH,
            searchStatus = search.status,
            sessionState = NativeProductSessionState(
                battleId = context.state.battleId,
                format = context.state.format,
                rulesFingerprint = search.rootSnapshots.values.first().rulesFingerprint,
                worlds = sampledWorlds.map { (world, sampleIndex) ->
                    val key = NativeSearchWorldKey(world.hypothesisId, sampleIndex)
                    NativeProductSessionWorld(
                        key = key,
                        probability = world.probability,
                        definition = world.definition,
                        rootSnapshot = search.rootSnapshots.getValue(key),
                        publicContext = world.publicContext,
                    )
                },
                publicTurn = context.state.turn,
                lastObservedEventSequence = context.state.observedEvents.lastOrNull()?.sequence,
                trainerTier = profile.difficulty.tier,
            ),
        )
    }

    private fun evaluateContinuation(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        budget: LocalLookaheadBudget,
        sessionState: NativeProductSessionState,
    ): NativeInitialProductDecisionEvaluation {
        val deadlineNanos = nativeDeadline(context.deadlineEpochMillis, budget.timeMillis)
            ?: return reconciliationFailure(NativeProductSessionReconcileStatus.DEADLINE_EXHAUSTED)
        val reconciliation = reconcileSession(sessionState, context, deadlineNanos)
        if (reconciliation.status != NativeProductSessionReconcileStatus.AVAILABLE) {
            return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.RECONCILIATION_FAILED,
                reconciliationStatus = reconciliation.status,
                failedWorldId = reconciliation.failedWorldId,
            )
        }
        val reconciled = requireNotNull(reconciliation.sessionState) {
            "An available native reconciliation must return its conditioned session"
        }
        if (budget.nodeLimit < reconciled.worlds.size) {
            return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                searchStatus = NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH,
            )
        }

        var rootBaseline = 0.0
        reconciled.worlds.forEach { world ->
            if (nanoTime() - deadlineNanos >= 0L) {
                return NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                    searchStatus = NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH,
                )
            }
            val value = leafEvaluator(world.publicContext.state, world.publicContext, tuning) {
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
        val search = searchWorlds(
            NativeProductWorldSearchRequest(
                worlds = reconciled.worlds.map { world ->
                    NativeProductWorldSearchInput(
                        key = world.key,
                        probability = world.probability,
                        definition = world.definition,
                        publicState = world.publicContext.state,
                        rootSnapshot = world.rootSnapshot,
                        evaluate = { state ->
                            leafEvaluator(state, world.publicContext, tuning) {
                                nanoTime() - deadlineNanos < 0L
                            }
                        },
                    )
                },
                productActions = context.candidates,
                maxDepth = profile.difficulty.lookaheadPlies.coerceAtLeast(1),
                nodeLimit = budget.nodeLimit,
                deadlineNanos = deadlineNanos,
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
            )
        }
        val expectedWorldKeys = reconciled.worlds.mapTo(linkedSetOf()) { it.key }
        if (search.rootSnapshots.keys != expectedWorldKeys) {
            return NativeInitialProductDecisionEvaluation(
                status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                depthCompleted = search.depthCompleted,
                nodesVisited = search.nodesVisited,
                searchStatus = NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED,
            )
        }
        val searchedWorlds = reconciled.worlds.map { world ->
            world.copy(rootSnapshot = search.rootSnapshots.getValue(world.key))
        }
        return NativeInitialProductDecisionEvaluation(
            status = NativeInitialProductDecisionStatus.AVAILABLE,
            ranked = NativeProductRankAdapter.rank(search.rootValues, rootBaseline),
            depthCompleted = search.depthCompleted,
            nodesVisited = search.nodesVisited,
            truncated = search.status == NativeProductWorldSearchStatus.PARTIAL_DEPTH,
            searchStatus = search.status,
            sessionState = reconciled.copy(worlds = searchedWorlds),
        )
    }

    private fun reconciliationFailure(
        status: NativeProductSessionReconcileStatus,
    ) = NativeInitialProductDecisionEvaluation(
        status = NativeInitialProductDecisionStatus.RECONCILIATION_FAILED,
        reconciliationStatus = status,
    )

    private fun isOpeningCandidate(context: BattleDecisionContext): Boolean =
        context.state.turn in 0..1 && context.opponentTeamPreview != null && context.exactOwnTeam != null

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
    }
}

/** Keeps product selection metadata but does not import any handmade transition score. */
internal object NativeProductRankAdapter {
    fun rank(
        values: List<NativeRootActionValue>,
        rootBaseline: Double = 0.0,
    ): List<LocalBattleActionRank> {
        require(rootBaseline.isFinite())
        return LocalBattleActionPolicy.sort(values.map { value ->
            val scaled = (value.value - rootBaseline) * BOARD_TO_SCORE
            LocalBattleActionRank(
                outcome = neutralOutcome(value.action, scaled),
                decisionTier = 0,
                comparisonValue = scaled,
                lookaheadUtility = scaled,
                executionProbability = 1.0,
                worstResponseHpRetention = 1.0,
            )
        })
    }

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
