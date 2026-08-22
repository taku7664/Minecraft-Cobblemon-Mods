package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionAdvice
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionReason
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMindGameIntent
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanIntent
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanOwner
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanUpdate
import jbro.cobblemon.morebattlecontent.api.ai.BattlePlanView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePredictedResponse
import jbro.cobblemon.morebattlecontent.api.ai.BattlePrediction
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTeamRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleSituation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BattleTacticalMemoryLedgerTest {
    private val battleId = UUID.randomUUID()
    private val opponentId = UUID.randomUUID()
    private val opponentRightId = UUID.randomUUID()
    private val allyId = UUID.randomUUID()

    @Test
    fun `accepted on time advice becomes the shared plan and prediction`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        val before = state(turn = 4)
        ledger.observe(before)
        ledger.accept(
            state = before,
            candidate = BattleActionCandidate("move", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0),
            advice = advice(BattlePredictedResponse.SWITCH),
        )

        val view = ledger.view(turn = 5)

        assertEquals(BattlePlanIntent.CREATE_SAFE_ENTRY, view.activePlan?.intent)
        assertEquals(0, view.predictionCalibration.samples)
    }

    @Test
    fun `an accepted decision from another brain cannot leave a foreign plan behind`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        val before = state(turn = 1)
        ledger.observe(before)
        ledger.accept(
            state = before,
            candidate = BattleActionCandidate("local", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0),
            advice = advice(BattlePredictedResponse.MOVE),
            planOwner = BattlePlanOwner.LOCAL_BRAIN,
        )

        ledger.accept(
            state = state(turn = 2),
            candidate = BattleActionCandidate("primary", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0),
            advice = null,
            planOwner = BattlePlanOwner.PRIMARY_BRAIN,
        )

        assertNull(ledger.view(2).activePlan)
        assertNull(ledger.view(2).activePlanOwner)
    }

    @Test
    fun `different control moves without board progress accumulate one shared repetition streak`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        val first = state(turn = 1, allyHp = 0.5)
        ledger.observe(first)
        ledger.accept(
            state = first,
            candidate = BattleActionCandidate(
                "recover",
                BattleActionKind.USE_MOVE,
                actorSlot = 0,
                moveSlot = 0,
                moveId = "cobblemon:recover",
            ),
            advice = null,
        )
        val second = state(turn = 2, allyHp = 0.5)
        ledger.observe(second)
        ledger.accept(
            state = second,
            candidate = BattleActionCandidate(
                "protect",
                BattleActionKind.USE_MOVE,
                actorSlot = 0,
                moveSlot = 1,
                moveId = "cobblemon:protect",
            ),
            advice = null,
        )

        ledger.observe(state(turn = 3, allyHp = 0.5))

        assertEquals(2, ledger.view(3).nonProgressControlStreak)
    }

    @Test
    fun `next public opponent switch scores the committed prediction as a hit`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        ledger.observe(state(turn = 4))
        ledger.accept(
            state = state(turn = 4),
            candidate = BattleActionCandidate("move", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0),
            advice = advice(BattlePredictedResponse.SWITCH),
        )

        ledger.observe(
            state(
                turn = 5,
                events = listOf(BattleObservedEventView(1, 5, BattleObservedEventKind.SWITCHED, opponentId)),
            ),
        )

        val view = ledger.view(turn = 5)
        assertEquals(1, view.predictionCalibration.samples)
        assertEquals(1, view.predictionCalibration.hits)
        assertEquals(0, view.predictionCalibration.consecutiveMisses)
        assertEquals(0.09, view.predictionCalibration.topResponseBrierScore!!, 0.000_001)
        assertEquals(1.0, view.predictionCalibration.alwaysMoveBrierScore)
        assertEquals(0.91, view.predictionCalibration.brierSkillScoreAgainstAlwaysMove!!, 0.000_001)
        val generalSwitch = view.tendencies.single {
            it.situation == BattleSituation.GENERAL && it.response == BattlePredictedResponse.SWITCH
        }
        assertEquals(1, generalSwitch.samples)
        assertEquals(2.0 / 3.0, generalSwitch.estimatedRate, 0.0001)
    }

    @Test
    fun `a public move instead of the predicted switch records one miss and expires a stale plan`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        ledger.observe(state(turn = 4))
        ledger.accept(
            state = state(turn = 4),
            candidate = BattleActionCandidate("move", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0),
            advice = advice(BattlePredictedResponse.SWITCH, expiresAtTurn = 4),
        )

        ledger.observe(
            state(
                turn = 5,
                events = listOf(
                    BattleObservedEventView(
                        sequence = 1,
                        turn = 5,
                        kind = BattleObservedEventKind.MOVE_USED,
                        actorPokemonId = opponentId,
                        publicValueId = "cobblemon:tackle",
                    ),
                ),
            ),
        )

        val view = ledger.view(turn = 5)
        assertEquals(1, view.predictionCalibration.samples)
        assertEquals(0, view.predictionCalibration.hits)
        assertEquals(1, view.predictionCalibration.consecutiveMisses)
        assertEquals(0.49, view.predictionCalibration.topResponseBrierScore!!, 0.000_001)
        assertEquals(0.0, view.predictionCalibration.alwaysMoveBrierScore)
        assertNull(view.predictionCalibration.brierSkillScoreAgainstAlwaysMove)
        assertNull(view.activePlan)
    }

    @Test
    fun `opponent tendency is observed even when the submitted local action carried no prediction`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        val before = state(turn = 1)
        ledger.observe(before)
        ledger.accept(
            state = before,
            candidate = BattleActionCandidate("move", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0),
            advice = null,
        )

        ledger.observe(
            state(
                turn = 2,
                events = listOf(
                    BattleObservedEventView(
                        1,
                        2,
                        BattleObservedEventKind.MOVE_USED,
                        opponentId,
                        publicValueId = "cobblemon:tackle",
                    ),
                ),
            ),
        )

        val view = ledger.view(2)
        assertEquals(0, view.predictionCalibration.samples)
        assertEquals(1, view.tendencies.first { it.response == BattlePredictedResponse.MOVE }.samples)
    }

    @Test
    fun `repetition becomes a mind game signal only after the opponent changes response`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        val repeatedMove = BattleActionCandidate(
            "repeat",
            BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:shadowball",
        )
        ledger.observe(state(turn = 1))
        ledger.accept(state(turn = 1), repeatedMove, advice = null)
        ledger.observe(
            state(
                turn = 2,
                events = listOf(
                    BattleObservedEventView(
                        1,
                        2,
                        BattleObservedEventKind.MOVE_USED,
                        opponentId,
                        publicValueId = "cobblemon:tackle",
                    ),
                ),
            ),
        )
        ledger.accept(state(turn = 2), repeatedMove, advice = null)

        val exposed = ledger.view(2)
        assertEquals(2, exposed.patternExposureCount)
        assertEquals(0.0, exposed.patternResponseShiftEvidence)

        ledger.observe(
            state(
                turn = 3,
                events = listOf(BattleObservedEventView(2, 3, BattleObservedEventKind.SWITCHED, opponentId)),
            ),
        )

        val adapted = ledger.view(3)
        assertEquals(true, adapted.patternResponseShiftEvidence > 0.0)
        assertEquals(true, adapted.opponentResponseVolatility > 0.0)
    }

    @Test
    fun `forced replacement after a faint is not learned as adaptation`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        val repeatedMove = BattleActionCandidate(
            "repeat",
            BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:shadowball",
        )
        ledger.observe(state(turn = 1))
        ledger.accept(state(turn = 1), repeatedMove, advice = null)
        ledger.observe(
            state(
                turn = 2,
                events = listOf(
                    BattleObservedEventView(
                        1,
                        2,
                        BattleObservedEventKind.MOVE_USED,
                        opponentId,
                        publicValueId = "cobblemon:tackle",
                    ),
                ),
            ),
        )
        ledger.accept(state(turn = 2), repeatedMove, advice = null)
        ledger.observe(
            state(
                turn = 3,
                events = listOf(
                    BattleObservedEventView(2, 3, BattleObservedEventKind.FAINTED, opponentId),
                    BattleObservedEventView(3, 3, BattleObservedEventKind.SWITCHED, opponentId),
                ),
            ),
        )

        assertEquals(0.0, ledger.view(3).patternResponseShiftEvidence)
    }

    @Test
    fun `pivot move followed by its switch is learned as one move response`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        ledger.observe(state(turn = 1))
        ledger.accept(
            state(turn = 1),
            BattleActionCandidate("wait", BattleActionKind.WAIT),
            advice = null,
        )
        ledger.observe(
            state(
                turn = 2,
                events = listOf(
                    BattleObservedEventView(
                        1,
                        2,
                        BattleObservedEventKind.MOVE_USED,
                        opponentId,
                        publicValueId = "cobblemon:voltswitch",
                    ),
                    BattleObservedEventView(2, 2, BattleObservedEventKind.SWITCHED, opponentId),
                ),
            ),
        )

        val view = ledger.view(2)
        assertEquals(1, view.tendencies.first { it.response == BattlePredictedResponse.MOVE }.samples)
        assertEquals(1.0 / 3.0, view.tendencies.first { it.response == BattlePredictedResponse.SWITCH }.estimatedRate, 0.0001)
    }

    @Test
    fun `double prediction is scored against its declared opponent slot rather than the first event`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.DOUBLE))
        val before = doubleState(turn = 4)
        ledger.observe(before)
        ledger.accept(
            state = before,
            candidate = BattleActionCandidate("turn:test", BattleActionKind.WAIT),
            advice = BattleDecisionAdvice(
                prediction = BattlePrediction(BattlePredictedResponse.SWITCH, 0.7, actorSlot = 1),
            ),
        )

        ledger.observe(
            doubleState(
                turn = 5,
                events = listOf(
                    BattleObservedEventView(
                        sequence = 1,
                        turn = 5,
                        kind = BattleObservedEventKind.MOVE_USED,
                        actorPokemonId = opponentId,
                        publicValueId = "cobblemon:tackle",
                    ),
                    BattleObservedEventView(
                        sequence = 2,
                        turn = 5,
                        kind = BattleObservedEventKind.SWITCHED,
                        actorPokemonId = opponentRightId,
                    ),
                ),
            ),
        )

        val calibration = ledger.view(5).predictionCalibration
        assertEquals(1, calibration.samples)
        assertEquals(1, calibration.hits)
    }

    @Test
    fun `missing declared double slot response cannot borrow another slots matching action`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.DOUBLE))
        val before = doubleState(turn = 4)
        ledger.observe(before)
        ledger.accept(
            state = before,
            candidate = BattleActionCandidate("wait", BattleActionKind.WAIT),
            advice = BattleDecisionAdvice(
                prediction = BattlePrediction(BattlePredictedResponse.MOVE, 0.7, actorSlot = 1),
            ),
        )

        ledger.observe(
            doubleState(
                turn = 5,
                events = listOf(
                    BattleObservedEventView(
                        sequence = 1,
                        turn = 5,
                        kind = BattleObservedEventKind.MOVE_USED,
                        actorPokemonId = opponentId,
                        publicValueId = "cobblemon:tackle",
                    ),
                ),
            ),
        )

        val calibration = ledger.view(5).predictionCalibration
        assertEquals(1, calibration.samples)
        assertEquals(0, calibration.hits)
        assertEquals(1, calibration.consecutiveMisses)
    }

    @Test
    fun `memory refuses a snapshot from another battle even before the first public event`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))

        assertThrows(IllegalArgumentException::class.java) {
            ledger.observe(state(turn = 1, stateBattleId = UUID.randomUUID()))
        }
    }

    @Test
    fun `plan abort condition clears the plan when the public active falls below critical hp`() {
        val ledger = BattleTacticalMemoryLedger(BattleBrainOpenContext(battleId, BattleFormat.SINGLE))
        val before = state(turn = 3, allyHp = 0.8)
        ledger.observe(before)
        ledger.accept(
            state = before,
            candidate = BattleActionCandidate("move", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0),
            advice = BattleDecisionAdvice(
                planUpdate = BattlePlanUpdate.replace(
                    BattlePlanView(
                        BattlePlanIntent.PRESERVE_CORE,
                        expiresAtTurn = 8,
                        abortIf = setOf(jbro.cobblemon.morebattlecontent.api.ai.BattlePlanAbortCondition.ACTIVE_BELOW_CRITICAL_HP),
                    ),
                ),
            ),
        )

        ledger.observe(state(turn = 4, allyHp = 0.19))

        assertNull(ledger.view(4).activePlan)
    }

    @Test
    fun `run scope carries conditioned tendencies to the next battle and can be discarded`() {
        val scopeId = UUID.randomUUID()
        val first = BattleTacticalMemoryLedger(
            BattleBrainOpenContext(battleId, BattleFormat.SINGLE, learningScopeId = scopeId),
        )
        first.observe(state(turn = 1))
        first.accept(
            state = state(turn = 1),
            candidate = BattleActionCandidate("move", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0),
            advice = advice(BattlePredictedResponse.SWITCH),
        )
        first.observe(
            state(
                turn = 2,
                events = listOf(BattleObservedEventView(1, 2, BattleObservedEventKind.SWITCHED, opponentId)),
            ),
        )
        BattleTacticalRunMemoryStore.record(scopeId, first.view(2).tendencies)

        val secondBattleId = UUID.randomUUID()
        val second = BattleTacticalMemoryLedger(
            BattleBrainOpenContext(secondBattleId, BattleFormat.SINGLE, learningScopeId = scopeId),
        )
        assertEquals(1, second.view(1).tendencies.first { it.response == BattlePredictedResponse.SWITCH }.samples)

        assertEquals(true, BattleTacticalRunMemoryStore.discard(scopeId))
        val empty = BattleTacticalMemoryLedger(
            BattleBrainOpenContext(UUID.randomUUID(), BattleFormat.SINGLE, learningScopeId = scopeId),
        )
        assertEquals(emptyList<Any>(), empty.view(1).tendencies)
    }

    private fun advice(response: BattlePredictedResponse, expiresAtTurn: Int = 8) = BattleDecisionAdvice(
        prediction = BattlePrediction(response, 0.7),
        planUpdate = BattlePlanUpdate.replace(
            BattlePlanView(BattlePlanIntent.CREATE_SAFE_ENTRY, BattleTeamRole.ACE, expiresAtTurn),
        ),
        reasonCodes = setOf(BattleDecisionReason.PRESERVE_WIN_CONDITION),
        mindGameIntent = BattleMindGameIntent.PREDICT_SWITCH,
    )

    private fun state(
        turn: Int,
        events: List<BattleObservedEventView> = emptyList(),
        stateBattleId: UUID = battleId,
        allyHp: Double? = null,
    ) = BattleStateView(
        battleId = stateBattleId,
        format = BattleFormat.SINGLE,
        turn = turn,
        pokemon = listOfNotNull(
            allyHp?.let { hp ->
                BattlePokemonStateView(
                    battlePokemonId = allyId,
                    side = BattleSide.ALLY,
                    activeSlot = 0,
                    speciesId = "showdown:ally",
                    formId = null,
                    level = 50,
                    hpFraction = hp,
                    statusId = null,
                    statStages = emptyMap(),
                    knownMoveIds = emptySet(),
                    knownAbilityId = null,
                    knownHeldItemId = null,
                    fainted = false,
                )
            },
            BattlePokemonStateView(
                battlePokemonId = opponentId,
                side = BattleSide.OPPONENT,
                activeSlot = 0,
                speciesId = "showdown:test",
                formId = null,
                level = 50,
                hpFraction = 1.0,
                statusId = null,
                statStages = emptyMap(),
                knownMoveIds = emptySet(),
                knownAbilityId = null,
                knownHeldItemId = null,
                fainted = false,
            ),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 0 },
        observedEvents = events,
        inferences = emptyList(),
    )

    private fun doubleState(
        turn: Int,
        events: List<BattleObservedEventView> = emptyList(),
    ) = BattleStateView(
        battleId = battleId,
        format = BattleFormat.DOUBLE,
        turn = turn,
        pokemon = listOf(
            BattlePokemonStateView(
                battlePokemonId = opponentId,
                side = BattleSide.OPPONENT,
                activeSlot = 0,
                speciesId = "showdown:left",
                formId = null,
                level = 50,
                hpFraction = 1.0,
                statusId = null,
                statStages = emptyMap(),
                knownMoveIds = emptySet(),
                knownAbilityId = null,
                knownHeldItemId = null,
                fainted = false,
            ),
            BattlePokemonStateView(
                battlePokemonId = opponentRightId,
                side = BattleSide.OPPONENT,
                activeSlot = 1,
                speciesId = "showdown:right",
                formId = null,
                level = 50,
                hpFraction = 1.0,
                statusId = null,
                statStages = emptyMap(),
                knownMoveIds = emptySet(),
                knownAbilityId = null,
                knownHeldItemId = null,
                fainted = false,
            ),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
        observedEvents = events,
        inferences = emptyList(),
    )
}
