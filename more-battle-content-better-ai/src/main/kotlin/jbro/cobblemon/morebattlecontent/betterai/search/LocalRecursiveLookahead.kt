package jbro.cobblemon.morebattlecontent.betterai.search

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.LocalForcedReplacementResolution
import jbro.cobblemon.morebattlecontent.betterai.calculation.LocalForcedReplacementResolver
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleMind
import jbro.cobblemon.morebattlecontent.betterai.state.LocalRecursiveMoveHabit
import jbro.cobblemon.morebattlecontent.betterai.state.LocalRecursiveSwitchTempo
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveHistoryProjector
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveSnapshotActionConstraints

internal data class LocalLookaheadEvaluation(
    val ranked: List<LocalBattleActionRank>,
    val nodesVisited: Int,
    val branchesPruned: Int,
    val depthCompleted: Int,
    val truncated: Boolean,
    val publicResponseIncomplete: Boolean,
    /** Effective weight the search result carried into the ranking, for diagnostics. */
    val publicResponseCoverage: Double = 1.0,
    /** Public tactical calculations the leaf evaluations actually performed. */
    val leafCalculations: Int = 0,
    /** What the same search would have cost with the caches keyed by object identity. */
    val leafCalculationsUnderIdentityKeying: Int = 0,
    /** The share of [nodesVisited] spent scoring leaves rather than projecting turns. */
    val leafWorkUnits: Int = 0,
)

/**
 * Full-turn, public-information minimax for single and double battles.
 *
 * One depth consumes every action submitted by both trainers for that turn. The local side
 * maximizes the resulting public board value and the opponent minimizes it. Hidden opponent moves
 * are represented only as unresolved public-response reserve branches.
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
    ): LocalLookaheadEvaluation {
        val requestedDepth = profile.difficulty.lookaheadPlies.coerceAtLeast(1)
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
            return LocalLookaheadEvaluation(ranked, 0, 0, 0, false, true, 0.0)
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
            return LocalLookaheadEvaluation(ranked, 0, 0, 0, true, false, 0.0)
        }
        var accepted = ranked
        var completedDepth = 0
        var totalNodes = 0
        var totalBranchesPruned = 0
        var totalLeafWorkUnits = 0
        var truncated = false
        var publicResponseIncomplete = false
        var lastCoverage = 1.0
        // Board gain each candidate showed at a single ply, keyed by action.
        //
        // A one-ply search already resolves the whole turn including the opponent's reply, so this is
        // the search's own account of the turn being played - the same event the immediate heuristic
        // scores. Everything past it is foresight, and only foresight is scaled by the difficulty
        // tier. Without the split, a tier weight would also dial down how well a trainer reads the
        // turn in front of it, which is not what a difficulty setting should mean.
        val singlePlyGain = mutableMapOf<String, Double>()
        for (depth in 1..requestedDepth) {
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
            )
            val evaluated = ranked.map { rank ->
                val evaluation = search.rootActionValue(context.state, rank.outcome.candidate, depth)
                if (evaluation == null) rank else {
                    // The recursive turn score carries its own knockout value, weighted by the actual
                    // damage-roll KO ratio and execution probability. Remove exactly the knockout
                    // value the root scorer already added, rather than a constant that only matched
                    // the secure case: the legacy correction subtracted a flat 250 per secure KO while
                    // the scorer had also added up to 50 from the situational evaluator, and it
                    // subtracted nothing at all for a merely probable knockout that had still been
                    // priced in.
                    val searchBoardGain = (evaluation.value - baseline) * BOARD_TO_SCORE
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
                        rank.outcome.knockoutUtility.coerceAtMost(searchBoardGain.coerceAtLeast(0.0))
                    }
                    val actionId = rank.outcome.candidate.actionId
                    if (depth == 1) singlePlyGain[actionId] = searchBoardGain
                    // Split the search result at the turn boundary and scale only the far side.
                    //
                    // Depth was measured to be a weak difficulty lever precisely because it was not
                    // split: a deeper search disagreed with the immediate heuristic in about 60% of
                    // positions, but its verdict entered the ranking as one lump dominated by the turn
                    // the heuristic had already scored, so the ranking barely moved and every tier
                    // played alike. The near half stays whole for every trainer; the far half is what
                    // a difficulty tier actually buys.
                    val immediateGain = singlePlyGain[actionId] ?: searchBoardGain
                    val foresightGain = (searchBoardGain - immediateGain) * profile.difficulty.foresightWeight
                    // The knockout correction exists only to stop the search's knockout value landing
                    // on top of the root scorer's. As the search takes over the value half, the root
                    // value it would be correcting for goes away, so the correction retires with it.
                    val authority = tuning.searchAuthority
                    val rawAdjustment =
                        immediateGain + foresightGain - rootSecureKoBaselineCorrection * (1.0 - authority)
                    val adjustment = (rawAdjustment * search.publicResponseCoverage)
                        .coerceIn(-tuning.maximumLookaheadAdjustment, tuning.maximumLookaheadAdjustment)
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
                    rank.copy(
                        comparisonValue = rank.comparisonValue - withdrawnHeuristicValue + adjustment,
                        // Everything the search changed relative to the pure heuristic ranking, the
                        // withdrawal included. Reporting only the added term made
                        // `comparisonValue - lookaheadUtility` stop meaning "the heuristic's answer"
                        // the moment any value was withdrawn, which silently turned the influence
                        // measurements into a comparison against the leftover penalty terms.
                        lookaheadUtility = adjustment - withdrawnHeuristicValue,
                        executionProbability = evaluation.ownExecutionProbability,
                        worstResponseHpRetention = if (responseHpBaseline <= 0.0) {
                            0.0
                        } else {
                            (evaluation.worstResponseRemainingHp / responseHpBaseline).coerceIn(0.0, 1.0)
                        },
                    )
                }
            }
            totalNodes += search.nodesVisited
            totalBranchesPruned += search.branchesPruned
            totalLeafWorkUnits += search.leafWorkUnits
            publicResponseIncomplete = publicResponseIncomplete || search.publicResponseIncomplete
            lastCoverage = search.publicResponseCoverage
            if (search.truncated) {
                truncated = true
                break
            }
            accepted = LocalBattleActionPolicy.sort(evaluated)
            completedDepth = depth
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
    ) {
        var nodesVisited: Int = 0
            private set
        var branchesPruned: Int = 0
            private set
        var truncated: Boolean = false
            private set
        var publicResponseIncomplete: Boolean = false
            private set
        var publicResponseCoverage: Double = 1.0
            private set
        var leafWorkUnits: Int = 0
            private set
        private val memo = HashMap<SearchKey, Double>()
        // Structural, like the search's own value memo. Keying leaf values by object identity meant a
        // position reached by two different routes was evaluated twice, and a leaf evaluation is a full
        // tactical calculation for every damaging move on both sides.
        private val stateUtilityMemo = HashMap<String, Double>().apply {
            put(actionCalculationCache.fingerprints.of(initialState), initialStateUtility)
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
            val turnStartValue = stateUtility(state)
            val responseValues = mutableListOf<OpponentTurnValue>()
            for (opponentAction in opponentActions) {
                if (budgetExhausted()) break
                turnValue(
                    state,
                    ownAction,
                    opponentAction,
                    depth,
                    initialHistory,
                    rootTurn = true,
                    turnStartValue = turnStartValue,
                )?.let { value -> responseValues += OpponentTurnValue(opponentAction, value) }
            }
            return aggregateOpponentResponses(responseValues, state, ownAction)?.let { aggregate ->
                // A risky action may still remain the best-ranked fallback, but it must not enter the
                // exploratory pool merely because some other public response lets it execute.
                val executionProbability = responseValues.minOfOrNull { it.value.ownExecutionProbability }
                    ?: aggregate.ownExecutionProbability
                val worstResponseRemainingHp = responseValues.minOfOrNull { it.value.ownRemainingHpFraction }
                    ?: aggregate.ownRemainingHpFraction
                RootActionEvaluation(aggregate.value, executionProbability, worstResponseRemainingHp)
            }
        }

        private fun searchState(state: BattleStateView, depth: Int, history: RecursiveActionHistory): Double {
            if (depth <= 0 || battleEnded(state) || budgetExhausted()) return stateUtility(state)
            forcedReplacementValue(state, depth, history)?.let { return it }
            val key = SearchKey(depth, fingerprint(state), history)
            memo[key]?.let { return it }
            val ownActions = PublicFutureActionFactory.actions(
                state,
                BattleSide.ALLY,
                context.publicActionCatalog,
                history,
                profile.difficulty.doubleCandidateLimitPerSlot,
            )
            val opponentActions = completeOpponentActions(state, history) ?: return stateUtility(state)
            if (ownActions.isEmpty() || opponentActions.isEmpty()) {
                if (opponentActions.isEmpty() && !battleEnded(state)) publicResponseIncomplete = true
                return stateUtility(state)
            }
            val turnStartValue = stateUtility(state)
            var best = Double.NEGATIVE_INFINITY
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
                aggregateOpponentResponses(responseValues, state, ownAction)?.let { responseValue ->
                    best = maxOf(best, responseValue.value)
                }
            }
            val result = if (best.isFinite()) best else stateUtility(state)
            if (!truncated) memo[key] = result
            return result
        }

        private fun forcedReplacementValue(
            state: BattleStateView,
            depth: Int,
            history: RecursiveActionHistory,
        ): Double? {
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
                LocalForcedReplacementResolver.resolve(state, BattleSide.ALLY, context)
            } else {
                LocalForcedReplacementResolution(listOf(state), 1.0)
            }
            recordReplacementCoverage(allyResolution)
            val allyOptions = allyResolution.states
            if (allyOptions.isEmpty()) {
                return stateUtility(state)
            }
            val allyValues = allyOptions.map { allyState ->
                val opponentResolution = if (opponentMissing) {
                    LocalForcedReplacementResolver.resolve(allyState, BattleSide.OPPONENT, context)
                } else {
                    LocalForcedReplacementResolution(listOf(allyState), 1.0)
                }
                recordReplacementCoverage(opponentResolution)
                val opponentOptions = opponentResolution.states
                if (opponentOptions.isEmpty()) {
                    stateUtility(allyState)
                } else {
                    opponentOptions.minOf { replacementState ->
                        searchState(replacementState, depth, history)
                    }
                }
            }
            return if (allyMissing) allyValues.maxOrNull() else allyValues.single()
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
            val projections = PublicSingleTurnProjector.project(
                initialState = state,
                allyAction = ownAction,
                opponentAction = opponentAction,
                sourceContext = context,
                history = history,
                maxChanceBranchesPerMove = chanceBranchesPerMove,
                calculationCache = actionCalculationCache,
                shouldContinue = ::projectedWorkAvailable,
            )
            if (projections.isEmpty()) return null
            val trackedOwnPokemonIds = trackedOwnPokemonIds(state, ownAction)
            val orderExpectations = projections.groupBy(PublicTurnProjection::order).values.mapNotNull { outcomes ->
                val totalProbability = outcomes.sumOf(PublicTurnProjection::probability)
                if (totalProbability <= 0.0) return@mapNotNull null
                val orderWeight = outcomes.first().orderProbability
                var executionProbability = 0.0
                var remainingHpFraction = 0.0
                val value = outcomes.sumOf { rawOutcome ->
                    val outcome = rawOutcome.copy(
                        state = RecursiveSnapshotActionConstraints.clearFromProjectedState(rawOutcome.state),
                    )
                    if (budgetExhausted()) return null
                    val ownActionExecuted = actionExecuted(state, outcome, BattleSide.ALLY, ownAction)
                    if (ownActionExecuted) {
                        executionProbability += outcome.probability
                    }
                    val trackedHp = trackedOwnPokemonIds.mapNotNull { id ->
                        outcome.state.pokemon.firstOrNull { it.battlePokemonId == id }?.hpFraction
                    }.averageOrNull() ?: 0.0
                    remainingHpFraction += trackedHp * outcome.probability
                    val immediateTurnScore = LocalImmediateTurnScorer.score(state, outcome.state)
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
                        immediateValue
                    } else {
                        val nextHistory = RecursiveHistoryProjector.project(
                            previous = history,
                            stateBefore = state,
                            outcome = outcome,
                            allyAction = ownAction,
                            opponentAction = opponentAction,
                        )
                        val continuationValue = searchState(
                            outcome.state,
                            depth - 1,
                            nextHistory,
                        )
                        immediateValue + FUTURE_DELTA_DISCOUNT * (continuationValue - immediateValue)
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
                    val moveHabitTempo = LocalRecursiveMoveHabit.cost(
                        state,
                        BattleSide.OPPONENT,
                        opponentAction,
                        history,
                    ) - LocalRecursiveMoveHabit.cost(
                        state,
                        BattleSide.ALLY,
                        ownAction,
                        history,
                    )
                    (value + switchTempo + moveHabitTempo - uncertaintyReserve) * outcome.probability
                } / totalProbability
                orderWeight to TurnValue(
                    value,
                    (executionProbability / totalProbability).coerceIn(0.0, 1.0),
                    (remainingHpFraction / totalProbability).coerceIn(0.0, 1.0),
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
            )
        }

        /**
         * How far a tier leans on the worst branch rather than the average one.
         *
         * Shared by the opponent's action choices and by the turn orders their stat ranges leave open,
         * so the search cannot end up more afraid of an unknown than of an adversary.
         */
        private fun worstCaseWeight(): Double = when (profile.difficulty.tier) {
            BattleTrainerTier.INTRODUCTORY -> 0.20
            BattleTrainerTier.STANDARD -> 0.40
            BattleTrainerTier.ADVANCED -> 0.65
            BattleTrainerTier.BOSS -> 0.85
        }

        private fun aggregateOpponentResponses(
            values: List<OpponentTurnValue>,
            state: BattleStateView,
            ownAction: BattleActionCandidate,
        ): TurnValue? {
            if (values.isEmpty()) return null
            val robust = robustAggregate(values.map(OpponentTurnValue::value))
            val learned = LocalOpponentResponseModel.distribution(
                actions = values.map(OpponentTurnValue::action),
                memory = context.memory,
                information = profile.personality.information,
                situations = LocalBattleMind.situations(state, ownAction),
            ) ?: return robust
            val categories = values.groupBy { LocalOpponentResponseModel.responseKind(it.action) }
                .filterKeys { it == BattlePredictedResponse.MOVE || it == BattlePredictedResponse.SWITCH }
            val weightedCategories = categories.mapNotNull { (_, responses) ->
                val mass = responses.sumOf { learned.weights[it.action] ?: 0.0 }
                if (mass <= 0.0 || !mass.isFinite()) null else mass to robustAggregate(
                    responses.map(OpponentTurnValue::value),
                )
            }
            val learnedTotal = weightedCategories.sumOf { it.first }
            if (learnedTotal <= 0.0 || !learnedTotal.isFinite()) return robust
            val modeled = TurnValue(
                value = weightedCategories.sumOf { (mass, response) -> response.value * mass } / learnedTotal,
                ownExecutionProbability = weightedCategories.sumOf { (mass, response) ->
                    response.ownExecutionProbability * mass
                } / learnedTotal,
                ownRemainingHpFraction = weightedCategories.minOf { (_, response) ->
                    response.ownRemainingHpFraction
                },
            )
            return TurnValue(
                value = robust.value * (1.0 - learned.influence) + modeled.value * learned.influence,
                ownExecutionProbability = robust.ownExecutionProbability * (1.0 - learned.influence) +
                    modeled.ownExecutionProbability * learned.influence,
                ownRemainingHpFraction = minOf(robust.ownRemainingHpFraction, modeled.ownRemainingHpFraction),
            )
        }

        private fun robustAggregate(turns: List<TurnValue>): TurnValue {
            require(turns.isNotEmpty())
            val worst = turns.minBy(TurnValue::value)
            if (turns.size == 1) return worst
            val temperature = RESPONSE_SOFTMIN_TEMPERATURE
            val weights = turns.map { response -> kotlin.math.exp((worst.value - response.value) / temperature) }
            val weightTotal = weights.sum()
            val expected = if (weightTotal > 0.0 && weightTotal.isFinite()) {
                TurnValue(
                    value = turns.indices.sumOf { index -> turns[index].value * weights[index] } / weightTotal,
                    ownExecutionProbability = turns.indices.sumOf { index ->
                        turns[index].ownExecutionProbability * weights[index]
                    } / weightTotal,
                    ownRemainingHpFraction = turns.minOf(TurnValue::ownRemainingHpFraction),
                )
            } else {
                worst
            }
            val worstWeight = worstCaseWeight()
            return TurnValue(
                value = expected.value * (1.0 - worstWeight) + worst.value * worstWeight,
                ownExecutionProbability = expected.ownExecutionProbability * (1.0 - worstWeight) +
                    worst.ownExecutionProbability * worstWeight,
                ownRemainingHpFraction = minOf(expected.ownRemainingHpFraction, worst.ownRemainingHpFraction),
            )
        }

        private fun stateUtility(state: BattleStateView): Double {
            val key = fingerprint(state)
            stateUtilityMemo[key]?.let { return it }
            val value = LocalLookaheadStateEvaluator.evaluate(
                state = state,
                source = context,
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

        private data class TurnValue(
            val value: Double,
            val ownExecutionProbability: Double,
            val ownRemainingHpFraction: Double,
        )
        private data class OpponentTurnValue(val action: BattleActionCandidate, val value: TurnValue)

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
        ): List<BattleActionCandidate>? {
            val activeOpponents = state.pokemon.filter {
                it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
            }
            val incompleteIds = activeOpponents.filterNot {
                context.publicActionCatalog.isMoveSetComplete(it.battlePokemonId)
            }.mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId)
            val actions = PublicFutureActionFactory.actions(
                state,
                BattleSide.OPPONENT,
                context.publicActionCatalog,
                history,
                profile.difficulty.doubleCandidateLimitPerSlot,
                incompleteIds,
            )
            if (incompleteIds.isNotEmpty()) {
                publicResponseIncomplete = true
                val revealedCoverage = incompleteIds.fold(1.0) { coverage, pokemonId ->
                    val revealedMoveCount = context.publicActionCatalog.forPokemon(pokemonId).size
                    val revealedFraction = (revealedMoveCount.toDouble() / STANDARD_MOVE_SLOTS).coerceIn(0.0, 1.0)
                    coverage * confidence(revealedFraction)
                }
                publicResponseCoverage = minOf(publicResponseCoverage, revealedCoverage)
            }
            return actions
        }

        private fun budgetExhausted(): Boolean {
            if (truncated) return true
            nodesVisited++
            if (nodesVisited > nodeLimit || clockMillis() >= deadlineMillis - DEADLINE_MARGIN_MILLIS) {
                truncated = true
            }
            return truncated
        }

        private fun fingerprint(state: BattleStateView): String =
            actionCalculationCache.fingerprints.of(state)

        private fun projectedWorkAvailable(): Boolean {
            if (truncated) return false
            nodesVisited++
            if (nodesVisited > nodeLimit || clockMillis() >= deadlineMillis - DEADLINE_MARGIN_MILLIS) {
                truncated = true
            }
            return !truncated
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
    )

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

    private const val BOARD_TO_SCORE = 100.0
    private const val MAX_ADJUSTMENT = 800.0
    private const val DEADLINE_MARGIN_MILLIS = 20L
    private const val RESPONSE_SOFTMIN_TEMPERATURE = 0.35
    private const val FUTURE_DELTA_DISCOUNT = 0.90
    private const val UNKNOWN_RESPONSE_RESERVE = 0.20
    private const val UNKNOWN_PUBLIC_RESPONSE_TAG = "unknown_public_response"
    private const val STANDARD_MOVE_SLOTS = 4.0

    private fun BattleActionCandidate.isUnknownPublicResponse(): Boolean =
        UNKNOWN_PUBLIC_RESPONSE_TAG in tags || componentActions.any { it.isUnknownPublicResponse() }
}
