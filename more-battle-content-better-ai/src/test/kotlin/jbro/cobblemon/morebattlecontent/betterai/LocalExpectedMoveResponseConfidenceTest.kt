package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.search.LocalExpectedMoveResponseConfidence
import jbro.cobblemon.morebattlecontent.betterai.search.LocalOpponentResponseValue
import jbro.cobblemon.morebattlecontent.betterai.search.LocalResponseValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalExpectedMoveResponseConfidenceTest {
    @Test
    fun `best expected response is unpenalized and lower responses blend their effect`() {
        val values = listOf(
            response("unknown", -10.0, setOf("unknown_public_response")),
            response("best", -100.0, setOf("expected_opponent_move")),
            response("second", -50.0, setOf("expected_opponent_move")),
            response("confirmed", -60.0, setOf("confirmed_opponent_move")),
        )
        val baseline = LocalExpectedMoveResponseConfidence.noResponseBaseline(values, reserve = 0.20)!!
        val adjusted = LocalExpectedMoveResponseConfidence.adjust(values, baseline, 0.80, 1.0e-9)

        assertEquals(-9.8, baseline, 1.0e-9)
        assertEquals(-100.0, adjusted.single { it.action.actionId == "best" }.value.value, 1.0e-9)
        assertEquals(-41.96, adjusted.single { it.action.actionId == "second" }.value.value, 1.0e-9)
        assertEquals(-60.0, adjusted.single { it.action.actionId == "confirmed" }.value.value, 1.0e-9)
    }

    @Test
    fun `tied best expected responses remain unpenalized`() {
        val values = listOf(
            response("unknown", 5.0, setOf("unknown_public_response")),
            response("first", -20.0, setOf("expected_opponent_move")),
            response("tied", -20.0 + 5.0e-10, setOf("expected_opponent_move")),
        )
        val adjusted = LocalExpectedMoveResponseConfidence.adjust(values, 5.0, 0.80, 1.0e-9)
        assertEquals(values.map { it.value.value }, adjusted.map { it.value.value })
    }

    @Test
    fun `best expected response is discounted when a confirmed response ranks above it`() {
        val values = listOf(
            response("unknown", 0.0, setOf("unknown_public_response")),
            response("confirmed_best", -100.0, setOf("confirmed_opponent_move")),
            response("expected_second", -50.0, setOf("expected_opponent_move")),
        )

        val adjusted = LocalExpectedMoveResponseConfidence.adjust(values, 0.0, 0.80, 1.0e-9)

        assertEquals(-100.0, adjusted.single { it.action.actionId == "confirmed_best" }.value.value, 1.0e-9)
        assertEquals(-40.0, adjusted.single { it.action.actionId == "expected_second" }.value.value, 1.0e-9)
    }

    @Test
    fun `double response discounts only the expected component beyond the matching confirmed baseline`() {
        val confirmedBest = response("confirmed_best", -100.0, setOf("confirmed_opponent_move"))
        val expected = BattleActionCandidate(
            "expected_slot_zero", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "expected_move",
            tags = setOf("expected_opponent_move"),
        )
        val unknown = BattleActionCandidate(
            "unknown_slot_zero", BattleActionKind.WAIT,
            tags = setOf("unknown_public_response"),
        )
        val confirmedPartner = BattleActionCandidate(
            "confirmed_slot_one", BattleActionKind.USE_MOVE, actorSlot = 1, moveSlot = 0,
            moveId = "confirmed_move",
            tags = setOf("confirmed_opponent_move"),
        )
        val values = listOf(
            confirmedBest,
            compositeResponse("expected_and_confirmed", -80.0, expected, confirmedPartner),
            compositeResponse("unknown_and_confirmed", -20.0, unknown, confirmedPartner),
        )

        val adjusted = LocalExpectedMoveResponseConfidence.adjust(values, 0.0, 0.80, 1.0e-9)

        assertEquals(-68.0,
            adjusted.single { it.action.actionId == "expected_and_confirmed" }.value.value, 1.0e-9)
    }

    @Test
    fun `partial unknown double response cannot take first place from a concrete expected response`() {
        val expected = BattleActionCandidate(
            "expected_slot_zero", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "expected_move", tags = setOf("expected_opponent_move"),
        )
        val unknown = BattleActionCandidate(
            "unknown_slot_zero", BattleActionKind.WAIT, tags = setOf("unknown_public_response"),
        )
        val confirmedPartner = BattleActionCandidate(
            "confirmed_slot_one", BattleActionKind.USE_MOVE, actorSlot = 1, moveSlot = 0,
            moveId = "confirmed_move", tags = setOf("confirmed_opponent_move"),
        )
        val values = listOf(
            response("confirmed", -100.0, setOf("confirmed_opponent_move")),
            compositeResponse("expected_concrete", -110.0, expected, confirmedPartner),
            compositeResponse("partial_unknown", -120.0, unknown, confirmedPartner),
        )

        val adjusted = LocalExpectedMoveResponseConfidence.adjust(values, -120.0, 0.80, 1.0e-9)

        assertEquals(-110.0,
            adjusted.single { it.action.actionId == "expected_concrete" }.value.value, 1.0e-9)
    }

    private fun response(id: String, value: Double, tags: Set<String>) = LocalOpponentResponseValue(
        BattleActionCandidate(id, BattleActionKind.WAIT, tags = tags),
        LocalResponseValue(value, 1.0, 1.0),
    )

    private fun compositeResponse(
        id: String,
        value: Double,
        vararg components: BattleActionCandidate,
    ) = LocalOpponentResponseValue(
        BattleActionCandidate(
            id,
            BattleActionKind.COMPOSITE,
            componentActionIds = components.map(BattleActionCandidate::actionId),
            componentActions = components.toList(),
        ),
        LocalResponseValue(value, 1.0, 1.0),
    )
}
