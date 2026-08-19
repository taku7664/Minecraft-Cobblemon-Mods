package jbro.cobblemon.morebattlecontent.internal.ai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionAdvice
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanUpdateOperation
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanAbortCondition
import jbro.cobblemon.morebattlecontent.api.ai.BattlePredictedResponse
import jbro.cobblemon.morebattlecontent.api.ai.BattlePrediction
import jbro.cobblemon.morebattlecontent.api.ai.BattlePredictionCalibrationView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTendencyView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSituation

/** Server-owned memory. It commits only metadata paired with an action that was actually submitted. */
internal class BattleTacticalMemoryLedger(
    private val openContext: BattleBrainOpenContext,
) {
    private var activePlan: BattlePlanView? = null
    private var pendingPrediction: PendingPrediction? = null
    private var pendingObservation: PendingObservation? = null
    private var planAcceptedSequence: Long = 0
    private var lastObservedSequence: Long = 0
    private var predictionSamples: Int = 0
    private var predictionHits: Int = 0
    private var consecutiveMisses: Int = 0
    private var topResponseBrierSum: Double = 0.0
    private var alwaysMoveBrierSum: Double = 0.0
    private var switchesThisBattle: Int = 0
    private var lastSwitchTurn: Int? = null
    private var lastMoveId: String? = null
    private var sameMoveRepeatCount: Int = 0
    private val tendencies = linkedMapOf<BattleSituation, MutableSituationTendency>()

    init {
        BattleTacticalRunMemoryStore.snapshot(openContext.learningScopeId)
            .groupBy(BattleTendencyView::situation)
            .forEach { (situation, views) ->
                tendencies[situation] = MutableSituationTendency(
                    samples = views.maxOfOrNull(BattleTendencyView::samples) ?: 0,
                    weights = views.associateTo(linkedMapOf()) { it.response to it.recentWeight },
                )
            }
    }

    @Synchronized
    fun observe(state: BattleStateView) {
        require(state.battleId == openContext.battleId) {
            "Tactical memory cannot observe another battle"
        }
        val newEvents = state.observedEvents.filter { it.sequence > lastObservedSequence }
        newEvents.maxOfOrNull { it.sequence }?.let { lastObservedSequence = it }
        abortPlanIfNeeded(state, newEvents)

        val observation = pendingObservation ?: return
        if (state.turn <= observation.turn) return
        val opponentById = state.pokemon.filter { it.side == BattleSide.OPPONENT }
            .associateBy { it.battlePokemonId }
        val responses = newEvents.mapNotNull { event ->
            val actor = opponentById[event.actorPokemonId] ?: return@mapNotNull null
            val response = when (event.kind) {
                BattleObservedEventKind.SWITCHED -> BattlePredictedResponse.SWITCH
                BattleObservedEventKind.MOVE_USED -> BattlePredictedResponse.MOVE
                else -> return@mapNotNull null
            }
            ObservedOpponentResponse(actor.activeSlot, response)
        }.distinctBy { it.actorSlot }
        if (responses.isEmpty()) return

        responses.forEach { response -> recordTendency(observation.situations, response.response) }
        pendingObservation = null
        pendingPrediction?.takeIf { state.turn > it.turn }?.let { pending ->
            val predictedSlot = pending.prediction.actorSlot
            val actual = if (predictedSlot == null) {
                responses.first().response
            } else {
                responses.firstOrNull { it.actorSlot == predictedSlot }?.response
                    ?: BattlePredictedResponse.OTHER
            }
            score(pending, actual)
            pendingPrediction = null
        }
    }

    @Synchronized
    fun accept(state: BattleStateView, candidate: BattleActionCandidate, advice: BattleDecisionAdvice?) {
        require(state.battleId == openContext.battleId) { "Tactical memory cannot accept another battle" }
        val turn = state.turn
        require(turn >= 0)
        pendingObservation = PendingObservation(turn, classifySituations(state))
        if (candidate.containsSwitch()) {
            switchesThisBattle += 1
            lastSwitchTurn = turn
        }
        val selectedMoveId = candidate.selectedMoveId()
        if (selectedMoveId == null) {
            lastMoveId = null
            sameMoveRepeatCount = 0
        } else if (selectedMoveId == lastMoveId) {
            sameMoveRepeatCount += 1
        } else {
            lastMoveId = selectedMoveId
            sameMoveRepeatCount = 1
        }
        advice ?: return
        when (advice.planUpdate.operation) {
            BattlePlanUpdateOperation.KEEP -> Unit
            BattlePlanUpdateOperation.REPLACE -> {
                activePlan = requireNotNull(advice.planUpdate.plan)
                planAcceptedSequence = lastObservedSequence
            }
            BattlePlanUpdateOperation.CLEAR -> {
                activePlan = null
                planAcceptedSequence = lastObservedSequence
            }
        }
        pendingPrediction = advice.prediction?.takeUnless { it.response == BattlePredictedResponse.UNKNOWN }?.let {
            PendingPrediction(turn, it)
        }
    }

    @Synchronized
    fun view(turn: Int): BattleTacticalMemoryView {
        if (activePlan?.expiresAtTurn?.let { turn > it } == true) activePlan = null
        return BattleTacticalMemoryView(
            activePlan = activePlan,
            tendencies = tendencies.flatMap { (situation, value) ->
                TRACKED_RESPONSES.map { response ->
                    val responseWeight = value.weights[response] ?: 0.0
                    BattleTendencyView(
                        situation = situation,
                        response = response,
                        samples = value.samples,
                        recentWeight = responseWeight,
                        estimatedRate = (responseWeight + 1.0) / (value.totalWeight + TRACKED_RESPONSES.size),
                    )
                }
            },
            predictionCalibration = BattlePredictionCalibrationView(
                samples = predictionSamples,
                hits = predictionHits,
                consecutiveMisses = consecutiveMisses,
                topResponseBrierScore = predictionSamples.takeIf { it > 0 }?.let {
                    topResponseBrierSum / it
                },
                alwaysMoveBrierScore = predictionSamples.takeIf { it > 0 }?.let {
                    alwaysMoveBrierSum / it
                },
            ),
            turnsSinceLastSwitch = lastSwitchTurn?.let { (turn - it).coerceAtLeast(0) },
            switchesThisBattle = switchesThisBattle,
            lastMoveId = lastMoveId,
            sameMoveRepeatCount = sameMoveRepeatCount,
        )
    }

    private fun score(pending: PendingPrediction, actual: BattlePredictedResponse) {
        predictionSamples += 1
        val hit = pending.prediction.response == actual
        val outcome = if (hit) 1.0 else 0.0
        val predictionError = pending.prediction.confidence - outcome
        topResponseBrierSum += predictionError * predictionError
        val alwaysMoveOutcome = if (actual == BattlePredictedResponse.MOVE) 1.0 else 0.0
        val baselineError = 1.0 - alwaysMoveOutcome
        alwaysMoveBrierSum += baselineError * baselineError
        if (hit) {
            predictionHits += 1
            consecutiveMisses = 0
        } else {
            consecutiveMisses += 1
        }
    }

    private fun recordTendency(situations: Set<BattleSituation>, actual: BattlePredictedResponse) {
        situations.forEach { situation ->
            val tendency = tendencies.getOrPut(situation) { MutableSituationTendency() }
            tendency.weights.replaceAll { _, weight -> weight * RECENCY_DECAY }
            tendency.samples += 1
            tendency.weights[actual] = (tendency.weights[actual] ?: 0.0) + 1.0
        }
    }

    private fun classifySituations(state: BattleStateView): Set<BattleSituation> = buildSet {
        add(BattleSituation.GENERAL)
        val opponentHp = state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted
        }.minOfOrNull { it.hpFraction }
        if (opponentHp != null && opponentHp <= LOW_HP_THRESHOLD) add(BattleSituation.LOW_HP)
    }

    private fun abortPlanIfNeeded(state: BattleStateView, newEvents: List<jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView>) {
        val plan = activePlan ?: return
        val activeAllyHp = state.pokemon.filter {
            it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted
        }.minOfOrNull { it.hpFraction }
        val activeCritical = BattlePlanAbortCondition.ACTIVE_BELOW_CRITICAL_HP in plan.abortIf &&
            activeAllyHp != null && activeAllyHp <= CRITICAL_HP_THRESHOLD
        val boardChanged = BattlePlanAbortCondition.OPPONENT_BOARD_CHANGED in plan.abortIf && newEvents.any {
            it.sequence > planAcceptedSequence && it.kind in BOARD_CHANGE_EVENTS
        }
        val targetUnavailable = BattlePlanAbortCondition.TARGET_ROLE_UNAVAILABLE in plan.abortIf &&
            plan.targetRole != null && openContext.strategy?.members?.filter { plan.targetRole in it.roles }?.none { member ->
                state.pokemon.any { pokemon ->
                    pokemon.side == BattleSide.ALLY && !pokemon.fainted && canonicalId(pokemon.speciesId) == canonicalId(member.speciesId)
                }
            } == true
        if (activeCritical || boardChanged || targetUnavailable) activePlan = null
    }

    private fun canonicalId(value: String): String = value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)

    private fun BattleActionCandidate.containsSwitch(): Boolean =
        kind == BattleActionKind.SWITCH || componentActions.any { it.containsSwitch() }

    private fun BattleActionCandidate.selectedMoveId(): String? =
        moveId ?: componentActions.firstNotNullOfOrNull { it.selectedMoveId() }

    private data class PendingPrediction(
        val turn: Int,
        val prediction: BattlePrediction,
    )
    private data class ObservedOpponentResponse(
        val actorSlot: Int?,
        val response: BattlePredictedResponse,
    )
    private data class PendingObservation(val turn: Int, val situations: Set<BattleSituation>)
    private data class MutableSituationTendency(
        var samples: Int = 0,
        val weights: MutableMap<BattlePredictedResponse, Double> = linkedMapOf(),
    ) {
        val totalWeight: Double get() = weights.values.sum()
    }

    private companion object {
        const val RECENCY_DECAY = 0.85
        const val LOW_HP_THRESHOLD = 0.35
        const val CRITICAL_HP_THRESHOLD = 0.2
        val TRACKED_RESPONSES = listOf(BattlePredictedResponse.MOVE, BattlePredictedResponse.SWITCH)
        val BOARD_CHANGE_EVENTS = setOf(BattleObservedEventKind.SWITCHED, BattleObservedEventKind.FAINTED)
    }
}
