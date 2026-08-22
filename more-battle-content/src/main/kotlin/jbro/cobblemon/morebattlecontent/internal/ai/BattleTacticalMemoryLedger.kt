package jbro.cobblemon.morebattlecontent.internal.ai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionAdvice
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanUpdateOperation
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanOwner
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanAbortCondition
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnockoutAssessment
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
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
    private var activePlanOwner: BattlePlanOwner? = null
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
    private var patternExposureCount: Int = 0
    private var patternResponseShiftEvidence: Double = 0.0
    private var opponentResponseVolatility: Double = 0.0
    private var nonProgressControlStreak: Int = 0
    private val lastOpponentResponseBySlot = linkedMapOf<Int?, BattlePredictedResponse>()
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
        resolveControlProgress(state, newEvents, observation)
        val opponentById = state.pokemon.filter { it.side == BattleSide.OPPONENT }
            .associateBy { it.battlePokemonId }
        val opponentFaintedThisWindow = newEvents.any { event ->
            event.kind == BattleObservedEventKind.FAINTED &&
                (event.actorPokemonId in opponentById || event.targetPokemonIds.any(opponentById::containsKey))
        }
        val opponentMovedThisWindow = newEvents.any { event ->
            event.kind == BattleObservedEventKind.MOVE_USED && event.actorPokemonId in opponentById
        }
        val responses = newEvents.mapNotNull { event ->
            val actor = opponentById[event.actorPokemonId] ?: return@mapNotNull null
            val response = when (event.kind) {
                BattleObservedEventKind.SWITCHED -> if (
                    opponentFaintedThisWindow || observation.forcedSwitchPossible ||
                    state.format == BattleFormat.SINGLE && opponentMovedThisWindow
                ) return@mapNotNull null else BattlePredictedResponse.SWITCH
                BattleObservedEventKind.MOVE_USED -> BattlePredictedResponse.MOVE
                else -> return@mapNotNull null
            }
            ObservedOpponentResponse(actor.activeSlot, response)
        }.distinctBy { it.actorSlot }
        if (responses.isEmpty()) {
            if (opponentFaintedThisWindow || observation.forcedSwitchPossible) {
                pendingObservation = null
                pendingPrediction = null
            }
            return
        }

        val comparableResponses = if (state.format == BattleFormat.SINGLE) responses.mapNotNull { response ->
            lastOpponentResponseBySlot[response.actorSlot]?.let { previous -> previous to response }
        } else emptyList()
        val responseShifted = comparableResponses.any { (previous, response) -> previous != response.response }
        if (comparableResponses.isNotEmpty()) {
            opponentResponseVolatility = if (responseShifted) {
                (opponentResponseVolatility * BEHAVIOR_SHIFT_RETENTION + BEHAVIOR_SHIFT_GAIN).coerceAtMost(1.0)
            } else {
                opponentResponseVolatility * BEHAVIOR_STABILITY_DECAY
            }
            patternResponseShiftEvidence = if (observation.patternExposed) {
                if (responseShifted) {
                    (patternResponseShiftEvidence * ADAPTATION_RETENTION + ADAPTATION_GAIN).coerceAtMost(1.0)
                } else {
                    patternResponseShiftEvidence * NO_ADAPTATION_DECAY
                }
            } else {
                patternResponseShiftEvidence * PASSIVE_ADAPTATION_DECAY
            }
        }
        if (state.format == BattleFormat.SINGLE) {
            responses.forEach { response ->
                recordTendency(observation.situations, response.response)
                lastOpponentResponseBySlot[response.actorSlot] = response.response
            }
        }
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
    fun accept(
        state: BattleStateView,
        candidate: BattleActionCandidate,
        advice: BattleDecisionAdvice?,
        planOwner: BattlePlanOwner? = null,
    ) {
        require(state.battleId == openContext.battleId) { "Tactical memory cannot accept another battle" }
        val turn = state.turn
        require(turn >= 0)
        if (candidate.containsSwitch()) {
            switchesThisBattle += 1
            lastSwitchTurn = turn
        }
        val selectedMoveId = candidate.selectedMoveId()
        if (selectedMoveId == null) {
            lastMoveId = null
            sameMoveRepeatCount = 0
            patternExposureCount = 0
        } else if (selectedMoveId == lastMoveId) {
            sameMoveRepeatCount += 1
        } else {
            lastMoveId = selectedMoveId
            sameMoveRepeatCount = 1
        }
        patternExposureCount = sameMoveRepeatCount.takeIf { it >= MINIMUM_PATTERN_EXPOSURE } ?: 0
        pendingObservation = PendingObservation(
            turn = turn,
            situations = classifySituations(state, candidate),
            patternExposed = patternExposureCount >= MINIMUM_PATTERN_EXPOSURE,
            forcedSwitchPossible = candidate.atomicActions().any { action ->
                action.moveDetails?.effects?.effects?.any { it.kind == BattleMoveEffectKind.SWITCH_TARGET } == true
            },
            controlOnly = candidate.isControlOnlyAction(),
            canCreateFieldProgress = candidate.canCreateFieldProgress(),
            progressSnapshot = PublicProgressSnapshot.capture(state),
        )
        if (planOwner != null && activePlanOwner != null && planOwner != activePlanOwner) clearPlan()
        advice ?: return
        when (advice.planUpdate.operation) {
            BattlePlanUpdateOperation.KEEP -> Unit
            BattlePlanUpdateOperation.REPLACE -> {
                activePlan = requireNotNull(advice.planUpdate.plan)
                activePlanOwner = planOwner
                planAcceptedSequence = lastObservedSequence
            }
            BattlePlanUpdateOperation.CLEAR -> {
                clearPlan()
                planAcceptedSequence = lastObservedSequence
            }
        }
        pendingPrediction = advice.prediction?.takeUnless { it.response == BattlePredictedResponse.UNKNOWN }?.let {
            PendingPrediction(turn, it)
        }
    }

    @Synchronized
    fun view(turn: Int): BattleTacticalMemoryView {
        if (activePlan?.expiresAtTurn?.let { turn > it } == true) clearPlan()
        return BattleTacticalMemoryView(
            activePlan = activePlan,
            activePlanOwner = activePlanOwner,
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
            patternExposureCount = patternExposureCount,
            patternResponseShiftEvidence = patternResponseShiftEvidence,
            opponentResponseVolatility = opponentResponseVolatility,
            nonProgressControlStreak = nonProgressControlStreak,
        )
    }

    private fun resolveControlProgress(
        state: BattleStateView,
        newEvents: List<jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView>,
        observation: PendingObservation,
    ) {
        if (!observation.controlOnly) {
            nonProgressControlStreak = 0
            return
        }
        val before = observation.progressSnapshot
        val after = PublicProgressSnapshot.capture(state)
        val fieldChanged = observation.canCreateFieldProgress &&
            newEvents.any { it.kind == BattleObservedEventKind.FIELD_EFFECT_CHANGED }
        val materialProgress = fieldChanged ||
            after.opponentHpTotal < before.opponentHpTotal - MINIMUM_PROGRESS_DELTA ||
            after.allyHpTotal > before.allyHpTotal + MINIMUM_PROGRESS_DELTA ||
            after.allyPositiveStages > before.allyPositiveStages ||
            after.opponentNegativeStages > before.opponentNegativeStages ||
            after.allyStatusCount < before.allyStatusCount ||
            after.opponentStatusCount > before.opponentStatusCount ||
            after.opponentFaintedCount > before.opponentFaintedCount
        nonProgressControlStreak = if (materialProgress) {
            0
        } else {
            (nonProgressControlStreak + 1).coerceAtMost(MAX_NON_PROGRESS_STREAK)
        }
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

    private fun classifySituations(
        state: BattleStateView,
        candidate: BattleActionCandidate,
    ): Set<BattleSituation> = buildSet {
        add(BattleSituation.GENERAL)
        val activeOpponents = state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted
        }
        val opponentHp = activeOpponents.minOfOrNull { it.hpFraction }
        if (opponentHp != null && opponentHp <= LOW_HP_THRESHOLD) add(BattleSituation.LOW_HP)
        if (activeOpponents.any { opponent -> opponent.statStages.values.any { it > 0 } }) {
            add(BattleSituation.AFTER_SETUP)
        }
        val atomic = candidate.atomicActions()
        val threatensKnockout = atomic.any { action ->
            val facts = action.facts ?: return@any false
            val rollFloor = facts.standardDamageRollKoProbabilityRange?.minimum
                ?: if (facts.standardKnockoutAssessment == BattleKnockoutAssessment.GUARANTEED) 1.0 else 0.0
            val accuracy = facts.baseAccuracyProbability
                ?: action.moveDetails?.accuracy?.div(100.0)
                ?: 1.0
            rollFloor * accuracy >= MINIMUM_CREDIBLE_KO_PROBABILITY
        }
        if (threatensKnockout) add(BattleSituation.UNDER_KO_THREAT)
        if (atomic.any { (it.facts?.actsFirstProbability ?: 0.0) >= FASTER_PROBABILITY_THRESHOLD }) {
            add(BattleSituation.FASTER)
        }
        if (atomic.any { it.mechanic != null }) add(BattleSituation.MECHANIC_AVAILABLE)
        val focusedTargets = atomic.flatMap { it.targets }.filter { it.side == BattleSide.OPPONENT }
        if (focusedTargets.size >= 2 && focusedTargets.map { it.slot }.distinct().size == 1) {
            add(BattleSituation.DOUBLE_FOCUS_TARGET)
        }
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
        val winPathChanged = BattlePlanAbortCondition.WIN_PATH_CHANGED in plan.abortIf && newEvents.any {
            it.sequence > planAcceptedSequence && it.kind == BattleObservedEventKind.FAINTED
        }
        if (activeCritical || boardChanged || targetUnavailable || winPathChanged) clearPlan()
    }

    private fun clearPlan() {
        activePlan = null
        activePlanOwner = null
    }

    private fun canonicalId(value: String): String = value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)

    private fun BattleActionCandidate.containsSwitch(): Boolean =
        kind == BattleActionKind.SWITCH || componentActions.any { it.containsSwitch() }

    private fun BattleActionCandidate.selectedMoveId(): String? =
        moveId ?: componentActions.firstNotNullOfOrNull { it.selectedMoveId() }

    private fun BattleActionCandidate.atomicActions(): List<BattleActionCandidate> =
        componentActions.ifEmpty { listOf(this) }.flatMap { action ->
            if (action.componentActions.isEmpty()) listOf(action) else action.atomicActions()
        }

    private fun BattleActionCandidate.isControlOnlyAction(): Boolean {
        val atomic = atomicActions()
        val moves = atomic.filter { it.kind == BattleActionKind.USE_MOVE }
        if (moves.isEmpty() || atomic.any { it.kind == BattleActionKind.SWITCH }) return false
        return moves.all { action ->
            action.moveDetails?.damageCategory == jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory.STATUS ||
                action.moveDetails == null && action.facts?.standardDamageFractionRange == null
        }
    }

    private fun BattleActionCandidate.canCreateFieldProgress(): Boolean = atomicActions().any { action ->
        action.moveDetails?.effects?.effects?.any { effect -> effect.kind in FIELD_PROGRESS_EFFECTS } == true
    }

    private data class PendingPrediction(
        val turn: Int,
        val prediction: BattlePrediction,
    )
    private data class ObservedOpponentResponse(
        val actorSlot: Int?,
        val response: BattlePredictedResponse,
    )
    private data class PendingObservation(
        val turn: Int,
        val situations: Set<BattleSituation>,
        val patternExposed: Boolean,
        val forcedSwitchPossible: Boolean,
        val controlOnly: Boolean,
        val canCreateFieldProgress: Boolean,
        val progressSnapshot: PublicProgressSnapshot,
    )
    private data class PublicProgressSnapshot(
        val allyHpTotal: Double,
        val opponentHpTotal: Double,
        val allyPositiveStages: Int,
        val opponentNegativeStages: Int,
        val allyStatusCount: Int,
        val opponentStatusCount: Int,
        val opponentFaintedCount: Int,
    ) {
        companion object {
            fun capture(state: BattleStateView): PublicProgressSnapshot {
                val allies = state.pokemon.filter { it.side == BattleSide.ALLY }
                val opponents = state.pokemon.filter { it.side == BattleSide.OPPONENT }
                return PublicProgressSnapshot(
                    allyHpTotal = allies.sumOf { it.hpFraction },
                    opponentHpTotal = opponents.sumOf { it.hpFraction },
                    allyPositiveStages = allies.sumOf { pokemon ->
                        pokemon.statStages.values.sumOf { it.coerceAtLeast(0) }
                    },
                    opponentNegativeStages = opponents.sumOf { pokemon ->
                        pokemon.statStages.values.sumOf { (-it).coerceAtLeast(0) }
                    },
                    allyStatusCount = allies.count { it.statusId != null },
                    opponentStatusCount = opponents.count { it.statusId != null },
                    opponentFaintedCount = opponents.count { it.fainted },
                )
            }
        }
    }
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
        const val MINIMUM_PATTERN_EXPOSURE = 2
        const val FASTER_PROBABILITY_THRESHOLD = 0.75
        const val MINIMUM_CREDIBLE_KO_PROBABILITY = 0.50
        const val BEHAVIOR_SHIFT_RETENTION = 0.65
        const val BEHAVIOR_SHIFT_GAIN = 0.35
        const val BEHAVIOR_STABILITY_DECAY = 0.70
        const val ADAPTATION_RETENTION = 0.75
        const val ADAPTATION_GAIN = 0.40
        const val NO_ADAPTATION_DECAY = 0.70
        const val PASSIVE_ADAPTATION_DECAY = 0.85
        const val MINIMUM_PROGRESS_DELTA = 0.005
        const val MAX_NON_PROGRESS_STREAK = 99
        val TRACKED_RESPONSES = listOf(BattlePredictedResponse.MOVE, BattlePredictedResponse.SWITCH)
        val BOARD_CHANGE_EVENTS = setOf(BattleObservedEventKind.SWITCHED, BattleObservedEventKind.FAINTED)
        val FIELD_PROGRESS_EFFECTS = setOf(
            BattleMoveEffectKind.SIDE_CONDITION,
            BattleMoveEffectKind.FIELD_CONDITION,
            BattleMoveEffectKind.WEATHER,
            BattleMoveEffectKind.TERRAIN,
        )
    }
}
