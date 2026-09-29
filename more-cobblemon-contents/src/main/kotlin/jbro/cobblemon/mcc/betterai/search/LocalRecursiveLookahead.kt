package jbro.cobblemon.mcc.betterai.search

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.LocalForcedReplacementResolution
import jbro.cobblemon.mcc.betterai.calculation.LocalForcedReplacementResolver
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.mcc.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.mcc.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.mcc.betterai.evaluation.LocalOpponentThreat
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.policy.LocalBattleMind
import jbro.cobblemon.mcc.betterai.search.LocalResponseValue as TurnValue
import jbro.cobblemon.mcc.betterai.search.LocalOpponentResponseValue as OpponentTurnValue
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.betterai.calculation.PublicMoveOutcomeBranchProjector
import jbro.cobblemon.mcc.betterai.state.LocalRecursiveSwitchTempo
import jbro.cobblemon.mcc.betterai.state.PublicTurnProjection
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory
import jbro.cobblemon.mcc.betterai.state.LocalOpponentMoveUsage
import jbro.cobblemon.mcc.betterai.state.LocalMoveUsageLookup
import jbro.cobblemon.mcc.betterai.state.RecursiveHistoryProjector
import jbro.cobblemon.mcc.betterai.state.RecursiveSnapshotActionConstraints
import jbro.cobblemon.mcc.betterai.state.LocalBranchMoveInputs
import jbro.cobblemon.mcc.betterai.state.LocalBranchMoveInputKey
import jbro.cobblemon.mcc.betterai.state.LocalOpponentMoveHypotheses

internal data class LocalLookaheadCoverage(val immediate: Double, val future: Double)

internal data class LocalLookaheadEvaluation(
    val ranked: List<LocalBattleActionRank>,
    val nodesVisited: Int,
    val branchesPruned: Int,
    val depthCompleted: Int,
    val truncated: Boolean,
    val publicResponseIncomplete: Boolean,
    /** Coverage of the last probed root action; not a single weight for every accepted score. */
    val publicResponseCoverage: Double = 1.0,
    /** Public tactical calculations the leaf evaluations actually performed. */
    val leafCalculations: Int = 0,
    /** What the same search would have cost with the caches keyed by object identity. */
    val leafCalculationsUnderIdentityKeying: Int = 0,
    /** The share of [nodesVisited] spent scoring leaves rather than projecting turns. */
    val leafWorkUnits: Int = 0,
    /** Per-action coverage for the accepted depth, excluding discarded partial iterations. */
    val responseCoverageByAction: Map<String, LocalLookaheadCoverage> = emptyMap(),
    /** Why the evaluator stopped, including intentional early exits that are not partial searches. */
    val terminationReason: LocalLookaheadTerminationReason = LocalLookaheadTerminationReason.COMPLETED,
    /** Wall-clock time consumed inside this evaluator invocation. */
    val elapsedMillis: Long = 0L,
    /** Time and nodes spent up to the end of [depthCompleted]; the rest went to a depth that did not finish. */
    val acceptedDepthMillis: Long = 0L,
    val acceptedDepthNodes: Int = 0,
    /** Candidates that finished the depth after [depthCompleted] before the budget ran out, kept at that depth. */
    val partialDepthCandidates: Int = 0,
)

/**
 * Full-turn, public-information minimax for single and double battles.
 *
 * One depth consumes every action submitted by both trainers for that turn. The local side
 * maximizes the resulting board value and the opponent minimizes it. Guessed opponent slots remain
 * unresolved reserve branches, while concrete expected and confirmed slots become explicit responses.
 */
internal object LocalRecursiveLookaheadEvaluator {
    fun evaluate(
        ranked: List<LocalBattleActionRank>,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        clockMillis: () -> Long = System::currentTimeMillis,
        /**
         * The same brief the root ranking used.
         *
         * Needed because withdrawing the heuristic's value half means computing what that half was,
         * and strategy alignment sits on the other side of the line. Scoring it with a brief and
         * withdrawing it without one would delete the alignment along with the value.
         */
        strategy: BattleStrategyBrief? = null,
        // Overridable so the cost of a budget can be measured against the decisions it buys, rather
        // than argued about. Production always takes the tier default.
        budget: LocalLookaheadBudget = LocalLookaheadBudgetPolicy.forTier(profile.difficulty.tier),
        /** Experimental exact pre-weight pool, recomputed after every root adjustment. */
        rootChoicePool: ((List<LocalBattleActionRank>) -> Set<String>)? = null,
        /** The actual production decision represented by a completed depth, for conservative convergence stops. */
        decisionSignature: ((List<LocalBattleActionRank>) -> LocalLookaheadDecisionSignature)? = null,
        /** Override only for synthetic fixtures; production selects the bundled table by battle format. */
        moveUsageForFormat: (BattleFormat) -> LocalMoveUsageLookup? = LocalOpponentMoveUsage::forFormat,
        /** Per-opponent threat multipliers for the AI's own root evaluation; see [LocalOpponentThreat]. */
        opponentThreatWeights: Map<UUID, Double> = emptyMap(),
        /**
         * Candidates the choosing rules have already ruled out (a failed setup gate, a wasted status move).
         * They keep their root ranking but get no search budget and take no root slot.
         */
        excludedActionIds: Set<String> = emptySet(),
        /**
         * What the opponent is predicted to do this turn. It weights the root turn's responses only: deeper
         * turns are other positions the prediction was not made for.
         */
        opponentIntents: List<OpponentIntent> = emptyList(),
    ): LocalLookaheadEvaluation {
        // The search's own projections branch on the tuning's chance model; the root ranking keeps its own.
        return PublicMoveOutcomeBranchProjector.withChanceModel(tuning.chanceModel) {
            evaluateUnderChanceModel(ranked, context, profile, tuning, clockMillis, strategy, budget, rootChoicePool,
                decisionSignature, moveUsageForFormat, opponentThreatWeights, excludedActionIds, opponentIntents)
        }
    }

    private fun evaluateUnderChanceModel(
        ranked: List<LocalBattleActionRank>,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        clockMillis: () -> Long,
        strategy: BattleStrategyBrief?,
        budget: LocalLookaheadBudget,
        rootChoicePool: ((List<LocalBattleActionRank>) -> Set<String>)?,
        decisionSignature: ((List<LocalBattleActionRank>) -> LocalLookaheadDecisionSignature)?,
        moveUsageForFormat: (BattleFormat) -> LocalMoveUsageLookup?,
        opponentThreatWeights: Map<UUID, Double>,
        excludedActionIds: Set<String>,
        opponentIntents: List<OpponentIntent>,
    ): LocalLookaheadEvaluation {
        val requestedDepth = profile.difficulty.lookaheadPlies.coerceAtLeast(1)
        val moveUsage = moveUsageForFormat(context.state.format)
        val searchStartedAt = clockMillis()
        val localDeadline = LocalLookaheadBudgetPolicy.deadline(
            startMillis = searchStartedAt,
            externalDeadlineMillis = context.deadlineEpochMillis,
            budgetMillis = budget.timeMillis,
        )
        // The search re-derives damage itself from public stat ranges; it does not consume the facts
        // attached to the root candidates. If an active Pokemon has no public combat stats, every
        // projected attack in the search deals nothing, while declared status and screen effects still
        // apply normally. The result is not a weaker search, it is a biased one: it can only see the
        // actions it happens to be able to model, and it will rank those above attacks that the facts
        // clearly show are better. Fall back to the flat heuristic instead of trusting a search that is
        // blind to half the move pool.
        if (!canProjectDamage(context)) {
            return LocalLookaheadEvaluation(
                ranked = ranked,
                nodesVisited = 0,
                branchesPruned = 0,
                depthCompleted = 0,
                truncated = false,
                publicResponseIncomplete = true,
                publicResponseCoverage = 0.0,
                terminationReason = LocalLookaheadTerminationReason.PUBLIC_DATA_INCOMPLETE,
                elapsedMillis = elapsedMillis(searchStartedAt, clockMillis()),
            )
        }
        val actionCalculationCache = LocalProjectedActionCalculationCache()
        val baseline = LocalLookaheadStateEvaluator.evaluate(
            state = context.state,
            source = context,
            calculationCache = actionCalculationCache,
            shouldContinue = { clockMillis() < localDeadline - DEADLINE_MARGIN_MILLIS },
            tuning = tuning,
        )
        if (clockMillis() >= localDeadline - DEADLINE_MARGIN_MILLIS) {
            return LocalLookaheadEvaluation(
                ranked = ranked,
                nodesVisited = 0,
                branchesPruned = 0,
                depthCompleted = 0,
                truncated = true,
                publicResponseIncomplete = false,
                publicResponseCoverage = 0.0,
                terminationReason = LocalLookaheadTerminationReason.TIME_BUDGET,
                elapsedMillis = elapsedMillis(searchStartedAt, clockMillis()),
            )
        }
        var accepted = ranked
        var completedDepth = 0
        var totalNodes = 0
        var totalBranchesPruned = 0
        var totalLeafWorkUnits = 0
        var truncated = false
        var terminationReason = LocalLookaheadTerminationReason.COMPLETED
        var publicResponseIncomplete = false
        var lastCoverage = 1.0
        var acceptedDepthMillis = 0L
        var acceptedDepthNodes = 0
        // Candidates that finished the depth the budget cut short; their deeper values were kept.
        var partialDepthCandidates = 0
        // Board gain each candidate showed at a single ply, keyed by action.
        //
        // A one-ply search already resolves the whole turn including the opponent's reply, so this is
        // the search's own account of the turn being played - the same event the immediate heuristic
        // scores. Everything past it is foresight, and only foresight is scaled by the difficulty
        // tier. Without the split, a tier weight would also dial down how well a trainer reads the
        // turn in front of it, which is not what a difficulty setting should mean.
        val singlePlyGain = mutableMapOf<String, Double>()
        val singlePlyCoverage = mutableMapOf<String, Double>()
        // The threat adjustment belongs to the root turn, so it takes the one-ply response weighting;
        // deeper values reweighing the same replies would leak foresight through a zero future weight.
        val singlePlyThreat = mutableMapOf<String, Double>()
        // Opposing Pokemon each candidate's single ply knocked out, for the knockout correction.
        val singlePlyKnockouts = mutableMapOf<String, Double>()
        var acceptedCoverage = emptyMap<String, LocalLookaheadCoverage>()
        var previousDepthCost: LocalCompletedDepthCost? = null
        var previousDecisionSignature: LocalLookaheadDecisionSignature? = null
        // Doubles searches its second turn narrowed; see LocalNarrowSecondTurn.
        var narrowing = false
        // The first turn's value of each root (own action, response) pair, for the narrowed second turn.
        val firstTurnValues = HashMap<Pair<String, String>, TurnValue>()
        for (depth in 1..requestedDepth) {
            val depthStartedAt = clockMillis()
            val narrowed = narrowing
            val search = Search(
                context = context,
                profile = profile,
                tuning = tuning,
                deadlineMillis = localDeadline,
                nodeLimit = budget.nodeLimit,
                chanceBranchesPerMove = budget.chanceBranchesPerMove,
                initialState = context.state,
                initialStateUtility = baseline,
                actionCalculationCache = actionCalculationCache,
                clockMillis = clockMillis,
                moveUsage = moveUsage,
                opponentThreatWeights = opponentThreatWeights,
                opponentIntents = opponentIntents,
                narrow = narrowed,
                firstTurnValues = firstTurnValues,
            )
            // Which candidates this ply is allowed to spend the budget on.
            //
            // Recomputed per depth from the ranking as it now stands, so a candidate the previous ply
            // promoted is searched at the next one. Singles never trims - it does not have enough
            // candidates to reach the limit - so this changes nothing outside doubles.
            val searchable = if (narrowed) {
                accepted.filterNot { it.outcome.candidate.actionId in excludedActionIds }
                    .take(LocalNarrowSecondTurn.ROOT_CANDIDATES).mapTo(linkedSetOf()) { it.outcome.candidate.actionId }
            } else {
                searchableActionIds(ranked.filterNot { it.outcome.candidate.actionId in excludedActionIds }, tuning, context)
            }
            val evaluatedCoverage = mutableMapOf<String, LocalLookaheadCoverage>()
            // Each rank this depth produced, with its unbounded adjustment; see boundSharedAdjustments.
            val producedAdjustments = java.util.IdentityHashMap<LocalBattleActionRank, RawAdjustment>()
            // This depth's root searches, kept so the simultaneous reading can re-weigh them without searching again.
            val rootSearches = HashMap<String, RootActionEvaluation>()
            var opponentMix: Map<String, Double>? = null
            fun evaluateRank(rank: LocalBattleActionRank): LocalBattleActionRank {
                val id = rank.outcome.candidate.actionId
                if ((tuning.revalidateUnsearchedRootLeaders || rootChoicePool != null) && depth > 1 && id !in singlePlyGain) {
                    // A newly admitted root needs its own immediate-turn baseline. Treating its
                    // deeper gain as immediate would leak foresight through a zero future weight.
                    val immediate = search.rootActionValue(context.state, rank.outcome.candidate, 1)
                        ?: return rank
                    singlePlyGain[id] = (immediate.value - baseline) * BOARD_TO_SCORE
                    singlePlyCoverage[id] = search.publicResponseCoverage
                    singlePlyThreat[id] = immediate.threatDelta
                    singlePlyKnockouts[id] = immediate.opponentKnockouts
                }
                val searched = (if (opponentMix != null) rootSearches[id] else null)
                    ?: search.rootActionValue(context.state, rank.outcome.candidate, depth)?.also { rootSearches[id] = it }
                val evaluation = searched?.let { found -> opponentMix?.let { found.againstMix(it, tuning.simultaneousResponseWeight) } ?: found }
                return if (evaluation == null) rank else {
                    // The recursive turn score carries its own knockout value, weighted by the actual
                    // damage-roll KO ratio and execution probability. Remove exactly the knockout
                    // value the root scorer already added, rather than a constant that only matched
                    // the secure case: the legacy correction subtracted a flat 250 per secure KO while
                    // the scorer had also added up to 50 from the situational evaluator, and it
                    // subtracted nothing at all for a merely probable knockout that had still been
                    // priced in.
                    val searchBoardGain = (evaluation.value - baseline) * BOARD_TO_SCORE
                    val actionId = rank.outcome.candidate.actionId
                    if (depth == 1) singlePlyGain[actionId] = searchBoardGain
                    if (depth == 1) singlePlyCoverage[actionId] = search.publicResponseCoverage
                    if (depth == 1) singlePlyThreat[actionId] = evaluation.threatDelta
                    if (depth == 1) singlePlyKnockouts[actionId] = evaluation.opponentKnockouts
                    val immediateGain = singlePlyGain[actionId] ?: searchBoardGain
                    val coverage = LocalLookaheadCoverage(
                        singlePlyCoverage[actionId] ?: search.publicResponseCoverage,
                        search.publicResponseCoverage,
                    )
                    evaluatedCoverage[actionId] = coverage
                    val rootSecureKoBaselineCorrection = if (tuning.legacyRawPowerFallback) {
                        rank.outcome.secureStandardKnockouts * LocalBattleActionPolicy.SECURE_KNOCKOUT_BONUS
                    } else {
                        // Remove only knockout value the search actually re-derived. The search cannot
                        // always reproduce a knockout the published facts assert - the projector needs
                        // defensive stats that may not be public, so an action the facts call a certain
                        // knockout can come back from the search as a no-op. Subtracting the root
                        // scorer's full knockout credit in that case deletes value nothing replaced,
                        // and the deletion lands only on knockout moves, so a guaranteed finisher ends
                        // up ranked below a speculative switch. Capping the correction at what the
                        // search itself gained makes the exchange conservative in the right direction:
                        // no re-derivation, no removal.
                        // This corrects the root turn, not later turns. Using the current depth's
                        // gain here leaks future losses/gains into ranking even at zero foresight.
                        if (tuning.realizedKnockoutCorrection) {
                            // Only the knockouts the search re-derived: capping at its whole gain instead took a
                            // knockout that cost heavy damage for one it had not re-derived, and erased the damage.
                            val realized = (singlePlyKnockouts[actionId] ?: evaluation.opponentKnockouts) * tuning.knockoutMaterialScore
                            rank.outcome.knockoutUtility.coerceAtMost(realized.coerceAtLeast(0.0))
                        } else {
                            rank.outcome.knockoutUtility.coerceAtMost(immediateGain.coerceAtLeast(0.0))
                        }
                    }
                    // Split the search result at the turn boundary and scale only the far side.
                    //
                    // Depth was measured to be a weak difficulty lever precisely because it was not
                    // split: a deeper search disagreed with the immediate heuristic in about 60% of
                    // positions, but its verdict entered the ranking as one lump dominated by the turn
                    // the heuristic had already scored, so the ranking barely moved and every tier
                    // played alike. The near half stays whole for every trainer; the far half is what
                    // a difficulty tier actually buys.
                    val foresightGain = (searchBoardGain - immediateGain) * profile.difficulty.foresightWeight
                    // The knockout correction exists only to stop the search's knockout value landing
                    // on top of the root scorer's. As the search takes over the value half, the root
                    // value it would be correcting for goes away, so the correction retires with it.
                    val authority = tuning.searchAuthority
                    // Stat-stage marginal value is owned by the root only until a projected turn can
                    // replace it. Blend `(search - root)` by public coverage so partial knowledge
                    // retains the unresolved share instead of adding the same setup value twice. The
                    // heuristic withdrawal above already removes its authority-owned share, so only
                    // the share that remains at the root may be subtracted here.
                    val immediateAdjustment = immediateGain -
                        rootSecureKoBaselineCorrection * (1.0 - authority) -
                        rank.outcome.statStageUtility * (1.0 - authority)
                    // Future unknown replacements must not discount an already modelled current turn.
                    val rawAdjustment = immediateAdjustment * coverage.immediate + foresightGain * coverage.future
                    val terminal = kotlin.math.abs(searchBoardGain) >= TERMINAL_SCORE_THRESHOLD
                    val adjustment = if (terminal || tuning.sharedAdjustmentBound) {
                        rawAdjustment
                    } else {
                        rawAdjustment.coerceIn(-tuning.maximumLookaheadAdjustment, tuning.maximumLookaheadAdjustment)
                    }
                    // Hand over as much of the immediate heuristic's value judgement as this tuning
                    // says the search should own. What is withdrawn is only the part a board search
                    // re-derives; the candidate statements the heuristic makes - penalties, ally
                    // collateral, mechanic cost, strategy alignment - are never touched, because
                    // nothing in a board evaluation can reconstruct them.
                    val heuristicValue = rank.outcome.tacticalUtility -
                        LocalTacticalScorer.candidateAdjustments(
                            rank.outcome.candidate,
                            context,
                            strategy,
                            profile,
                        )
                    val withdrawnHeuristicValue = heuristicValue * authority
                    val responseHpBaseline = trackedOwnPokemonIds(context.state, rank.outcome.candidate)
                        .mapNotNull { id -> context.state.pokemon.firstOrNull { it.battlePokemonId == id }?.hpFraction }
                        .averageOrNull()
                        ?: rank.outcome.switchPostEntryHp
                        ?: 1.0
                    // AI-only threat priority on the root turn, added after the opponent's responses
                    // were weighed by the plain value.
                    val threatAdjustment = (singlePlyThreat[actionId] ?: evaluation.threatDelta) *
                        BOARD_TO_SCORE * coverage.immediate
                    rank.copy(
                        comparisonValue = rank.comparisonValue - withdrawnHeuristicValue + adjustment + threatAdjustment,
                        // Everything the search changed relative to the pure heuristic ranking, the
                        // withdrawal included. Reporting only the added term made
                        // `comparisonValue - lookaheadUtility` stop meaning "the heuristic's answer"
                        // the moment any value was withdrawn, which silently turned the influence
                        // measurements into a comparison against the leftover penalty terms.
                        lookaheadUtility = adjustment - withdrawnHeuristicValue + threatAdjustment,
                        executionProbability = evaluation.ownExecutionProbability,
                        worstResponseHpRetention = retention(evaluation.worstResponseRemainingHp, responseHpBaseline),
                        // No confirmed reply at all leaves nothing confirmed to lose HP to.
                        worstConfirmedResponseHpRetention = evaluation.worstConfirmedResponseRemainingHp
                            ?.let { retention(it, responseHpBaseline) }
                            ?: 1.0,
                    ).also { producedAdjustments[it] = RawAdjustment(rawAdjustment, terminal) }
                }
            }
            // Candidates this depth finished. A deeper depth takes them in the previous depth's order, so the
            // leaders finish first; if the budget runs out, the finished ones keep their new values and the
            // rest keep the previous depth's. Values are per turn, so the two depths compare directly.
            val finishedIds = linkedSetOf<String>()
            val evaluated = if (depth > 1) {
                val original = ranked.associateBy { it.outcome.candidate.actionId }
                val deeper = HashMap<String, LocalBattleActionRank>()
                for (previous in accepted) {
                    val id = previous.outcome.candidate.actionId
                    if (id in excludedActionIds || searchable != null && id !in searchable) continue
                    if (search.truncated) break
                    val rank = evaluateRank(original.getValue(id))
                    if (!search.truncated) {
                        deeper[id] = rank
                        finishedIds += id
                    }
                }
                accepted.map { deeper[it.outcome.candidate.actionId] ?: it }.toMutableList()
            } else {
                ranked.map { rank ->
                    val id = rank.outcome.candidate.actionId
                    if (id in excludedActionIds || searchable != null && id !in searchable) rank else evaluateRank(rank)
                }.toMutableList()
            }
            var leaderValidated = narrowed || !tuning.revalidateUnsearchedRootLeaders && rootChoicePool == null
            if (!leaderValidated) {
                // Keep every original candidate and cooperation reservation. Validate an unsearched
                // leader or choice-pool member, then reconsider the pool; never add its adjustment twice.
                // Without a supplied pool, the older experiment certifies only rank one.
                while (!search.truncated) {
                    if (clockMillis() >= localDeadline - DEADLINE_MARGIN_MILLIS) break
                    val ordered = LocalBattleActionPolicy.sort(evaluated)
                    val requiredIds = rootChoicePool?.invoke(ordered)
                        ?: setOf(ordered.first().outcome.candidate.actionId)
                    require(requiredIds.isNotEmpty() && requiredIds.all { id -> ranked.any { it.outcome.candidate.actionId == id } }) {
                        "Root choice pool must contain existing action IDs"
                    }
                    if (clockMillis() >= localDeadline - DEADLINE_MARGIN_MILLIS) break
                    val leaderId = requiredIds.firstOrNull { it !in evaluatedCoverage }
                    if (leaderId == null) {
                        leaderValidated = true
                        break
                    }
                    val index = ranked.indexOfFirst { it.outcome.candidate.actionId == leaderId }
                    evaluated[index] = evaluateRank(ranked[index])
                    if (leaderId !in evaluatedCoverage) break
                }
            }
            if (tuning.simultaneousResponseWeight > 0.0 && !search.truncated) {
                // The root as a simultaneous choice: the opponent's equilibrium mix over the replies, then each
                // candidate's value against that mix instead of against the worst reply to it alone.
                val producedIds = evaluated.filter { it in producedAdjustments }.map { it.outcome.candidate.actionId }
                rootOpponentMix(producedIds.mapNotNull { rootSearches[it] })?.let { mix ->
                    opponentMix = mix
                    val original = ranked.associateBy { it.outcome.candidate.actionId }
                    for (index in evaluated.indices) {
                        if (evaluated[index] !in producedAdjustments) continue
                        evaluated[index] = evaluateRank(original.getValue(evaluated[index].outcome.candidate.actionId))
                    }
                }
            }
            if (tuning.sharedAdjustmentBound) {
                boundSharedAdjustments(evaluated, producedAdjustments, tuning.maximumLookaheadAdjustment)
            }
            totalNodes += search.nodesVisited
            totalBranchesPruned += search.branchesPruned
            totalLeafWorkUnits += search.leafWorkUnits
            publicResponseIncomplete = publicResponseIncomplete || search.publicResponseIncomplete
            lastCoverage = search.publicResponseCoverage
            if (search.truncated) {
                truncated = true
                terminationReason = search.terminationReason ?: LocalLookaheadTerminationReason.TIME_BUDGET
                if (tuning.keepFinishedCandidates && depth > 1 && finishedIds.isNotEmpty()) {
                    accepted = LocalBattleActionPolicy.sort(evaluated)
                    acceptedCoverage = acceptedCoverage + evaluatedCoverage.filterKeys { it in finishedIds }
                    partialDepthCandidates = finishedIds.size
                }
                break
            }
            if (!leaderValidated) {
                truncated = true
                terminationReason = if (clockMillis() >= localDeadline - DEADLINE_MARGIN_MILLIS) {
                    LocalLookaheadTerminationReason.TIME_BUDGET
                } else {
                    LocalLookaheadTerminationReason.ROOT_VALIDATION_INCOMPLETE
                }
                break
            }
            accepted = LocalBattleActionPolicy.sort(evaluated)
            acceptedCoverage = if (narrowed) acceptedCoverage + evaluatedCoverage else evaluatedCoverage.toMap()
            completedDepth = depth
            val depthFinishedAt = clockMillis()
            acceptedDepthMillis = elapsedMillis(searchStartedAt, depthFinishedAt)
            acceptedDepthNodes = totalNodes
            val currentDepthCost = LocalCompletedDepthCost(
                elapsedMillis = elapsedMillis(depthStartedAt, depthFinishedAt),
                nodesVisited = search.nodesVisited,
            )
            val currentDecisionSignature = decisionSignature?.invoke(accepted)
            if (depth == 1 && requestedDepth > 1 && tuning.narrowSecondTurn && context.state.format == BattleFormat.DOUBLE) {
                narrowing = true
                previousDepthCost = currentDepthCost
                previousDecisionSignature = currentDecisionSignature
                continue
            }
            val admission = LocalDepthAdmissionPolicy.afterCompletedDepth(
                completedDepth = completedDepth,
                requestedDepth = requestedDepth,
                remainingMillis = remainingMillis(localDeadline - DEADLINE_MARGIN_MILLIS, depthFinishedAt),
                previousCost = previousDepthCost,
                currentCost = currentDepthCost,
                previousSignature = previousDecisionSignature,
                currentSignature = currentDecisionSignature,
                rootPairs = if (tuning.skipHopelessDepth) search.rootPairsProjected else null,
                nodeLimit = budget.nodeLimit,
            )
            previousDepthCost = currentDepthCost
            previousDecisionSignature = currentDecisionSignature
            when (admission) {
                LocalDepthAdmissionDecision.CONTINUE -> Unit
                LocalDepthAdmissionDecision.STOP_PREDICTED_COST -> {
                    truncated = true
                    terminationReason = LocalLookaheadTerminationReason.PREDICTED_NEXT_DEPTH_COST
                    break
                }
                LocalDepthAdmissionDecision.STOP_STABLE_DECISION -> {
                    terminationReason = LocalLookaheadTerminationReason.STABLE_DECISION
                    break
                }
            }
        }
        if (tuning.unsearchedTakeMedianAdjustment && acceptedCoverage.isNotEmpty()) {
            // A candidate the search never reached kept the heuristic's value while the searched ones took the
            // search's cautious correction, so skipping the search was an advantage and a doubles Boss often
            // picked the joint action nobody had looked at. It takes the searched candidates' median correction.
            val searchedLooks = accepted.filter { it.outcome.candidate.actionId in acceptedCoverage }.map { it.lookaheadUtility }.sorted()
            // Never a gain: a search that found the searched candidates better than the heuristic said is no
            // evidence about one it did not search, and lifting those lifted untested joints into the choice pool.
            val median = (if (searchedLooks.size % 2 == 1) searchedLooks[searchedLooks.size / 2]
                else (searchedLooks[searchedLooks.size / 2 - 1] + searchedLooks[searchedLooks.size / 2]) / 2.0).coerceAtMost(0.0)
            accepted = LocalBattleActionPolicy.sort(accepted.map { rank ->
                val id = rank.outcome.candidate.actionId
                if (id in acceptedCoverage || id in excludedActionIds) rank
                else rank.copy(comparisonValue = rank.comparisonValue + median, lookaheadUtility = rank.lookaheadUtility + median)
            })
        }
        return LocalLookaheadEvaluation(
            ranked = accepted,
            nodesVisited = totalNodes,
            branchesPruned = totalBranchesPruned,
            depthCompleted = completedDepth,
            truncated = truncated,
            publicResponseIncomplete = publicResponseIncomplete,
            publicResponseCoverage = lastCoverage,
            leafCalculations = actionCalculationCache.calculationsPerformed,
            leafCalculationsUnderIdentityKeying = actionCalculationCache.calculationsUnderIdentityKeying,
            leafWorkUnits = totalLeafWorkUnits,
            responseCoverageByAction = acceptedCoverage,
            terminationReason = terminationReason,
            elapsedMillis = elapsedMillis(searchStartedAt, clockMillis()),
            acceptedDepthMillis = acceptedDepthMillis,
            acceptedDepthNodes = acceptedDepthNodes,
            partialDepthCandidates = partialDepthCandidates,
        )
    }

    /**
     * Whether the search can model attacks at all in this position.
     *
     * The Showdown projection needs a level and public combat stat ranges for both the attacker and
     * the defender. Without them every projected attack resolves to no damage, so the search would
     * compare a real status effect against an attack it believes does nothing.
     */
    private fun canProjectDamage(context: BattleDecisionContext): Boolean {
        val actives = context.state.pokemon.filter { it.activeSlot != null && !it.fainted }
        if (actives.isEmpty()) return false
        return actives.all { it.level != null && it.combatStats != null }
    }

    private class Search(
        private val context: BattleDecisionContext,
        private val profile: BattleTrainerProfile,
        private val tuning: LocalDecisionTuning,
        private val deadlineMillis: Long,
        private val nodeLimit: Int,
        private val chanceBranchesPerMove: Int,
        initialState: BattleStateView,
        initialStateUtility: Double,
        private val actionCalculationCache: LocalProjectedActionCalculationCache,
        private val clockMillis: () -> Long,
        private val moveUsage: LocalMoveUsageLookup?,
        private val opponentThreatWeights: Map<UUID, Double> = emptyMap(),
        private val opponentIntents: List<OpponentIntent> = emptyList(),
        /** Search the second turn narrowed; see [LocalNarrowSecondTurn]. */
        private val narrow: Boolean = false,
        /** Filled by the first turn and read by the narrowed second one. */
        private val firstTurnValues: MutableMap<Pair<String, String>, TurnValue> = HashMap(),
    ) {
        var nodesVisited: Int = 0
            private set
        var branchesPruned: Int = 0
            private set
        var truncated: Boolean = false
            private set
        var terminationReason: LocalLookaheadTerminationReason? = null
            private set
        var publicResponseIncomplete: Boolean = false
            private set
        var publicResponseCoverage: Double = 1.0
            private set
        var leafWorkUnits: Int = 0
            private set
        /** Root (own action, response) pairs projected so far: the width one more turn multiplies by. */
        var rootPairsProjected: Int = 0
            private set
        private val memo = HashMap<SearchKey, Continuation>()
        // Structural, like the search's own value memo. Keying leaf values by object identity meant a
        // position reached by two different routes was evaluated twice, and a leaf evaluation is a full
        // tactical calculation for every damaging move on both sides.
        private val stateUtilityMemo = HashMap<LocalBranchMoveInputKey, Double>().apply {
            put(LocalBranchMoveInputs.key(actionCalculationCache.fingerprints.of(initialState), RecursiveActionHistory()), initialStateUtility)
        }

        fun rootActionValue(state: BattleStateView, ownAction: BattleActionCandidate, depth: Int): RootActionEvaluation? {
            // Coverage and memoized leaves describe one root action's public branches. Carrying either
            // into the next candidate makes scores depend on server candidate ordering.
            publicResponseCoverage = 1.0
            memo.clear()
            val initialHistory = RecursiveSnapshotActionConstraints.seed(
                state = state,
                allySwitchedLastTurn = context.memory.turnsSinceLastSwitch?.let { it <= 1 } == true,
                allyLastMoveId = context.memory.lastMoveId,
                allySameMoveRepeatCount = context.memory.sameMoveRepeatCount,
            )
            val opponentActions = completeOpponentActions(state, initialHistory) ?: return null
            if (opponentActions.isEmpty()) {
                publicResponseIncomplete = true
                return null
            }
            val turnStartValue = stateUtility(state, initialHistory)
            val responseValues = mutableListOf<OpponentTurnValue>()
            val deepResponses = if (narrow && depth > 1) deepResponses(opponentActions, state) else null
            val shallowValues = mutableMapOf<Int, TurnValue>()
            for ((index, opponentAction) in opponentActions.withIndex()) {
                if (budgetExhausted()) break
                rootPairsProjected++
                val pair = ownAction.actionId to opponentAction.actionId
                if (deepResponses != null) {
                    // Every response gets its one-turn value: the shallow ones use it, the deep ones measure the shift.
                    (firstTurnValues[pair] ?: turnValue(state, ownAction, opponentAction, 1, initialHistory, rootTurn = true,
                        turnStartValue = turnStartValue))?.let { shallowValues[index] = it }
                    if (index !in deepResponses) continue
                }
                turnValue(
                    state,
                    ownAction,
                    opponentAction,
                    depth,
                    initialHistory,
                    rootTurn = true,
                    turnStartValue = turnStartValue,
                )?.let { value ->
                    if (depth == 1) firstTurnValues[pair] = value
                    responseValues += OpponentTurnValue(opponentAction, value)
                }
            }
            if (deepResponses != null) {
                // Only downward: the reply that punishes this line may be among the ones not searched, so a
                // shallow reply never borrows the deep ones' gain.
                val shift = (responseValues.mapNotNull { response ->
                    shallowValues[opponentActions.indexOf(response.action)]?.let { response.value.value - it.value }
                }.averageOrNull() ?: 0.0).coerceAtMost(0.0)
                opponentActions.withIndex().filter { it.index !in deepResponses }.forEach { (index, action) ->
                    shallowValues[index]?.let { responseValues += OpponentTurnValue(action, it.copy(value = it.value + shift)) }
                }
            }
            val calibratedResponses = calibrateExpectedResponses(responseValues)
            return aggregateOpponentResponses(calibratedResponses, state, ownAction)?.let { robust ->
                val aggregate = LocalOpponentIntentWeights.probabilities(calibratedResponses.map { it.action }, opponentIntents, state)
                    ?.let { LocalSearchResponseObjective.withIntent(robust, calibratedResponses, it, tuning.intentResponseWeight) } ?: robust
                // A risky action may still remain the best-ranked fallback, but it must not enter the
                // exploratory pool merely because some other public response lets it execute.
                val executionProbability = calibratedResponses.minOfOrNull { it.value.ownExecutionProbability }
                    ?: aggregate.ownExecutionProbability
                val worstResponseRemainingHp = calibratedResponses.minOfOrNull { it.value.ownRemainingHpFraction }
                    ?: aggregate.ownRemainingHpFraction
                // Expected slots are inferred, not observed. They keep their full weight in the worst
                // case above, but a veto that overrides the ranking needs confirmed evidence.
                val worstConfirmedResponseRemainingHp = calibratedResponses
                    .filterNot { it.action.containsTag(EXPECTED_OPPONENT_MOVE_TAG) }
                    .minOfOrNull { it.value.ownRemainingHpFraction }
                RootActionEvaluation(
                    aggregate.value,
                    executionProbability,
                    worstResponseRemainingHp,
                    worstConfirmedResponseRemainingHp,
                    aggregate.threatDelta,
                    opponentKnockouts = aggregate.opponentKnockouts,
                    responses = if (tuning.simultaneousResponseWeight > 0.0) {
                        calibratedResponses.associate { it.action.actionId to it.value.value }
                    } else emptyMap(),
                )
            }
        }

        /** The indices of [responses] searched deeper when narrowed: the most likely ones. */
        private fun deepResponses(responses: List<BattleActionCandidate>, state: BattleStateView): Set<Int> {
            val chances = LocalOpponentIntentWeights.probabilities(responses, opponentIntents, state)
            val order = if (chances == null) responses.indices.toList() else responses.indices.sortedByDescending { chances[it] }
            return order.take(LocalNarrowSecondTurn.DEEP_RESPONSES).toSet()
        }

        // Narrowing the AI's own options can only make it miss a good line; narrowing the opponent's can
        // hide the reply that punishes it. So the opponent keeps the wider list.
        private val innerLimitPerSlot: Int
            get() = if (narrow) minOf(LocalNarrowSecondTurn.INNER_PER_SLOT, profile.difficulty.doubleCandidateLimitPerSlot)
                else profile.difficulty.doubleCandidateLimitPerSlot

        private val innerOpponentLimitPerSlot: Int
            get() = if (narrow) minOf(LocalNarrowSecondTurn.INNER_OPPONENT_PER_SLOT, profile.difficulty.doubleCandidateLimitPerSlot)
                else profile.difficulty.doubleCandidateLimitPerSlot

        /**
         * A continuation's value, and the value of the board it started from: after any forced replacement, which
         * belongs to the turn that knocked out, not to the one that follows.
         */
        private class Continuation(val value: Double, val start: Double, val startMaterial: Double)

        private fun leaf(state: BattleStateView, history: RecursiveActionHistory): Continuation =
            stateUtility(state, history).let { Continuation(it, it, LocalBoardMaterial.evaluate(state)) }

        private fun searchState(projectedState: BattleStateView, depth: Int, history: RecursiveActionHistory): Continuation {
            val state = LocalBranchMoveInputs.state(projectedState, context.publicActionCatalog, history)
            if (depth <= 0 || battleEnded(state) || budgetExhausted()) return leaf(state, history)
            forcedReplacementValue(state, depth, history)?.let { return it }
            val key = SearchKey(depth, fingerprint(state), history)
            memo[key]?.let { return it }
            val futureOwnActions = PublicFutureActionFactory.actions(
                state,
                BattleSide.ALLY,
                context.publicActionCatalog,
                history,
                innerLimitPerSlot,
            )
            // Advanced still considers switching now. Only its second simulated turn omits
            // voluntary own switches; forced replacements are resolved above this branch.
            val ownActions = if (profile.difficulty.tier == BattleTrainerTier.ADVANCED) {
                futureOwnActions.filterNot { it.containsActionKind(BattleActionKind.SWITCH) }
            } else futureOwnActions
            val opponentActions = completeOpponentActions(state, history, innerOpponentLimitPerSlot) ?: return leaf(state, history)
            if (ownActions.isEmpty() || opponentActions.isEmpty()) {
                if (opponentActions.isEmpty() && !battleEnded(state)) publicResponseIncomplete = true
                return leaf(state, history)
            }
            val turnStartValue = stateUtility(state, history)
            var best = Double.NEGATIVE_INFINITY
            val simultaneous = tuning.simultaneousResponseWeight
            val table = if (simultaneous > 0.0) ArrayList<DoubleArray>(ownActions.size) else null
            for (ownAction in ownActions) {
                if (budgetExhausted()) break
                val responseValues = mutableListOf<OpponentTurnValue>()
                for (opponentAction in opponentActions) {
                    if (budgetExhausted()) break
                    turnValue(
                        state,
                        ownAction,
                        opponentAction,
                        depth,
                        history,
                        rootTurn = false,
                        turnStartValue = turnStartValue,
                    )
                        ?.let { value -> responseValues += OpponentTurnValue(opponentAction, value) }
                }
                val calibrated = calibrateExpectedResponses(responseValues)
                aggregateOpponentResponses(calibrated, state, ownAction)?.let { responseValue ->
                    best = maxOf(best, responseValue.value)
                }
                if (table != null && calibrated.size == opponentActions.size) {
                    table += DoubleArray(calibrated.size) { calibrated[it].value.value }
                }
            }
            val sequential = if (best.isFinite()) best else stateUtility(state, history)
            // A row whose replies came back short of the full list cannot sit in the table; the sequential value
            // stands alone then.
            val value = if (table != null && table.size >= 2 && best.isFinite()) {
                sequential * (1.0 - simultaneous) + LocalMatrixGame.solve(table.toTypedArray()).value * simultaneous
            } else sequential
            val result = Continuation(value, turnStartValue, LocalBoardMaterial.evaluate(state))
            if (!truncated) memo[key] = result
            return result
        }

        private fun forcedReplacementValue(
            state: BattleStateView,
            depth: Int,
            history: RecursiveActionHistory,
        ): Continuation? {
            fun needsReplacement(side: BattleSide, current: BattleStateView): Boolean =
                current.remainingPokemonBySide.getValue(side).let { remaining ->
                    val slotCapacity = if (current.format == BattleFormat.DOUBLE) 2 else 1
                    val requiredActive = minOf(slotCapacity, remaining)
                    val active = current.pokemon.count {
                        it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
                    }
                    remaining > 0 && active < requiredActive
                }

            val allyMissing = needsReplacement(BattleSide.ALLY, state)
            val opponentMissing = needsReplacement(BattleSide.OPPONENT, state)
            if (!allyMissing && !opponentMissing) return null

            val allyResolution = if (allyMissing) {
                LocalForcedReplacementResolver.resolve(state, BattleSide.ALLY,
                    LocalBranchMoveInputs.context(context, state, history, spendPp = true))
            } else {
                LocalForcedReplacementResolution(listOf(state), 1.0)
            }
            recordReplacementCoverage(allyResolution)
            val allyOptions = allyResolution.states
            if (allyOptions.isEmpty()) {
                return leaf(state, history)
            }
            val allyValues = allyOptions.map { allyState ->
                val opponentResolution = if (opponentMissing) {
                    LocalForcedReplacementResolver.resolve(allyState, BattleSide.OPPONENT,
                        LocalBranchMoveInputs.context(context, allyState, history, spendPp = true))
                } else {
                    LocalForcedReplacementResolution(listOf(allyState), 1.0)
                }
                recordReplacementCoverage(opponentResolution)
                val opponentOptions = opponentResolution.states
                if (opponentOptions.isEmpty()) {
                    leaf(allyState, history)
                } else {
                    opponentOptions.map { replacementState -> searchState(replacementState, depth, history) }.minBy { it.value }
                }
            }
            return if (allyMissing) allyValues.maxByOrNull { it.value } else allyValues.single()
        }

        private fun recordReplacementCoverage(resolution: LocalForcedReplacementResolution) {
            if (resolution.publiclyKnownFraction >= 1.0) return
            publicResponseIncomplete = true
            publicResponseCoverage = minOf(
                publicResponseCoverage,
                confidence(resolution.publiclyKnownFraction),
            )
        }

        /**
         * How much of a search result survives when the opponent has not revealed everything.
         *
         * Refusing to invent hidden moves is right; discarding the search because some exist is not.
         * The legacy gate squared the revealed fraction, so an opponent who had shown nothing yet
         * produced `0.0` - the recursive projection contributed literally nothing and every decision
         * fell through to the flat heuristic. Since a full move set is rarely revealed in a 3v3, that
         * was the normal case rather than the edge case, and the search was effectively dead code.
         *
         * A search over the moves that *are* known is still evidence about the position. It is
         * discounted, not deleted: linear in the revealed fraction, with a floor so turn one still
         * gets a projection instead of a guess.
         */
        private fun confidence(revealedFraction: Double): Double {
            val fraction = revealedFraction.coerceIn(0.0, 1.0)
            if (!tuning.lookaheadLinearCoverage) return fraction * fraction
            val floor = tuning.lookaheadCoverageFloor
            return floor + (1.0 - floor) * fraction
        }

        private fun turnValue(
            state: BattleStateView,
            ownAction: BattleActionCandidate,
            opponentAction: BattleActionCandidate,
            depth: Int,
            history: RecursiveActionHistory,
            rootTurn: Boolean,
            turnStartValue: Double,
        ): TurnValue? {
            val projectedHistory = LocalOpponentMoveHypotheses.assumeAction(
                state, context.publicActionCatalog, history, opponentAction)
            val projectionContext = LocalBranchMoveInputs.context(context, state, projectedHistory)
            val projections = PublicSingleTurnProjector.project(
                initialState = state,
                allyAction = ownAction,
                opponentAction = opponentAction,
                sourceContext = projectionContext,
                history = projectedHistory,
                maxChanceBranchesPerMove = chanceBranchesPerMove,
                calculationCache = actionCalculationCache,
                shouldContinue = ::projectedWorkAvailable,
            )
            if (projections.isEmpty()) return null
            val trackedOwnPokemonIds = trackedOwnPokemonIds(state, ownAction)
            val turnStartMaterial = if (tuning.positionalTurnDeltas) LocalBoardMaterial.evaluate(state) else 0.0
            val orderExpectations = projections.groupBy(PublicTurnProjection::order).values.mapNotNull { outcomes ->
                val totalProbability = outcomes.sumOf(PublicTurnProjection::probability)
                if (totalProbability <= 0.0) return@mapNotNull null
                val orderWeight = outcomes.first().orderProbability
                var executionProbability = 0.0
                var remainingHpFraction = 0.0
                var threatDelta = 0.0
                var opponentKnockouts = 0.0
                val turnStartThreat = if (rootTurn) LocalOpponentThreat.materialAdjustment(state, opponentThreatWeights) else 0.0
                val value = outcomes.sumOf { rawOutcome ->
                    val outcome = rawOutcome.copy(
                        state = RecursiveSnapshotActionConstraints.clearFromProjectedState(rawOutcome.state),
                    )
                    if (budgetExhausted()) return null
                    executionProbability += outcome.probability * executedShare(state, outcome, BattleSide.ALLY, ownAction)
                    val trackedHp = trackedOwnPokemonIds.mapNotNull { id ->
                        outcome.state.pokemon.firstOrNull { it.battlePokemonId == id }?.hpFraction
                    }.averageOrNull() ?: 0.0
                    remainingHpFraction += trackedHp * outcome.probability
                    if (rootTurn) {
                        opponentKnockouts += knockedOutOpponents(state, outcome.state) * outcome.probability
                        threatDelta += (LocalOpponentThreat.materialAdjustment(outcome.state, opponentThreatWeights) -
                            turnStartThreat) * outcome.probability
                    }
                    val immediateTurnScore = LocalImmediateTurnScorer.score(
                        state,
                        outcome.state,
                        projectionContext,
                        actionCalculationCache,
                        tuning,
                        ::projectedWorkAvailable,
                    )
                    val immediateTurnDelta = immediateTurnScore.total + outcome.expectedScoreAdjustment
                    val immediateValue = turnStartValue + immediateTurnDelta
                    val stopBranch = !battleEnded(outcome.state) &&
                        LocalTurnBranchPruner.shouldStopBranch(
                            immediateTurnDelta = immediateTurnDelta,
                            depthRemaining = depth,
                            newlyLostAllyHpBefore = LocalTurnBranchPruner.newlyLostAllyHpBefore(
                                state,
                                outcome.state,
                            ),
                            thresholdOffset = tuning.branchPruneThresholdOffset,
                        )
                    val value = if (depth <= 1 || battleEnded(outcome.state) || stopBranch) {
                        if (stopBranch) branchesPruned++
                        if (depth > 1 && tuning.perTurnSearchValues && tuning.perTurnShortLines) {
                            // On the same scale as the lines that go on: their turns' changes are averaged, and a
                            // line that ends here, or is not followed, has no later change. Taken whole, a loss on
                            // this turn weighed 1.9 times a loss on the next and every other change, and at two
                            // turns a Boss stalled rather than risk one.
                            turnStartValue + immediateTurnDelta / (1.0 + FUTURE_DELTA_DISCOUNT)
                        } else immediateValue
                    } else {
                        val nextHistory = RecursiveHistoryProjector.project(
                            previous = projectedHistory,
                            stateBefore = state,
                            outcome = outcome,
                            allyAction = ownAction,
                            opponentAction = opponentAction,
                            originalPoolPokemonIds = context.publicActionCatalog.originalEntries
                                .mapTo(hashSetOf()) { it.battlePokemonId },
                            publicActionCatalog = context.publicActionCatalog,
                        )
                        val continuation = searchState(
                            outcome.state,
                            depth - 1,
                            nextHistory,
                        )
                        val continuationValue = continuation.value
                        // What this turn did to the position the leaf reads, beyond material: the matchup a switch
                        // or a replacement brings, a boost's standing pressure. The turn scorer prices material,
                        // stages, status and the field; the leaf's positional terms entered only as the next turn's
                        // change, measured from and to leaf boards, so they cancelled and never reached the root.
                        val positionalDelta = if (!tuning.positionalTurnDeltas || !tuning.replacementAwareTurnStart) 0.0 else {
                            (continuation.start - continuation.startMaterial) - (turnStartValue - turnStartMaterial)
                        }
                        // Per turn, not summed: the later turns' own change, measured from the board they start
                        // on, averaged with this turn's under the future discount. A two-turn line and a one-turn
                        // line are then on the same scale, and re-reading the board after this turn is not
                        // counted as something the next turn did.
                        //
                        // The board the next turn starts on is the one after a forced replacement. Measured before
                        // it, a knockout's next turn began on a board with an empty slot, and the replacement's
                        // arrival was charged to that turn as a loss: at two turns a Boss preferred Nasty Plot to
                        // the Power Gem that knocks out the Ho-Oh in front of it.
                        val nextStart = if (tuning.replacementAwareTurnStart) continuation.start else stateUtility(
                            LocalBranchMoveInputs.state(outcome.state, context.publicActionCatalog, nextHistory),
                            nextHistory,
                        )
                        if (tuning.perTurnSearchValues) {
                            turnStartValue + (immediateTurnDelta + positionalDelta + FUTURE_DELTA_DISCOUNT * (continuationValue - nextStart)) /
                                (1.0 + FUTURE_DELTA_DISCOUNT)
                        } else {
                            immediateValue + FUTURE_DELTA_DISCOUNT * (continuationValue - immediateValue)
                        }
                    }
                    val uncertaintyReserve = if (opponentAction.isUnknownPublicResponse()) {
                        UNKNOWN_RESPONSE_RESERVE
                    } else {
                        0.0
                    }
                    val switchTempo = if (rootTurn) {
                        LocalRecursiveSwitchTempo.adjustment(
                            allySwitch = false,
                            opponentSwitch = opponentAction.containsActionKind(BattleActionKind.SWITCH),
                            allyRepeated = false,
                            opponentRepeated = history.opponentSwitchedLastTurn,
                        )
                    } else {
                        LocalRecursiveSwitchTempo.adjustment(
                            allySwitch = ownAction.containsActionKind(BattleActionKind.SWITCH),
                            opponentSwitch = opponentAction.containsActionKind(BattleActionKind.SWITCH),
                            allyRepeated = history.allySwitchedLastTurn,
                            opponentRepeated = history.opponentSwitchedLastTurn,
                        )
                    }
                    (value + switchTempo - uncertaintyReserve) * outcome.probability
                } / totalProbability
                orderWeight to TurnValue(
                    value,
                    (executionProbability / totalProbability).coerceIn(0.0, 1.0),
                    (remainingHpFraction / totalProbability).coerceIn(0.0, 1.0),
                    threatDelta / totalProbability,
                    opponentKnockouts / totalProbability,
                )
            }
            // Turn order is chance, not choice, and it used to be collapsed by a flat minimum - full
            // pessimism at every tier, deeper than the worst case an opponent's actual decision is
            // given. The opponent picks their move; they do not pick their IVs, and an order the public
            // stat ranges leave open is exactly that kind of unknown.
            //
            // The cost of the old reading was invisible while every fixture handed the search a point
            // range for the opponent, which made the order known and the minimum harmless. Against the
            // species range production really supplies - about 1.8x wide on Speed - the ranges overlap
            // almost always, so the search assumed it moved second on every node of every branch. Any
            // patient line whose payoff needs surviving one turn then priced as "I move second and die",
            // and the AI could only ever be greedy.
            //
            // So the orders are averaged, then pulled toward the worst by the same tier weight the
            // opponent's own choices get. A Boss is still deeply cautious; it is no longer more
            // frightened of an unlucky stat spread than of a hostile decision.
            if (orderExpectations.isEmpty()) return null
            val worstOrder = orderExpectations.minBy { it.second.value }
            if (orderExpectations.size == 1) return worstOrder.second
            val pessimism = (worstCaseWeight() * tuning.turnOrderPessimismScale).coerceIn(0.0, 1.0)
            // Weighted by how likely each order is, not spread evenly across the ones left open. An
            // even spread made every degree of Speed uncertainty identical, so a drop that nearly
            // reversed the order scored the same as one that barely moved it.
            val weightTotal = orderExpectations.sumOf { it.first }
            val meanValue = if (weightTotal > 0.0) {
                orderExpectations.sumOf { it.first * it.second.value } / weightTotal
            } else {
                orderExpectations.sumOf { it.second.value } / orderExpectations.size
            }
            val meanExecution = if (weightTotal > 0.0) {
                orderExpectations.sumOf { it.first * it.second.ownExecutionProbability } / weightTotal
            } else {
                orderExpectations.sumOf { it.second.ownExecutionProbability } / orderExpectations.size
            }
            return TurnValue(
                value = meanValue * (1.0 - pessimism) + worstOrder.second.value * pessimism,
                ownExecutionProbability = meanExecution * (1.0 - pessimism) +
                    worstOrder.second.ownExecutionProbability * pessimism,
                ownRemainingHpFraction = orderExpectations.minOf { it.second.ownRemainingHpFraction },
                opponentKnockouts = if (weightTotal > 0.0) {
                    orderExpectations.sumOf { it.first * it.second.opponentKnockouts } / weightTotal * (1.0 - pessimism) +
                        worstOrder.second.opponentKnockouts * pessimism
                } else worstOrder.second.opponentKnockouts,
                threatDelta = run {
                    val meanThreat = if (weightTotal > 0.0) {
                        orderExpectations.sumOf { it.first * it.second.threatDelta } / weightTotal
                    } else {
                        orderExpectations.sumOf { it.second.threatDelta } / orderExpectations.size
                    }
                    meanThreat * (1.0 - pessimism) + worstOrder.second.threatDelta * pessimism
                },
            )
        }

        /**
         * How far a tier leans on the worst branch rather than the average one.
         *
         * Shared by the opponent's action choices and by the turn orders their stat ranges leave open,
         * so the search cannot end up more afraid of an unknown than of an adversary.
         */
        private fun worstCaseWeight(): Double =
            LocalSearchResponseObjective.worstCaseWeight(profile.difficulty.tier)

        private fun aggregateOpponentResponses(
            values: List<OpponentTurnValue>,
            state: BattleStateView,
            ownAction: BattleActionCandidate,
        ): TurnValue? {
            if (values.isEmpty()) return null
            return LocalSearchResponseObjective.aggregate(
                values, context.memory, profile, LocalBattleMind.situations(state, ownAction),
            )
        }

        private fun calibrateExpectedResponses(
            values: List<OpponentTurnValue>,
        ): List<OpponentTurnValue> {
            val baseline = LocalExpectedMoveResponseConfidence.noResponseBaseline(
                values,
                UNKNOWN_RESPONSE_RESERVE,
            ) ?: return values
            return LocalExpectedMoveResponseConfidence.adjust(
                values,
                noResponseBaseline = baseline,
                confidence = tuning.expectedMoveResponseConfidence,
                bestTieTolerance = tuning.expectedMoveBestTieTolerance,
            )
        }

        private fun stateUtility(state: BattleStateView, history: RecursiveActionHistory): Double {
            val key = LocalBranchMoveInputs.key(fingerprint(state), history)
            stateUtilityMemo[key]?.let { return it }
            val value = LocalLookaheadStateEvaluator.evaluate(
                state = state,
                source = LocalBranchMoveInputs.context(context, state, history, spendPp = true),
                calculationCache = actionCalculationCache,
                shouldContinue = ::leafWorkAvailable,
                tuning = tuning,
            )
            // A leaf whose move list was cut short by the budget is a partial reading, not a value:
            // `attackPressure` takes a prefix of each side's moves and then maxes, and the ally's list
            // is walked before the opponent's, so a truncated evaluation reads as free advantage.
            // Storing it would spread that one reading over every later use of the position.
            if (!truncated) stateUtilityMemo[key] = value
            return value
        }
        /**
         * The share of the submitted actions that went through: 0 or 1 for a single action, and for a doubles
         * joint action the share of its non-pass slots. Counting a joint action only when every slot
         * executed made one partner that might be knocked out first veto the other's attack: a real VGC
         * final turn dropped every line with Ursaluna attacking and kept only Protect + Protect.
         */
        private fun executedShare(
            stateBefore: BattleStateView,
            outcome: PublicTurnProjection,
            side: BattleSide,
            submitted: BattleActionCandidate,
        ): Double {
            val acting = primitiveActions(submitted).filter { it.kind != BattleActionKind.WAIT }
            if (acting.isEmpty()) return 1.0
            return acting.count { actionExecuted(stateBefore, outcome, side, it) }.toDouble() / acting.size
        }

        private fun actionExecuted(
            stateBefore: BattleStateView,
            outcome: PublicTurnProjection,
            side: BattleSide,
            submitted: BattleActionCandidate,
        ): Boolean = primitiveActions(submitted).all { action ->
            when (action.kind) {
                BattleActionKind.USE_MOVE -> stateBefore.pokemon.firstOrNull {
                    it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
                }?.battlePokemonId in outcome.executedMoveIdsByPokemon
                BattleActionKind.SWITCH -> outcome.state.pokemon.any {
                    it.battlePokemonId == action.switchPokemonId && it.side == side &&
                        it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
                }
                BattleActionKind.WAIT -> true
                BattleActionKind.FORFEIT -> false
                BattleActionKind.COMPOSITE -> false
            }
        }

        private fun completeOpponentActions(
            state: BattleStateView,
            history: RecursiveActionHistory,
            limitPerSlot: Int = profile.difficulty.doubleCandidateLimitPerSlot,
        ): List<BattleActionCandidate>? {
            val currentCatalog = context.publicActionCatalog.afterSwitch(history.restoredOriginalPokemonIds)
            val activeOpponents = state.pokemon.filter {
                it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }
            val incompleteIds = activeOpponents.filterNot {
                currentCatalog.isMoveSetComplete(it.battlePokemonId)
            }.mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId)
            val actions = PublicFutureActionFactory.actions(
                state,
                BattleSide.OPPONENT,
                context.publicActionCatalog,
                history,
                limitPerSlot,
                incompleteIds,
                includeMoveHypotheses = tuning.lookaheadMoveHypotheses,
                hypotheticalMoveLimitPerSlot = tuning.hypotheticalMoveLimitPerSlot,
                hypotheticalPriorityReservation = tuning.hypotheticalPriorityReservation,
                moveUsage = moveUsage,
            )
            if (incompleteIds.isNotEmpty()) {
                publicResponseIncomplete = true
                val revealedCoverage = incompleteIds.fold(1.0) { coverage, pokemonId ->
                    val inference = currentCatalog.inferredMovesForPokemon(pokemonId)
                    val concreteCoverage = inference?.slots?.sumOf { slot ->
                        when (slot.knowledge) {
                            BattleOpponentMoveKnowledge.CONFIRMED -> 1.0
                            BattleOpponentMoveKnowledge.EXPECTED -> tuning.expectedMoveResponseConfidence
                            BattleOpponentMoveKnowledge.GUESS -> 0.0
                        }
                    } ?: currentCatalog.forPokemon(pokemonId).size.toDouble()
                    val knownFraction = (concreteCoverage / STANDARD_MOVE_SLOTS).coerceIn(0.0, 1.0)
                    coverage * confidence(knownFraction)
                }
                publicResponseCoverage = minOf(publicResponseCoverage, revealedCoverage)
            }
            return actions
        }

        private fun budgetExhausted(): Boolean {
            return !consumeWorkUnit()
        }

        private fun fingerprint(state: BattleStateView): String =
            actionCalculationCache.fingerprints.of(state)

        private fun projectedWorkAvailable(): Boolean {
            return consumeWorkUnit()
        }

        private fun consumeWorkUnit(): Boolean {
            if (truncated) return false
            nodesVisited++
            when {
                nodesVisited > nodeLimit -> stop(LocalLookaheadTerminationReason.NODE_BUDGET)
                clockMillis() >= deadlineMillis - DEADLINE_MARGIN_MILLIS -> {
                    stop(LocalLookaheadTerminationReason.TIME_BUDGET)
                }
            }
            return !truncated
        }

        private fun stop(reason: LocalLookaheadTerminationReason) {
            if (!truncated) {
                truncated = true
                terminationReason = reason
            }
        }

        /**
         * The same budget check, attributed to leaf evaluation rather than to the tree.
         *
         * `nodesVisited` is one counter spent by two very different things: projecting a turn, and
         * scoring a leaf by recalculating every damaging move on both sides. They share a limit named
         * for the first, so the reported node count is not a tree size and the allowance a tier thinks
         * it is granting to depth is partly consumed by evaluation. Splitting the count says how much,
         * which has to be known before the budgets are worth separating.
         */
        private fun leafWorkAvailable(): Boolean {
            leafWorkUnits++
            return projectedWorkAvailable()
        }
    }

    private data class SearchKey(val depth: Int, val state: String, val history: RecursiveActionHistory)
    private data class RootActionEvaluation(
        val value: Double,
        val ownExecutionProbability: Double,
        val worstResponseRemainingHp: Double,
        /** Null when every evaluated reply was an expected move slot. */
        val worstConfirmedResponseRemainingHp: Double?,
        val threatDelta: Double = 0.0,
        /** Opposing Pokemon expected to be knocked out on the root turn. */
        val opponentKnockouts: Double = 0.0,
        /** Each reply's value, keyed by the reply's action id. */
        val responses: Map<String, Double> = emptyMap(),
    ) {
        /** [weight] of the value moved to the expected value against the opponent's [mix]. */
        fun againstMix(mix: Map<String, Double>, weight: Double): RootActionEvaluation {
            var mass = 0.0
            var expected = 0.0
            for ((id, share) in mix) {
                val response = responses[id] ?: continue
                mass += share
                expected += share * response
            }
            if (mass < MIX_COVERAGE) return this
            return copy(value = value * (1.0 - weight) + expected / mass * weight)
        }
    }

    /** The opponent's equilibrium mix over its replies to the root, from the searched candidates' reply values. */
    private fun rootOpponentMix(searches: List<RootActionEvaluation>): Map<String, Double>? {
        if (searches.size < 2) return null
        val columns = searches.first().responses.keys.toList()
        if (columns.isEmpty() || searches.any { it.responses.keys != columns.toSet() }) return null
        val table = Array(searches.size) { row -> DoubleArray(columns.size) { searches[row].responses.getValue(columns[it]) } }
        val solution = LocalMatrixGame.solve(table)
        return columns.indices.associate { columns[it] to solution.columnStrategy[it] }
    }

    /** Below this share of the mix found among an action's replies, the mix says too little about it. */
    private const val MIX_COVERAGE = 0.5

    private fun retention(remainingHp: Double, baselineHp: Double): Double =
        if (baselineHp <= 0.0) 0.0 else (remainingHp / baselineHp).coerceIn(0.0, 1.0)

    private fun BattleActionCandidate.containsTag(tag: String): Boolean =
        tag in tags || componentActions.any { it.containsTag(tag) }

    /**
     * Which root candidates this ply may spend the budget on, or null to allow all of them.
     *
     * Narrowed per slot rather than per joint action. Ranking whole joints and keeping the top few
     * looks equivalent and is not: the best joint's first-slot move usually recurs through the rest
     * of the list, so the survivors differ only in their second half and the search re-decides one
     * side of the turn eight times over. Each slot's actions are scored here by the best joint they
     * appear in, the top few per slot survive, and a joint stays only if every one of its parts did.
     * A best joint for each supported cooperation pattern is also retained for turn evaluation.
     *
     * Singles has one slot and few candidates, so nothing is trimmed there.
     */
    private fun searchableActionIds(
        ranked: List<LocalBattleActionRank>,
        tuning: LocalDecisionTuning,
        context: BattleDecisionContext,
    ): Set<String>? {
        if (ranked.size <= tuning.maximumRootCandidates) return null
        val bestBySlotAction = linkedMapOf<Pair<Int, String>, Double>()
        ranked.forEach { rank ->
            primitiveActions(rank.outcome.candidate).forEach { component ->
                val slot = component.actorSlot ?: return@forEach
                val key = slot to component.actionId
                val best = bestBySlotAction[key]
                if (best == null || rank.comparisonValue > best) {
                    bestBySlotAction[key] = rank.comparisonValue
                }
            }
        }
        if (bestBySlotAction.isEmpty()) return null
        val keptBySlot = bestBySlotAction.entries
            .groupBy { it.key.first }
            .mapValues { (_, entries) ->
                entries.sortedByDescending { it.value }
                    .take(tuning.maximumRootActionsPerSlot)
                    .mapTo(linkedSetOf()) { it.key.second }
            }
        val kept = ranked.asSequence()
            .filter { rank ->
                primitiveActions(rank.outcome.candidate).all { component ->
                    val slot = component.actorSlot ?: return@all true
                    component.actionId in keptBySlot.getValue(slot)
                }
            }
            .mapTo(linkedSetOf()) { it.outcome.candidate.actionId }
        kept += LocalCooperativeRootRetention.select(ranked, context)
        return kept.takeIf { it.isNotEmpty() }
    }

    private fun primitiveActions(action: BattleActionCandidate): List<BattleActionCandidate> =
        if (action.kind == BattleActionKind.COMPOSITE) action.componentActions else listOf(action)

    private fun BattleActionCandidate.containsActionKind(kind: BattleActionKind): Boolean =
        this.kind == kind || componentActions.any { it.containsActionKind(kind) }

    private fun trackedOwnPokemonIds(
        state: BattleStateView,
        action: BattleActionCandidate,
    ): List<UUID> = primitiveActions(action).mapNotNull { primitive ->
        if (primitive.kind == BattleActionKind.SWITCH) {
            primitive.switchPokemonId
        } else {
            primitive.actorSlot?.let { slot ->
                state.pokemon.firstOrNull {
                    it.side == BattleSide.ALLY && it.activeSlot == slot && !it.fainted && it.hpFraction > 0.0
                }?.battlePokemonId
            }
        }
    }.distinct()

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun battleEnded(state: BattleStateView): Boolean = BattleSide.entries.any { side ->
        state.remainingPokemonBySide.getValue(side) <= 0
    }

    private fun elapsedMillis(startMillis: Long, endMillis: Long): Long =
        if (endMillis >= startMillis) endMillis - startMillis else 0L

    private fun remainingMillis(deadlineMillis: Long, currentMillis: Long): Long =
        if (deadlineMillis > currentMillis) deadlineMillis - currentMillis else 0L

    private const val BOARD_TO_SCORE = 100.0
    private const val TERMINAL_SCORE_THRESHOLD = 10_000.0
    private const val MAX_ADJUSTMENT = 800.0

    private class RawAdjustment(val value: Double, val terminal: Boolean)

    /**
     * Bounds the search's say by [limit] without erasing the differences between candidates. Bounding each
     * candidate on its own flattened a loss every choice shares: a critical hit that knocks out the last
     * Pokemon one time in 24 costs every candidate over the bound, and they all came back equal, handing the
     * choice to the heuristic. The bound applies to the best candidate's adjustment, the rest keep their gap
     * to it up to twice the bound. A terminal verdict is taken whole, as before.
     */
    private fun boundSharedAdjustments(
        evaluated: MutableList<LocalBattleActionRank>,
        produced: java.util.IdentityHashMap<LocalBattleActionRank, RawAdjustment>,
        limit: Double,
    ) {
        val raws = evaluated.mapNotNull { produced[it] }
        if (raws.isEmpty() || raws.any { it.terminal }) return
        val reference = raws.maxOf { it.value }
        val boundedReference = reference.coerceIn(-limit, limit)
        for (index in evaluated.indices) {
            val rank = evaluated[index]
            val raw = produced[rank]?.value ?: continue
            val shift = boundedReference + (raw - reference).coerceAtLeast(-2.0 * limit) - raw
            if (shift != 0.0) {
                evaluated[index] = rank.copy(
                    comparisonValue = rank.comparisonValue + shift,
                    lookaheadUtility = rank.lookaheadUtility + shift,
                )
            }
        }
    }
    private const val DEADLINE_MARGIN_MILLIS = 20L
    private const val FUTURE_DELTA_DISCOUNT = 0.90
    private const val UNKNOWN_RESPONSE_RESERVE = 0.20
    private const val UNKNOWN_PUBLIC_RESPONSE_TAG = "unknown_public_response"
    private const val EXPECTED_OPPONENT_MOVE_TAG = "expected_opponent_move"
    private const val STANDARD_MOVE_SLOTS = 4.0

    /** Opposing Pokemon standing in [before] and fainted in [after]. */
    private fun knockedOutOpponents(before: BattleStateView, after: BattleStateView): Int {
        var count = 0
        for (pokemon in before.pokemon) {
            if (pokemon.side != BattleSide.OPPONENT || pokemon.fainted || pokemon.hpFraction <= 0.0) continue
            val now = after.pokemon.firstOrNull { it.battlePokemonId == pokemon.battlePokemonId } ?: continue
            if (now.fainted || now.hpFraction <= 0.0) count++
        }
        return count
    }

    private fun BattleActionCandidate.isUnknownPublicResponse(): Boolean =
        UNKNOWN_PUBLIC_RESPONSE_TAG in tags || componentActions.any { it.isUnknownPublicResponse() }
}
