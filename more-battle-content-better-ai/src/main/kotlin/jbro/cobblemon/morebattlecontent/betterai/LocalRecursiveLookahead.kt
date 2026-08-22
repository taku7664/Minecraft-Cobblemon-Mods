package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import java.util.UUID
import kotlin.math.roundToInt

internal data class LocalLookaheadEvaluation(
    val ranked: List<LocalBattleActionRank>,
    val nodesVisited: Int,
    val branchesPruned: Int,
    val depthCompleted: Int,
    val truncated: Boolean,
    val publicResponseIncomplete: Boolean,
)

/**
 * Full-turn, public-information minimax for single battles.
 *
 * One depth consumes both trainers' actions. The local side maximizes the resulting public board
 * value and the opponent minimizes it. Hidden opponent moves are never invented to fill a branch.
 */
internal object LocalRecursiveLookaheadEvaluator {
    fun evaluate(
        ranked: List<LocalBattleActionRank>,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        clockMillis: () -> Long = System::currentTimeMillis,
    ): LocalLookaheadEvaluation {
        val requestedDepth = profile.difficulty.lookaheadPlies.coerceAtLeast(1)
        if (context.state.format != BattleFormat.SINGLE) {
            return LocalLookaheadEvaluation(ranked, 0, 0, 0, false, true)
        }
        val baseline = LocalLookaheadStateEvaluator.evaluate(context.state, context)
        var accepted = ranked
        var completedDepth = 0
        var totalNodes = 0
        var totalBranchesPruned = 0
        var truncated = false
        var publicResponseIncomplete = false
        for (depth in 1..requestedDepth) {
            val search = Search(
                context = context,
                profile = profile,
                deadlineMillis = context.deadlineEpochMillis,
                nodeLimit = nodeLimit(depth),
                clockMillis = clockMillis,
            )
            val evaluated = ranked.map { rank ->
                val evaluation = search.rootActionValue(context.state, rank.outcome.candidate, depth)
                if (evaluation == null) rank else {
                    val unexecutedSecureKoCorrection = rank.outcome.secureStandardKnockouts *
                        LocalBattleActionPolicy.SECURE_KNOCKOUT_BONUS *
                        (1.0 - evaluation.ownExecutionProbability)
                    val rawAdjustment = (evaluation.value - baseline) * BOARD_TO_SCORE -
                        unexecutedSecureKoCorrection
                    val adjustment = (rawAdjustment * search.publicResponseCoverage)
                        .coerceIn(-MAX_ADJUSTMENT, MAX_ADJUSTMENT)
                    rank.copy(
                        comparisonValue = rank.comparisonValue + adjustment,
                        lookaheadUtility = adjustment,
                        executionProbability = evaluation.ownExecutionProbability,
                        worstResponseHpRetention = if (rank.outcome.candidate.kind == BattleActionKind.SWITCH) {
                            val entryHp = rank.outcome.switchPostEntryHp ?: 1.0
                            if (entryHp <= 0.0) 0.0 else (evaluation.worstResponseRemainingHp / entryHp)
                                .coerceIn(0.0, 1.0)
                        } else {
                            1.0
                        },
                    )
                }
            }
            totalNodes += search.nodesVisited
            totalBranchesPruned += search.branchesPruned
            publicResponseIncomplete = publicResponseIncomplete || search.publicResponseIncomplete
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
        )
    }

    private class Search(
        private val context: BattleDecisionContext,
        private val profile: BattleTrainerProfile,
        private val deadlineMillis: Long,
        private val nodeLimit: Int,
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
        private val memo = HashMap<SearchKey, Double>()

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
            val ownActions = PublicFutureActionFactory.actions(state, BattleSide.ALLY, context.publicActionCatalog, history)
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
                current.remainingPokemonBySide.getValue(side) > 0 && current.pokemon.none {
                    it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
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
                resolution.publiclyKnownFraction * resolution.publiclyKnownFraction,
            )
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
            val projections = PublicSingleTurnProjector.project(state, ownAction, opponentAction, context, history)
            if (projections.isEmpty()) return null
            val trackedOwnPokemonId = when (ownAction.kind) {
                BattleActionKind.SWITCH -> ownAction.switchPokemonId
                BattleActionKind.COMPOSITE -> ownAction.componentActions
                    .firstOrNull { it.kind == BattleActionKind.SWITCH }
                    ?.switchPokemonId
                    ?: state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null }?.battlePokemonId
                else -> state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null }?.battlePokemonId
            }
            val orderExpectations = projections.groupBy(PublicTurnProjection::order).values.mapNotNull { outcomes ->
                val totalProbability = outcomes.sumOf(PublicTurnProjection::probability)
                if (totalProbability <= 0.0) return@mapNotNull null
                var executionProbability = 0.0
                var remainingHpFraction = 0.0
                val value = outcomes.sumOf { rawOutcome ->
                    val outcome = rawOutcome.copy(
                        state = RecursiveSnapshotActionConstraints.clearFromProjectedState(rawOutcome.state),
                    )
                    if (budgetExhausted()) return null
                    val ownActionExecuted = when (ownAction.kind) {
                        BattleActionKind.USE_MOVE -> BattleSide.ALLY in outcome.executedSides
                        BattleActionKind.SWITCH -> outcome.state.pokemon.any {
                            it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
                        }
                        BattleActionKind.COMPOSITE ->
                            BattleSide.ALLY in outcome.executedSides || BattleSide.ALLY in outcome.switchedSides
                        BattleActionKind.WAIT, BattleActionKind.FORFEIT -> true
                    }
                    if (ownActionExecuted) {
                        executionProbability += outcome.probability
                    }
                    remainingHpFraction += (
                        trackedOwnPokemonId?.let { id ->
                            outcome.state.pokemon.firstOrNull { it.battlePokemonId == id }?.hpFraction
                        } ?: 0.0
                    ) * outcome.probability
                    val immediateValue = stateUtility(outcome.state)
                    val stopContinuation = !battleEnded(outcome.state) &&
                        LocalTurnBranchPruner.shouldStopContinuation(
                            immediateBoardDelta = immediateValue - turnStartValue,
                            depthRemaining = depth,
                            newlyLostAllyHpBefore = LocalTurnBranchPruner.newlyLostAllyHpBefore(
                                state,
                                outcome.state,
                            ),
                        )
                    val value = if (depth <= 1 || battleEnded(outcome.state) || stopContinuation) {
                        if (stopContinuation) branchesPruned++
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
                            opponentSwitch = opponentAction.kind == BattleActionKind.SWITCH,
                            allyRepeated = false,
                            opponentRepeated = history.opponentSwitchedLastTurn,
                        )
                    } else {
                        LocalRecursiveSwitchTempo.adjustment(
                            allySwitch = ownAction.kind == BattleActionKind.SWITCH,
                            opponentSwitch = opponentAction.kind == BattleActionKind.SWITCH,
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
                TurnValue(
                    value,
                    (executionProbability / totalProbability).coerceIn(0.0, 1.0),
                    (remainingHpFraction / totalProbability).coerceIn(0.0, 1.0),
                )
            }
            val worstOrder = orderExpectations.minByOrNull(TurnValue::value) ?: return null
            return worstOrder.copy(
                ownRemainingHpFraction = orderExpectations.minOf(TurnValue::ownRemainingHpFraction),
            )
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
            val worstWeight = when (profile.difficulty.tier) {
                BattleTrainerTier.INTRODUCTORY -> 0.20
                BattleTrainerTier.STANDARD -> 0.40
                BattleTrainerTier.ADVANCED -> 0.65
                BattleTrainerTier.BOSS -> 0.85
            }
            return TurnValue(
                value = expected.value * (1.0 - worstWeight) + worst.value * worstWeight,
                ownExecutionProbability = expected.ownExecutionProbability * (1.0 - worstWeight) +
                    worst.ownExecutionProbability * worstWeight,
                ownRemainingHpFraction = minOf(expected.ownRemainingHpFraction, worst.ownRemainingHpFraction),
            )
        }

        private fun stateUtility(state: BattleStateView): Double =
            LocalLookaheadStateEvaluator.evaluate(state, context)

        private data class TurnValue(
            val value: Double,
            val ownExecutionProbability: Double,
            val ownRemainingHpFraction: Double,
        )
        private data class OpponentTurnValue(val action: BattleActionCandidate, val value: TurnValue)

        private fun completeOpponentActions(
            state: BattleStateView,
            history: RecursiveActionHistory,
        ): List<BattleActionCandidate>? {
            val activeOpponent = state.pokemon.singleOrNull {
                it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted
            }
            val actions = PublicFutureActionFactory.actions(
                state,
                BattleSide.OPPONENT,
                context.publicActionCatalog,
                history,
            )
            if (activeOpponent != null && !context.publicActionCatalog.isMoveSetComplete(activeOpponent.battlePokemonId)) {
                publicResponseIncomplete = true
                val revealedMoveCount = context.publicActionCatalog.forPokemon(activeOpponent.battlePokemonId).size
                val revealedFraction = (revealedMoveCount.toDouble() / STANDARD_MOVE_SLOTS).coerceIn(0.0, 1.0)
                publicResponseCoverage = minOf(publicResponseCoverage, revealedFraction * revealedFraction)
                return actions + BattleActionCandidate(
                    actionId = "lookahead:opponent:unknown-public-response",
                    kind = BattleActionKind.WAIT,
                    tags = setOf(UNKNOWN_PUBLIC_RESPONSE_TAG),
                )
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
    }

    private data class SearchKey(val depth: Int, val state: String, val history: RecursiveActionHistory)
    private data class RootActionEvaluation(
        val value: Double,
        val ownExecutionProbability: Double,
        val worstResponseRemainingHp: Double,
    )

    private fun fingerprint(state: BattleStateView): String = buildString {
        append(state.turn).append('|')
        state.pokemon.sortedBy { it.battlePokemonId }.forEach { pokemon ->
            append(pokemon.battlePokemonId).append(':')
            append(pokemon.side.ordinal).append(':')
            append(pokemon.activeSlot ?: -1).append(':')
            append((pokemon.hpFraction * 10_000).roundToInt()).append(':')
            append(pokemon.statusId ?: "-").append(':')
            append(pokemon.formId ?: "-").append(':')
            append(pokemon.knownHeldItemId ?: "-").append(':')
            append(pokemon.actionConstraints.taunted).append(':')
            append(pokemon.actionConstraints.encoreMoveId ?: "-").append(':')
            append(pokemon.actionConstraints.trapped).append(':')
            append(pokemon.actionConstraints.mustRecharge).append(':')
            pokemon.statStages.toSortedMap().forEach { (stat, stage) -> append(stat).append('=').append(stage).append(',') }
            append(';')
        }
        BattleSide.entries.forEach { side ->
            append("remaining:").append(side.ordinal).append('=')
                .append(state.remainingPokemonBySide.getValue(side)).append(';')
            state.field.sideConditions.getValue(side).sortedBy { it.effectId }.forEach { effect ->
                append(side.ordinal).append(':').append(effect.effectId).append(':')
                append(effect.remainingTurns).append(':').append(effect.stacks).append(';')
            }
        }
    }

    private fun battleEnded(state: BattleStateView): Boolean = BattleSide.entries.any { side ->
        state.remainingPokemonBySide.getValue(side) <= 0
    }

    private fun nodeLimit(depth: Int): Int = when (depth) {
        1 -> 5_000
        2 -> 50_000
        3 -> 300_000
        else -> 2_000_000
    }

    private const val BOARD_TO_SCORE = 100.0
    private const val MAX_ADJUSTMENT = 800.0
    private const val DEADLINE_MARGIN_MILLIS = 20L
    private const val RESPONSE_SOFTMIN_TEMPERATURE = 0.35
    private const val FUTURE_DELTA_DISCOUNT = 0.90
    private const val UNKNOWN_RESPONSE_RESERVE = 0.20
    private const val UNKNOWN_PUBLIC_RESPONSE_TAG = "unknown_public_response"
    private const val STANDARD_MOVE_SLOTS = 4.0

    private fun BattleActionCandidate.isUnknownPublicResponse(): Boolean = UNKNOWN_PUBLIC_RESPONSE_TAG in tags
}

internal data class RecursiveActionHistory(
    val allySwitchedLastTurn: Boolean = false,
    val opponentSwitchedLastTurn: Boolean = false,
    val moveUses: Map<RecursiveMoveUseKey, Int> = emptyMap(),
    val rechargingPokemonIds: Set<UUID> = emptySet(),
    val tauntTurnsByPokemon: Map<UUID, Int> = emptyMap(),
    val encoreByPokemon: Map<UUID, RecursiveEncoreLock> = emptyMap(),
    val trappedByPokemon: Map<UUID, RecursiveTrapLock> = emptyMap(),
    val lastMoveByPokemon: Map<UUID, String> = emptyMap(),
    val moveStreakByPokemon: Map<UUID, RecursiveMoveStreak> = emptyMap(),
    val actedSinceEntryPokemonIds: Set<UUID> = emptySet(),
    val badPoisonTurnsByPokemon: Map<UUID, Int> = emptyMap(),
)

internal data class RecursiveMoveUseKey(val pokemonId: UUID, val moveId: String)

internal data class RecursiveEncoreLock(val moveId: String, val remainingTurns: Int)

internal data class RecursiveTrapLock(val sourcePokemonId: UUID, val remainingTurns: Int)

internal data class RecursiveMoveStreak(val moveId: String, val count: Int)

internal object RecursiveSnapshotActionConstraints {
    fun seed(
        state: BattleStateView,
        allySwitchedLastTurn: Boolean = false,
        allyLastMoveId: String? = null,
        allySameMoveRepeatCount: Int = 0,
    ): RecursiveActionHistory {
        val activeBySide = BattleSide.entries.associateWith { side ->
            state.pokemon.singleOrNull { it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        }
        val active = activeBySide.values.filterNotNull()
        val publicMoveStreaks = active.mapNotNull { pokemon ->
            publicMoveStreak(state, pokemon.battlePokemonId)?.let { pokemon.battlePokemonId to it }
        }.toMap().toMutableMap()
        val ally = activeBySide[BattleSide.ALLY]
        if (ally != null && allyLastMoveId != null && allySameMoveRepeatCount > 0) {
            publicMoveStreaks[ally.battlePokemonId] = RecursiveMoveStreak(
                allyLastMoveId,
                allySameMoveRepeatCount,
            )
        }
        return RecursiveActionHistory(
            allySwitchedLastTurn = allySwitchedLastTurn,
            rechargingPokemonIds = active.filter { it.actionConstraints.mustRecharge }
                .mapTo(linkedSetOf(), BattlePokemonStateView::battlePokemonId),
            tauntTurnsByPokemon = active.filter { it.actionConstraints.taunted }
                .associate { it.battlePokemonId to MAXIMUM_SNAPSHOT_CONTROL_TURNS },
            encoreByPokemon = active.mapNotNull { pokemon ->
                pokemon.actionConstraints.encoreMoveId?.let { moveId ->
                    pokemon.battlePokemonId to RecursiveEncoreLock(moveId, MAXIMUM_SNAPSHOT_CONTROL_TURNS)
                }
            }.toMap(),
            trappedByPokemon = active.mapNotNull { pokemon ->
                if (!pokemon.actionConstraints.trapped) return@mapNotNull null
                val source = activeBySide[if (pokemon.side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY]
                    ?: return@mapNotNull null
                pokemon.battlePokemonId to RecursiveTrapLock(source.battlePokemonId, MAXIMUM_SNAPSHOT_TRAP_TURNS)
            }.toMap(),
            lastMoveByPokemon = active.mapNotNull { pokemon ->
                pokemon.actionConstraints.encoreMoveId?.let { pokemon.battlePokemonId to it }
            }.toMap() + publicMoveStreaks.mapValues { it.value.moveId },
            moveStreakByPokemon = publicMoveStreaks,
            badPoisonTurnsByPokemon = LocalBadPoisonCounter.seed(state),
        )
    }

    private fun publicMoveStreak(state: BattleStateView, pokemonId: UUID): RecursiveMoveStreak? {
        val lastEntrySequence = state.observedEvents.asSequence()
            .filter { it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId == pokemonId }
            .maxOfOrNull { it.sequence }
            ?: Long.MIN_VALUE
        val moves = state.observedEvents.asSequence()
            .filter {
                it.sequence > lastEntrySequence &&
                    it.kind == BattleObservedEventKind.MOVE_USED &&
                    it.actorPokemonId == pokemonId
            }
            .mapNotNull { it.publicValueId }
            .toList()
        val lastMove = moves.lastOrNull() ?: return null
        val count = moves.asReversed().takeWhile { sameMove(it, lastMove) }.size
        return RecursiveMoveStreak(lastMove, count)
    }

    private fun sameMove(first: String, second: String): Boolean = canonicalId(first) == canonicalId(second)

    private fun canonicalId(value: String): String = value.substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    fun clearFromProjectedState(state: BattleStateView): BattleStateView {
        if (state.pokemon.none { it.actionConstraints != BattlePokemonActionConstraintView.empty() }) return state
        return state.copyState(
            pokemon = state.pokemon.map { pokemon ->
                pokemon.copyState(actionConstraints = BattlePokemonActionConstraintView.empty())
            },
        )
    }

    private const val MAXIMUM_SNAPSHOT_CONTROL_TURNS = 3
    private const val MAXIMUM_SNAPSHOT_TRAP_TURNS = 5
}

internal enum class RecursiveControlEffectKind { RECHARGE, TAUNT, ENCORE, TRAP }

internal data class RecursiveControlEffect(
    val kind: RecursiveControlEffectKind,
    val sourceSide: BattleSide,
    val sourcePokemonId: UUID,
    val targetPokemonId: UUID,
)

internal object RecursiveHistoryProjector {
    fun project(
        previous: RecursiveActionHistory,
        stateBefore: BattleStateView,
        outcome: PublicTurnProjection,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
    ): RecursiveActionHistory {
        val actions = mapOf(BattleSide.ALLY to allyAction, BattleSide.OPPONENT to opponentAction)
        val actorIds = BattleSide.entries.associateWith { side ->
            stateBefore.pokemon.singleOrNull { pokemon ->
                pokemon.side == side && pokemon.activeSlot != null && !pokemon.fainted && pokemon.hpFraction > 0.0
            }?.battlePokemonId
        }
        val moveUses = previous.moveUses.toMutableMap()
        val lastMoves = previous.lastMoveByPokemon.toMutableMap()
        val moveStreaks = previous.moveStreakByPokemon.toMutableMap()
        outcome.executedMoveIdsByPokemon.forEach { (actorId, moveId) ->
                val key = RecursiveMoveUseKey(actorId, moveId)
                moveUses[key] = (moveUses[key] ?: 0) + 1
                lastMoves[actorId] = moveId
                val previousStreak = moveStreaks[actorId]
                moveStreaks[actorId] = RecursiveMoveStreak(
                    moveId = moveId,
                    count = if (previousStreak != null && sameMove(previousStreak.moveId, moveId)) {
                        previousStreak.count + 1
                    } else {
                        1
                    },
                )
        }

        val taunt = decrement(previous.tauntTurnsByPokemon).toMutableMap()
        val trapped = previous.trappedByPokemon.mapNotNull { (pokemonId, lock) ->
            lock.copy(remainingTurns = lock.remainingTurns - 1)
                .takeIf { it.remainingTurns > 0 }
                ?.let { pokemonId to it }
        }.toMap().toMutableMap()
        val encore = previous.encoreByPokemon.mapNotNull { (pokemonId, lock) ->
            lock.copy(remainingTurns = lock.remainingTurns - 1)
                .takeIf { it.remainingTurns > 0 }
                ?.let { pokemonId to it }
        }.toMap().toMutableMap()
        val recharge = linkedSetOf<UUID>()

        outcome.controlEffects.forEach { effect ->
            val targetSide = stateBefore.pokemon.firstOrNull {
                it.battlePokemonId == effect.targetPokemonId
            }?.side
            val sourceIndex = outcome.order.indexOf(effect.sourceSide)
            val targetMovedFirst = targetSide != null &&
                outcome.order.indexOf(targetSide).let { it >= 0 && sourceIndex >= 0 && it < sourceIndex } &&
                targetSide in outcome.executedSides
            when (effect.kind) {
                RecursiveControlEffectKind.RECHARGE -> recharge += effect.targetPokemonId
                RecursiveControlEffectKind.TAUNT -> taunt[effect.targetPokemonId] =
                    if (targetMovedFirst) STANDARD_CONTROL_TURNS else FUTURE_CONTROL_TURNS_AFTER_EARLY_HIT
                RecursiveControlEffectKind.TRAP -> trapped[effect.targetPokemonId] = RecursiveTrapLock(
                    sourcePokemonId = effect.sourcePokemonId,
                    remainingTurns = MINIMUM_TRAP_FUTURE_TURNS,
                )
                RecursiveControlEffectKind.ENCORE -> {
                    val lockedMove = if (targetMovedFirst) {
                        outcome.executedMoveIdsByPokemon[effect.targetPokemonId]
                    } else {
                        previous.lastMoveByPokemon[effect.targetPokemonId]
                    }
                    if (lockedMove != null) {
                        val turns = if (targetMovedFirst) {
                            STANDARD_CONTROL_TURNS
                        } else {
                            FUTURE_CONTROL_TURNS_AFTER_EARLY_HIT
                        }
                        encore[effect.targetPokemonId] = RecursiveEncoreLock(lockedMove, turns)
                    }
                }
            }
        }

        val activeIds = outcome.state.pokemon.filter { it.activeSlot != null && !it.fainted }.mapTo(hashSetOf()) {
            it.battlePokemonId
        }
        taunt.keys.retainAll(activeIds)
        trapped.keys.retainAll(activeIds)
        trapped.entries.removeIf { (_, lock) -> lock.sourcePokemonId !in activeIds }
        encore.keys.retainAll(activeIds)
        recharge.retainAll(activeIds)
        lastMoves.keys.retainAll(activeIds)
        moveStreaks.keys.retainAll(activeIds)
        val actedSinceEntry = (previous.actedSinceEntryPokemonIds + outcome.executedMoveIdsByPokemon.keys)
            .filterTo(linkedSetOf()) { it in activeIds }

        return RecursiveActionHistory(
            allySwitchedLastTurn = allyAction.kind == BattleActionKind.SWITCH || BattleSide.ALLY in outcome.switchedSides,
            opponentSwitchedLastTurn = opponentAction.kind == BattleActionKind.SWITCH || BattleSide.OPPONENT in outcome.switchedSides,
            moveUses = moveUses,
            rechargingPokemonIds = recharge,
            tauntTurnsByPokemon = taunt,
            encoreByPokemon = encore,
            trappedByPokemon = trapped,
            lastMoveByPokemon = lastMoves,
            moveStreakByPokemon = moveStreaks,
            actedSinceEntryPokemonIds = actedSinceEntry,
            badPoisonTurnsByPokemon = outcome.badPoisonTurnsByPokemon,
        )
    }

    private fun decrement(values: Map<UUID, Int>): Map<UUID, Int> = values.mapNotNull { (id, turns) ->
        (turns - 1).takeIf { it > 0 }?.let { id to it }
    }.toMap()

    private fun sameMove(first: String, second: String): Boolean = canonicalId(first) == canonicalId(second)

    private fun canonicalId(value: String): String = value.substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private const val STANDARD_CONTROL_TURNS = 3
    private const val FUTURE_CONTROL_TURNS_AFTER_EARLY_HIT = 2
    private const val MINIMUM_TRAP_FUTURE_TURNS = 4
}

internal object LocalRecursiveSwitchTempo {
    fun adjustment(
        allySwitch: Boolean,
        opponentSwitch: Boolean,
        allyRepeated: Boolean,
        opponentRepeated: Boolean,
    ): Double = switchCost(opponentSwitch, opponentRepeated) - switchCost(allySwitch, allyRepeated)

    private fun switchCost(switched: Boolean, repeated: Boolean): Double = if (!switched) {
        0.0
    } else {
        SWITCH_TEMPO_COST + if (repeated) CONSECUTIVE_SWITCH_COST else 0.0
    }

    private const val SWITCH_TEMPO_COST = 0.15
    private const val CONSECUTIVE_SWITCH_COST = 0.30
}

/** Applies a board-unit opportunity cost to healthy, repeated, pure recovery lines. */
internal object LocalRecursiveMoveHabit {
    fun cost(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        history: RecursiveActionHistory,
    ): Double {
        if (action.kind != BattleActionKind.USE_MOVE) return 0.0
        val moveId = action.moveId ?: return 0.0
        val actor = state.pokemon.singleOrNull {
            it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return 0.0
        val streak = history.moveStreakByPokemon[actor.battlePokemonId] ?: return 0.0
        if (streak.count < MINIMUM_REPEATS || canonicalId(streak.moveId) != canonicalId(moveId)) return 0.0
        val effects = action.moveDetails?.effects?.effects.orEmpty()
        val pureRecovery = effects.isNotEmpty() && effects.all {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER
        }
        if (!pureRecovery || actor.hpFraction <= SURVIVAL_HP_THRESHOLD) return 0.0
        val healthyScale = ((actor.hpFraction - SURVIVAL_HP_THRESHOLD) / (1.0 - SURVIVAL_HP_THRESHOLD))
            .coerceIn(0.0, 1.0)
        val repeatPressure = (streak.count - 1).coerceIn(1, MAXIMUM_REPEAT_PRESSURE)
        return healthyScale * repeatPressure * BOARD_COST_PER_REPEAT
    }

    private fun canonicalId(value: String): String = value.substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private const val MINIMUM_REPEATS = 2
    private const val SURVIVAL_HP_THRESHOLD = 0.65
    private const val BOARD_COST_PER_REPEAT = 0.35
    private const val MAXIMUM_REPEAT_PRESSURE = 4
}

internal object PublicFutureActionFactory {
    fun actions(
        state: BattleStateView,
        side: BattleSide,
        catalog: BattlePublicActionCatalogView,
        history: RecursiveActionHistory = RecursiveActionHistory(),
    ): List<BattleActionCandidate> {
        if (state.format != BattleFormat.SINGLE) return emptyList()
        val active = state.pokemon.singleOrNull { it.side == side && it.activeSlot != null && !it.fainted }
            ?: return emptyList()
        val actorSlot = active.activeSlot ?: 0
        if (active.actionConstraints.mustRecharge || active.battlePokemonId in history.rechargingPokemonIds) {
            return listOf(
                BattleActionCandidate(
                    actionId = "lookahead:${side.name.lowercase()}:${active.battlePokemonId}:recharge",
                    kind = BattleActionKind.WAIT,
                    tags = setOf("public_lookahead", "forced_recharge"),
                ),
            )
        }
        val encoreMoveId = active.actionConstraints.encoreMoveId ?: history.encoreByPokemon[active.battlePokemonId]
            ?.takeIf { it.remainingTurns > 0 }
            ?.moveId
        val taunted = active.actionConstraints.taunted ||
            (history.tauntTurnsByPokemon[active.battlePokemonId] ?: 0) > 0
        val moves = active.let { pokemon ->
            catalog.forPokemon(pokemon.battlePokemonId).filter { option ->
                val used = history.moveUses[RecursiveMoveUseKey(pokemon.battlePokemonId, option.moveId)] ?: 0
                option.details.currentPp - used > 0 &&
                    (canonicalId(option.moveId) !in FIRST_ENTRY_ONLY_MOVES ||
                        pokemon.battlePokemonId !in history.actedSinceEntryPokemonIds) &&
                    (!taunted || option.details.damageCategory != BattleMoveDamageCategory.STATUS) &&
                    (encoreMoveId == null || option.moveId == encoreMoveId)
            }.mapIndexed { index, option ->
                BattleActionCandidate(
                    actionId = "lookahead:${side.name.lowercase()}:${pokemon.battlePokemonId}:move:${option.moveId}",
                    kind = BattleActionKind.USE_MOVE,
                    actorSlot = actorSlot,
                    moveSlot = index,
                    moveId = option.moveId,
                    targets = moveTargets(option.details.targetPattern, side),
                    moveDetails = option.details,
                    tags = setOf("public_lookahead"),
                )
            }
        }
        val opposingActive = state.pokemon.singleOrNull {
            it.side != side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val trapped = active.actionConstraints.trapped ||
            (history.trappedByPokemon[active.battlePokemonId]?.remainingTurns ?: 0) > 0 ||
            trappedByKnownAbility(active, opposingActive)
        val switches = if (trapped) emptyList() else state.pokemon.filter {
            it.side == side && it.activeSlot == null && !it.fainted
        }.map { bench ->
            BattleActionCandidate(
                actionId = "lookahead:${side.name.lowercase()}:switch:${bench.battlePokemonId}",
                kind = BattleActionKind.SWITCH,
                actorSlot = actorSlot,
                switchPokemonId = bench.battlePokemonId,
                tags = setOf("public_lookahead"),
            )
        }
        return moves + switches
    }

    private fun trappedByKnownAbility(
        active: BattlePokemonStateView,
        opposingActive: BattlePokemonStateView?,
    ): Boolean {
        if (opposingActive == null || "ghost" in active.knownTypeIds.map(::canonicalId)) return false
        return when (canonicalId(opposingActive.knownAbilityId)) {
            "shadowtag" -> canonicalId(active.knownAbilityId) != "shadowtag"
            "arenatrap" -> "flying" !in active.knownTypeIds.map(::canonicalId) &&
                canonicalId(active.knownAbilityId) != "levitate" &&
                canonicalId(active.knownHeldItemId) != "airballoon"
            "magnetpull" -> "steel" in active.knownTypeIds.map(::canonicalId)
            else -> false
        }
    }

    private fun canonicalId(value: String?): String = value.orEmpty()
        .substringAfter(':')
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private val FIRST_ENTRY_ONLY_MOVES = setOf("fakeout", "firstimpression", "matblock")

    private fun moveTargets(
        pattern: BattleMoveTargetPattern,
        side: BattleSide,
    ): List<BattleTargetSlot> = when (pattern) {
        BattleMoveTargetPattern.SELF,
        BattleMoveTargetPattern.SIDE,
        -> emptyList()
        else -> listOf(BattleTargetSlot(if (side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY, 0))
    }
}

internal data class PublicTurnProjection(
    val state: BattleStateView,
    val order: List<BattleSide>,
    val probability: Double = 1.0,
    val executedSides: Set<BattleSide> = emptySet(),
    val controlEffects: List<RecursiveControlEffect> = emptyList(),
    val switchedSides: Set<BattleSide> = emptySet(),
    val executedMoveIdsByPokemon: Map<UUID, String> = emptyMap(),
    val badPoisonTurnsByPokemon: Map<UUID, Int> = emptyMap(),
)

internal object PublicSingleTurnProjector {
    fun project(
        initialState: BattleStateView,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
        sourceContext: BattleDecisionContext,
        history: RecursiveActionHistory = RecursiveActionHistory(),
    ): List<PublicTurnProjection> {
        require(initialState.format == BattleFormat.SINGLE)
        var switchedState = initialState
        if (allyAction.kind == BattleActionKind.SWITCH) switchedState = applySwitch(switchedState, BattleSide.ALLY, allyAction, sourceContext)
        if (opponentAction.kind == BattleActionKind.SWITCH) switchedState = applySwitch(switchedState, BattleSide.OPPONENT, opponentAction, sourceContext)

        val moveActions = buildMap {
            if (allyAction.kind == BattleActionKind.USE_MOVE) put(BattleSide.ALLY, allyAction)
            if (opponentAction.kind == BattleActionKind.USE_MOVE) put(BattleSide.OPPONENT, opponentAction)
        }
        val orders = when (moveActions.size) {
            0 -> listOf(emptyList())
            1 -> listOf(moveActions.keys.toList())
            else -> possibleOrders(switchedState, requireNotNull(moveActions[BattleSide.ALLY]), requireNotNull(moveActions[BattleSide.OPPONENT]))
        }
        return orders.flatMap { order ->
            var branches = listOf(
                WeightedState(
                    state = switchedState,
                    probability = 1.0,
                    lastMoveByPokemon = history.lastMoveByPokemon,
                ),
            )
            order.forEach { side ->
                branches = branches.flatMap { branch ->
                    applyMove(
                        branch.state,
                        side,
                        requireNotNull(moveActions[side]),
                        sourceContext,
                        branch.protectedSides,
                        branch.protectionAttackDrops,
                        branch.tauntedPokemonIds,
                        branch.forcedMoveIdsByPokemon,
                        history,
                    ).map { outcome ->
                        val newlyTaunted = outcome.controlEffects.filter {
                            it.kind == RecursiveControlEffectKind.TAUNT
                        }.mapTo(linkedSetOf()) { it.targetPokemonId }
                        val newlyForced = outcome.controlEffects.filter {
                            it.kind == RecursiveControlEffectKind.ENCORE && it.targetPokemonId !in outcome.executedMoveIdsByPokemon
                        }.mapNotNull { effect ->
                            branch.lastMoveByPokemon[effect.targetPokemonId]?.let { effect.targetPokemonId to it }
                        }.toMap()
                        WeightedState(
                            outcome.state,
                            branch.probability * outcome.probability,
                            branch.executedSides + outcome.executedSides,
                            branch.protectedSides + outcome.protectedSides,
                            branch.controlEffects + outcome.controlEffects,
                            branch.switchedSides + outcome.switchedSides,
                            branch.protectionAttackDrops + outcome.protectionAttackDrops,
                            branch.tauntedPokemonIds + outcome.tauntedPokemonIds + newlyTaunted,
                            branch.forcedMoveIdsByPokemon + outcome.forcedMoveIdsByPokemon + newlyForced,
                            branch.lastMoveByPokemon + outcome.executedMoveIdsByPokemon,
                            branch.executedMoveIdsByPokemon + outcome.executedMoveIdsByPokemon,
                        )
                    }
                }.let(::mergeBranches)
            }
            branches.map { outcome ->
                val badPoisonTurns = LocalBadPoisonCounter.advance(initialState, outcome.state, history)
                PublicTurnProjection(
                    incrementTurn(LocalEndTurnStateProjector.project(outcome.state, badPoisonTurns)),
                    order,
                    outcome.probability,
                    outcome.executedSides,
                    outcome.controlEffects,
                    outcome.switchedSides,
                    outcome.executedMoveIdsByPokemon,
                    badPoisonTurns,
                )
            }
        }.groupBy { projection ->
            listOf(
                projection.order,
                fingerprint(projection.state),
                projection.executedSides,
                projection.controlEffects,
                projection.switchedSides,
                projection.executedMoveIdsByPokemon,
                projection.badPoisonTurnsByPokemon,
            )
        }
            .values
            .map { identical -> identical.first().copy(probability = identical.sumOf(PublicTurnProjection::probability)) }
    }

    private fun applySwitch(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        sourceContext: BattleDecisionContext,
    ): BattleStateView {
        val calculatedContext = PublicBattleTacticalCalculator.calculate(
            actionContext(state, action.withoutFacts(), sourceContext),
            side,
        )
        return LocalSwitchStateProjector.project(state, side, calculatedContext.candidates.single())
    }

    private fun applyMove(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        sourceContext: BattleDecisionContext,
        protectedSides: Set<BattleSide>,
        protectionAttackDrops: Map<BattleSide, Int> = emptyMap(),
        tauntedPokemonIds: Set<UUID> = emptySet(),
        forcedMoveIdsByPokemon: Map<UUID, String> = emptyMap(),
        history: RecursiveActionHistory = RecursiveActionHistory(),
        availabilityChecked: Boolean = false,
    ): List<WeightedState> {
        val actorBeforeTransition = state.pokemon.firstOrNull {
            it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return listOf(WeightedState(state, 1.0, protectedSides = protectedSides))
        if (!availabilityChecked && canonicalId(actorBeforeTransition.statusId) in PARALYSIS_IDS) {
            val unable = WeightedState(
                state = state,
                probability = FULL_PARALYSIS_PROBABILITY,
                protectedSides = protectedSides,
            )
            val able = applyMove(
                state,
                side,
                action,
                sourceContext,
                protectedSides,
                protectionAttackDrops,
                tauntedPokemonIds,
                forcedMoveIdsByPokemon,
                history,
                availabilityChecked = true,
            ).map { outcome ->
                outcome.copy(probability = outcome.probability * (1.0 - FULL_PARALYSIS_PROBABILITY))
            }
            return listOf(unable) + able
        }
        val effectiveAction = forcedMoveIdsByPokemon[actorBeforeTransition.battlePokemonId]
            ?.let { forcedMoveId -> forcedMoveAction(state, side, forcedMoveId, sourceContext) }
            ?: action
        val projectedFormState = LocalStanceChangeStateProjector.beforeMove(state, side, effectiveAction)
        val actor = projectedFormState.pokemon.single { it.battlePokemonId == actorBeforeTransition.battlePokemonId }
        if (actor.battlePokemonId in tauntedPokemonIds &&
            effectiveAction.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS
        ) {
            return listOf(WeightedState(projectedFormState, 1.0))
        }
        val actionContext = actionContext(projectedFormState, effectiveAction.withoutFacts(), sourceContext)
        val calculated = PublicBattleTacticalCalculator.calculate(actionContext, side)
        val calculatedAction = calculated.candidates.single()
        val projection = PublicActionOutcomeProjector.project(calculatedAction, calculated, side)
        val target = projectedFormState.pokemon.singleOrNull { it.side != side && it.activeSlot != null && !it.fainted }
        val effects = calculatedAction.moveDetails?.effects?.effects.orEmpty()
        if (effects.any { it.kind == BattleMoveEffectKind.PROTECT_USER && (it.probability ?: 1.0) == 1.0 }) {
            val contactDrop = if (canonicalId(calculatedAction.moveId) == "kingsshield") 1 else 0
            return listOf(
                WeightedState(
                    state = projectedFormState,
                    probability = 1.0,
                    executedSides = setOf(side),
                    protectedSides = setOf(side),
                    protectionAttackDrops = if (contactDrop > 0) mapOf(side to contactDrop) else emptyMap(),
                    executedMoveIdsByPokemon = mapOf(actor.battlePokemonId to requireNotNull(effectiveAction.moveId)),
                ),
            )
        }
        val targetIsProtected = target?.side in protectedSides
        val breaksProtection = effects.any { it.kind == BattleMoveEffectKind.BREAKS_PROTECTION }
        if (projection.publiclyNullified || (targetIsProtected && !breaksProtection)) {
            val contact = "contact" in calculatedAction.moveDetails?.effects?.mechanicFlags.orEmpty()
            val attackDrop = target?.side?.let(protectionAttackDrops::get) ?: 0
            val blockedState = if (targetIsProtected && contact && attackDrop > 0) {
                applyStatStage(projectedFormState, actor.battlePokemonId, "attack", -attackDrop)
            } else {
                projectedFormState
            }
            return listOf(
                WeightedState(
                    state = blockedState,
                    probability = 1.0,
                    executedSides = setOf(side),
                    executedMoveIdsByPokemon = effectiveAction.moveId?.let {
                        mapOf(actor.battlePokemonId to it)
                    }.orEmpty(),
                ),
            )
        }
        return PublicMoveOutcomeBranchProjector.project(calculatedAction, calculated, side).flatMap { moveOutcome ->
            if (!moveOutcome.hit) {
                listOf(
                    WeightedState(
                        state = projectedFormState,
                        probability = moveOutcome.probability,
                        executedSides = setOf(side),
                        executedMoveIdsByPokemon = effectiveAction.moveId?.let {
                            mapOf(actor.battlePokemonId to it)
                        }.orEmpty(),
                    ),
                )
            } else {
                val appliedHit = LocalDirectHitMechanics.apply(
                    projectedFormState,
                    actor.battlePokemonId,
                    target?.battlePokemonId,
                    moveOutcome.damageFraction,
                    effects,
                    ignoreTargetAbility = ignoresTargetAbility(actor, calculatedAction),
                )
                LocalContactAfterHitMechanics.project(
                    appliedHit.state,
                    actor.battlePokemonId,
                    target?.battlePokemonId,
                    calculatedAction,
                    appliedHit.directDamageFraction,
                ).flatMap { contactOutcome ->
                    applyChanceEffects(
                        contactOutcome.state,
                        actor.battlePokemonId,
                        target?.battlePokemonId,
                        effects,
                        accuracy = 1.0,
                        executedSide = side,
                    ).map { it.copy(probability = it.probability * contactOutcome.probability) }
                }.map { effectOutcome ->
                    val recharge = effects.any {
                        it.kind == BattleMoveEffectKind.RECHARGE_TURN && (it.probability ?: 1.0) >= CERTAIN_PROBABILITY
                    }
                    effectOutcome.copy(
                        probability = effectOutcome.probability * moveOutcome.probability,
                        controlEffects = effectOutcome.controlEffects + if (recharge) {
                            listOf(
                                RecursiveControlEffect(
                                    RecursiveControlEffectKind.RECHARGE,
                                    side,
                                    actor.battlePokemonId,
                                    actor.battlePokemonId,
                                ),
                            )
                        } else {
                            emptyList()
                        },
                        executedMoveIdsByPokemon = effectiveAction.moveId?.let {
                            mapOf(actor.battlePokemonId to it)
                        }.orEmpty(),
                    )
                }.map { effectOutcome ->
                    effectOutcome.copy(
                        controlEffects = effectOutcome.controlEffects + scriptedTrapEffects(
                            effectiveAction,
                            side,
                            actor.battlePokemonId,
                            target?.battlePokemonId,
                        ),
                    )
                }.map { effectOutcome ->
                    if (effects.any { it.kind == BattleMoveEffectKind.SWITCH_USER }) {
                        applyPivotSwitch(effectOutcome, side, sourceContext, history)
                    } else {
                        effectOutcome
                    }
                }
            }
        }.let(::mergeBranches)
    }

    private fun scriptedTrapEffects(
        action: BattleActionCandidate,
        sourceSide: BattleSide,
        actorId: UUID,
        targetId: UUID?,
    ): List<RecursiveControlEffect> {
        val moveId = canonicalId(action.moveId)
        if (moveId !in SCRIPTED_TARGET_TRAP_MOVES && moveId != "jawlock") return emptyList()
        val effects = arrayListOf<RecursiveControlEffect>()
        targetId?.let {
            effects += RecursiveControlEffect(RecursiveControlEffectKind.TRAP, sourceSide, actorId, it)
        }
        if (moveId == "jawlock") {
            effects += RecursiveControlEffect(RecursiveControlEffectKind.TRAP, sourceSide, actorId, actorId)
        }
        return effects
    }

    private fun forcedMoveAction(
        state: BattleStateView,
        side: BattleSide,
        moveId: String,
        sourceContext: BattleDecisionContext,
    ): BattleActionCandidate? = PublicFutureActionFactory.actions(
        state,
        side,
        sourceContext.publicActionCatalog,
    ).firstOrNull { it.kind == BattleActionKind.USE_MOVE && it.moveId == moveId }

    private fun applyStatStage(
        state: BattleStateView,
        pokemonId: UUID,
        statId: String,
        amount: Int,
    ): BattleStateView = state.copyState(
        pokemon = state.pokemon.map { pokemon ->
            if (pokemon.battlePokemonId != pokemonId) return@map pokemon
            val stages = pokemon.statStages.toMutableMap()
            stages[statId] = ((stages[statId] ?: 0) + amount).coerceIn(-6, 6)
            pokemon.copyState(statStages = stages)
        },
    )

    private fun applyPivotSwitch(
        branch: WeightedState,
        side: BattleSide,
        sourceContext: BattleDecisionContext,
        history: RecursiveActionHistory,
    ): WeightedState {
        val outgoing = branch.state.pokemon.singleOrNull {
            it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        } ?: return branch
        if (branch.controlEffects.any {
                it.kind == RecursiveControlEffectKind.TRAP && it.targetPokemonId == outgoing.battlePokemonId
            }
        ) return branch
        val choices = PublicFutureActionFactory.actions(
            branch.state,
            side,
            sourceContext.publicActionCatalog,
            history,
        ).filter { it.kind == BattleActionKind.SWITCH }.map { generated ->
            val action = BattleActionCandidate(
                actionId = generated.actionId + ":pivot",
                kind = BattleActionKind.SWITCH,
                actorSlot = requireNotNull(outgoing.activeSlot),
                switchPokemonId = generated.switchPokemonId,
                tags = generated.tags + "pivot_follow_up",
            )
            val projected = LocalSwitchStateProjector.project(branch.state, side, action)
            projected to LocalLookaheadStateEvaluator.evaluate(projected, sourceContext)
        }
        if (choices.isEmpty()) return branch
        val selected = if (side == BattleSide.ALLY) {
            choices.maxBy { it.second }
        } else {
            choices.minBy { it.second }
        }
        return branch.copy(state = selected.first, switchedSides = branch.switchedSides + side)
    }

    private fun ignoresTargetAbility(actor: BattlePokemonStateView, action: BattleActionCandidate): Boolean =
        canonicalId(actor.knownAbilityId) in ABILITY_IGNORING_ABILITIES ||
            action.moveDetails?.effects?.effects?.any { it.kind == BattleMoveEffectKind.IGNORE_ABILITY } == true

    private fun applyChanceEffects(
        initial: BattleStateView,
        actorId: UUID,
        targetId: UUID?,
        effects: List<BattleMoveEffectView>,
        accuracy: Double,
        executedSide: BattleSide,
    ): List<WeightedState> {
        val projectable = effects.filter { effect ->
            effect.kind in PROJECTED_EFFECT_KINDS &&
                effect.target in PROJECTED_EFFECT_TARGETS
        }
        var branches = listOf(WeightedState(initial, 1.0, setOf(executedSide)))
        projectable.forEach { effect ->
            val probability = ((effect.probability ?: 1.0) * accuracy).coerceIn(0.0, 1.0)
            if (probability <= 0.0) return@forEach
            branches = branches.flatMap { branch ->
                val controlEffect = recursiveControlEffect(effect, actorId, targetId, executedSide)
                val applied = applyEffect(branch.state, actorId, targetId, effect, executedSide)
                if (probability >= CERTAIN_PROBABILITY) {
                    listOf(
                        branch.copy(
                            state = applied,
                            controlEffects = branch.controlEffects + listOfNotNull(controlEffect),
                        ),
                    )
                } else if (applied === branch.state && controlEffect == null) {
                    listOf(branch)
                } else {
                    listOf(
                        branch.copy(probability = branch.probability * (1.0 - probability)),
                        branch.copy(
                            state = applied,
                            probability = branch.probability * probability,
                            controlEffects = branch.controlEffects + listOfNotNull(controlEffect),
                        ),
                    )
                }
            }.let(::mergeBranches)
        }
        return branches
    }

    private fun recursiveControlEffect(
        effect: BattleMoveEffectView,
        actorId: UUID,
        targetId: UUID?,
        sourceSide: BattleSide,
    ): RecursiveControlEffect? {
        if (effect.kind != BattleMoveEffectKind.VOLATILE_STATUS) return null
        val targetPokemonId = when (effect.target) {
            BattleMoveEffectTarget.USER -> actorId
            BattleMoveEffectTarget.SELECTED_TARGET -> targetId
            else -> null
        } ?: return null
        val kind = when (canonicalId(effect.valueId)) {
            "taunt" -> RecursiveControlEffectKind.TAUNT
            "encore" -> RecursiveControlEffectKind.ENCORE
            "trapped", "partiallytrapped", "meanlook", "octolock", "jawlock" -> RecursiveControlEffectKind.TRAP
            else -> return null
        }
        return RecursiveControlEffect(kind, sourceSide, actorId, targetPokemonId)
    }

    private fun applyEffect(
        state: BattleStateView,
        actorId: UUID,
        targetId: UUID?,
        effect: BattleMoveEffectView,
        executedSide: BattleSide,
    ): BattleStateView {
        if (effect.kind == BattleMoveEffectKind.SIDE_CONDITION) {
            return LocalFieldEffectProjector.apply(state, executedSide, effect)
        }
        val affectedId = when (effect.target) {
            BattleMoveEffectTarget.USER -> actorId
            BattleMoveEffectTarget.SELECTED_TARGET -> targetId
            else -> null
        } ?: return state
        val affected = state.pokemon.firstOrNull { it.battlePokemonId == affectedId } ?: return state
        if (affected.fainted || affected.hpFraction <= 0.0) return state
        val updated = when (effect.kind) {
            BattleMoveEffectKind.STATUS -> {
                if (affected.statusId != null || effect.valueId == null) return state
                affected.copyState(statusId = effect.valueId)
            }
            BattleMoveEffectKind.STAT_STAGE -> {
                val stages = affected.statStages.toMutableMap()
                effect.statStages.forEach { (stat, amount) ->
                    stages[stat] = ((stages[stat] ?: 0) + amount).coerceIn(-6, 6)
                }
                if (stages == affected.statStages) return state
                affected.copyState(statStages = stages)
            }
            else -> return state
        }
        return state.copyState(
            pokemon = state.pokemon.map { pokemon ->
                if (pokemon.battlePokemonId == affectedId) updated else pokemon
            },
        )
    }

    private fun mergeBranches(branches: List<WeightedState>): List<WeightedState> {
        val merged = branches.groupBy {
            listOf(
                fingerprint(it.state),
                it.executedSides,
                it.protectedSides,
                it.controlEffects,
                it.switchedSides,
                it.protectionAttackDrops,
                it.tauntedPokemonIds,
                it.forcedMoveIdsByPokemon,
                it.lastMoveByPokemon,
                it.executedMoveIdsByPokemon,
            )
        }.values.map { identical ->
            WeightedState(
                identical.first().state,
                identical.sumOf(WeightedState::probability),
                identical.first().executedSides,
                identical.first().protectedSides,
                identical.first().controlEffects,
                identical.first().switchedSides,
                identical.first().protectionAttackDrops,
                identical.first().tauntedPokemonIds,
                identical.first().forcedMoveIdsByPokemon,
                identical.first().lastMoveByPokemon,
                identical.first().executedMoveIdsByPokemon,
            )
        }.sortedByDescending(WeightedState::probability)
        val retained = merged.take(MAX_CHANCE_BRANCHES_PER_MOVE)
        val total = retained.sumOf(WeightedState::probability)
        return if (total > 0.0) retained.map { it.copy(probability = it.probability / total) } else retained
    }

    private fun possibleOrders(
        state: BattleStateView,
        allyAction: BattleActionCandidate,
        opponentAction: BattleActionCandidate,
    ): List<List<BattleSide>> {
        val allyPriority = effectivePriority(state, BattleSide.ALLY, allyAction)
        val opponentPriority = effectivePriority(state, BattleSide.OPPONENT, opponentAction)
        if (allyPriority != opponentPriority) {
            return if (allyPriority > opponentPriority) {
                listOf(listOf(BattleSide.ALLY, BattleSide.OPPONENT))
            } else {
                listOf(listOf(BattleSide.OPPONENT, BattleSide.ALLY))
            }
        }
        return when (LocalLookaheadStateEvaluator.speedRelation(state)) {
            LocalPublicSpeedRelation.ALLY_FIRST -> listOf(listOf(BattleSide.ALLY, BattleSide.OPPONENT))
            LocalPublicSpeedRelation.OPPONENT_FIRST -> listOf(listOf(BattleSide.OPPONENT, BattleSide.ALLY))
            LocalPublicSpeedRelation.AMBIGUOUS,
            LocalPublicSpeedRelation.UNAVAILABLE,
            -> listOf(
                listOf(BattleSide.ALLY, BattleSide.OPPONENT),
                listOf(BattleSide.OPPONENT, BattleSide.ALLY),
            )
        }
    }

    private fun effectivePriority(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
    ): Int {
        val base = action.moveDetails?.priority ?: 0
        val actor = state.pokemon.singleOrNull {
            it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
        }
        val prankster = canonicalId(actor?.knownAbilityId) == "prankster" &&
            action.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS
        return base + if (prankster) 1 else 0
    }

    private fun canonicalId(value: String?): String? = value
        ?.substringAfter(':')
        ?.lowercase()
        ?.filter(Char::isLetterOrDigit)

    private fun actionContext(
        state: BattleStateView,
        action: BattleActionCandidate,
        source: BattleDecisionContext,
    ): BattleDecisionContext {
        return BattleDecisionContext(
            requestId = source.requestId,
            state = state,
            candidates = listOf(action),
            deadlineEpochMillis = source.deadlineEpochMillis,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = source.publicActionCatalog,
        )
    }

    private fun incrementTurn(state: BattleStateView): BattleStateView = state.copyState(turn = state.turn + 1)

    private fun fingerprint(state: BattleStateView): String = buildString {
        state.pokemon.sortedBy { it.battlePokemonId }.forEach {
            append(it.battlePokemonId).append(':').append(it.side).append(':').append(it.activeSlot).append(':')
            append((it.hpFraction * 10_000).roundToInt()).append(':').append(it.statusId).append(':')
            append(it.formId).append(':').append(it.knownHeldItemId).append(':')
            append(it.statStages).append('|')
        }
        BattleSide.entries.forEach { side ->
            state.field.sideConditions.getValue(side).sortedBy { it.effectId }.forEach { effect ->
                append(side).append(':').append(effect.effectId).append(':')
                append(effect.remainingTurns).append(':').append(effect.stacks).append('|')
            }
        }
    }

    private data class WeightedState(
        val state: BattleStateView,
        val probability: Double,
        val executedSides: Set<BattleSide> = emptySet(),
        val protectedSides: Set<BattleSide> = emptySet(),
        val controlEffects: List<RecursiveControlEffect> = emptyList(),
        val switchedSides: Set<BattleSide> = emptySet(),
        val protectionAttackDrops: Map<BattleSide, Int> = emptyMap(),
        val tauntedPokemonIds: Set<UUID> = emptySet(),
        val forcedMoveIdsByPokemon: Map<UUID, String> = emptyMap(),
        val lastMoveByPokemon: Map<UUID, String> = emptyMap(),
        val executedMoveIdsByPokemon: Map<UUID, String> = emptyMap(),
    )

    private const val CERTAIN_PROBABILITY = 0.999_999
    private const val MAX_CHANCE_BRANCHES_PER_MOVE = 64
    private const val FULL_PARALYSIS_PROBABILITY = 0.25
    private val PARALYSIS_IDS = setOf("par", "paralysis", "paralyzed", "paralysed")
    private val PROJECTED_EFFECT_KINDS = setOf(
        BattleMoveEffectKind.STATUS,
        BattleMoveEffectKind.STAT_STAGE,
        BattleMoveEffectKind.SIDE_CONDITION,
        BattleMoveEffectKind.VOLATILE_STATUS,
    )
    private val PROJECTED_EFFECT_TARGETS = setOf(
        BattleMoveEffectTarget.USER,
        BattleMoveEffectTarget.SELECTED_TARGET,
        BattleMoveEffectTarget.USER_SIDE,
        BattleMoveEffectTarget.TARGET_SIDE,
    )
    private val SCRIPTED_TARGET_TRAP_MOVES = setOf(
        "anchorshot",
        "block",
        "meanlook",
        "octolock",
        "spiderweb",
        "spiritshackle",
        "thousandwaves",
    )
    private val ABILITY_IGNORING_ABILITIES = setOf("moldbreaker", "teravolt", "turboblaze")
}

private fun BattleActionCandidate.withoutFacts() = BattleActionCandidate(
    actionId = actionId,
    kind = kind,
    actorSlot = actorSlot,
    moveSlot = moveSlot,
    moveId = moveId,
    targets = targets,
    switchPokemonId = switchPokemonId,
    componentActionIds = componentActionIds,
    componentActions = componentActions,
    mechanic = mechanic,
    moveDetails = moveDetails,
    facts = null,
    tags = tags,
)

private fun BattleStateView.copyState(
    turn: Int = this.turn,
    pokemon: List<BattlePokemonStateView> = this.pokemon,
): BattleStateView = BattleStateView(
    battleId = battleId,
    format = format,
    turn = turn,
    pokemon = pokemon,
    field = field,
    remainingPokemonBySide = BattleSide.entries.associateWith { side ->
        val previousKnownLiving = this.pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        val nextKnownLiving = pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        (this.remainingPokemonBySide.getValue(side) + nextKnownLiving - previousKnownLiving).coerceAtLeast(0)
    },
    observedEvents = observedEvents,
    inferences = inferences,
)

private fun BattlePokemonStateView.copyState(
    side: BattleSide = this.side,
    activeSlot: Int? = this.activeSlot,
    hpFraction: Double = this.hpFraction,
    statusId: String? = this.statusId,
    statStages: Map<String, Int> = this.statStages,
    fainted: Boolean = this.fainted,
    actionConstraints: BattlePokemonActionConstraintView = this.actionConstraints,
): BattlePokemonStateView = BattlePokemonStateView(
    battlePokemonId = battlePokemonId,
    side = side,
    activeSlot = activeSlot,
    speciesId = speciesId,
    formId = formId,
    level = level,
    hpFraction = hpFraction,
    statusId = statusId,
    statStages = statStages,
    knownMoveIds = knownMoveIds,
    knownAbilityId = knownAbilityId,
    knownHeldItemId = knownHeldItemId,
    fainted = fainted,
    knownTypeIds = knownTypeIds,
    combatStats = combatStats,
    knownFormStates = knownFormStates,
    actionConstraints = actionConstraints,
)
