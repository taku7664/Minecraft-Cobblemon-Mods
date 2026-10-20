package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.matchup.IntentKind
import jbro.cobblemon.mcc.betterai.matchup.IntentOption
import jbro.cobblemon.mcc.betterai.matchup.LocalOpponentTendencies
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalOpponentTendenciesTest {
    private val allyId = UUID.randomUUID()
    private val opponentId = UUID.randomUUID()
    private val benchId = UUID.randomUUID()

    @Test
    fun `an opponent that switched four times in five leans the intent to its record`() {
        val leaned = LocalOpponentTendencies.apply(listOf(intent()), context(samples = 8, switchRate = 0.8), 1.0).single()
        assertEquals(0.8, switchShare(leaned), 1e-9)
        // The moves keep their split among themselves.
        val moves = leaned.options.filter { it.kind != IntentKind.SWITCH }
        assertEquals(0.75, moves.first { it.moveId == "tackle" }.probability / moves.sumOf { it.probability }, 1e-9)
    }

    @Test
    fun `a short record leans the intent by its evidence only`() {
        // Three answers: a recent weight of 3 of the 8 that saturate the evidence.
        val leaned = LocalOpponentTendencies.apply(listOf(intent()), context(samples = 3, switchRate = 0.8), 1.0).single()
        assertEquals(0.2 + 3.0 / 8.0 * (0.8 - 0.2), switchShare(leaned), 1e-9)
    }

    @Test
    fun `the weight multiplies the record's pull and may carry the chance past the record`() {
        // Pull 1.2 of the gap 0.6 from 0.2: 0.92, past the recorded 0.8.
        val leaned = LocalOpponentTendencies.apply(listOf(intent()), context(samples = 8, switchRate = 0.8), 1.2).single()
        assertEquals(0.92, switchShare(leaned), 1e-9)
        // Never past certainty.
        val capped = LocalOpponentTendencies.apply(listOf(intent()), context(samples = 8, switchRate = 0.8), 5.0).single()
        assertEquals(1.0, switchShare(capped), 1e-9)
    }

    @Test
    fun `no record, no switch offered or a zero weight leave the intent as it is`() {
        val intent = intent()
        assertEquals(intent, LocalOpponentTendencies.apply(listOf(intent), context(samples = 2, switchRate = 0.8), 1.0).single())
        assertEquals(intent, LocalOpponentTendencies.apply(listOf(intent), context(samples = 8, switchRate = 0.8), 0.0).single())
        val locked = intent.copy(options = intent.options.filter { it.kind != IntentKind.SWITCH })
        assertEquals(locked, LocalOpponentTendencies.apply(listOf(locked), context(samples = 8, switchRate = 0.8), 1.0).single())
    }

    private fun switchShare(intent: OpponentIntent) = intent.options.filter { it.kind == IntentKind.SWITCH }.sumOf { it.probability }

    private fun intent() = OpponentIntent(opponentId, 0, listOf(
        IntentOption(IntentKind.ATTACK, "tackle", allyId, null, 0.5, 0.6),
        IntentOption(IntentKind.ATTACK, "bite", allyId, null, 0.3, 0.2),
        IntentOption(IntentKind.SWITCH, null, null, benchId, 0.1, 0.2),
    ))

    private fun context(samples: Int, switchRate: Double): BattleDecisionContext {
        val moveRate = 1.0 - switchRate
        val memory = BattleTacticalMemoryView(tendencies = listOf(
            BattleTendencyView(BattleSituation.GENERAL, BattlePredictedResponse.MOVE, samples, moveRate * samples, moveRate),
            BattleTendencyView(BattleSituation.GENERAL, BattlePredictedResponse.SWITCH, samples, switchRate * samples, switchRate),
        ))
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(),
                format = BattleFormat.SINGLE,
                turn = 3,
                pokemon = listOf(pokemon(allyId, BattleSide.ALLY), pokemon(opponentId, BattleSide.OPPONENT)),
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 3),
                observedEvents = emptyList(),
                inferences = emptyList(),
            ),
            candidates = listOf(BattleActionCandidate(
                actionId = "move",
                kind = BattleActionKind.USE_MOVE,
                actorSlot = 0,
                moveSlot = 0,
                moveId = "cobblemon:tackle",
                targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 35),
            )),
            deadlineEpochMillis = Long.MAX_VALUE,
        ).copy(memory = memory)
    }

    private fun pokemon(id: UUID, side: BattleSide) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
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
        knownTypeIds = setOf("normal"),
    )
}
