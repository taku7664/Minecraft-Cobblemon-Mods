package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattlePredictedResponse
import jbro.cobblemon.morebattlecontent.api.ai.BattleSituation
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTendencyView
import jbro.cobblemon.morebattlecontent.betterai.search.NativeOpponentResponseOrdering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeOpponentResponseOrderingTest {
    private val actions = listOf(
        BattleActionCandidate("move:a", BattleActionKind.USE_MOVE, 0, moveSlot = 0, moveId = "a"),
        BattleActionCandidate("move:b", BattleActionKind.USE_MOVE, 0, moveSlot = 1, moveId = "b"),
        BattleActionCandidate("switch", BattleActionKind.SWITCH, 0,
            switchPokemonId = UUID(0, 301)),
    )

    @Test
    fun `observed response preference changes visit order without removing actions`() {
        val ordered = NativeOpponentResponseOrdering.order(actions, memory(8), 1.0)

        assertEquals(listOf("switch", "move:a", "move:b"), ordered.map { it.actionId })
        assertEquals(actions.map { it.actionId }.toSet(), ordered.map { it.actionId }.toSet())
    }

    @Test
    fun `previous completed depth takes priority over behavioral ordering`() {
        val ordered = NativeOpponentResponseOrdering.order(
            actions, memory(8), 1.0,
            mapOf("move:b" to -1.0, "switch" to 0.0, "move:a" to 1.0),
        )

        assertEquals(listOf("move:b", "switch", "move:a"), ordered.map { it.actionId })
    }

    @Test
    fun `insufficient experience preserves native request order`() {
        assertEquals(actions, NativeOpponentResponseOrdering.order(actions, memory(2), 1.0))
    }

    private fun memory(samples: Int) = BattleTacticalMemoryView(tendencies = listOf(
        BattleTendencyView(BattleSituation.GENERAL, BattlePredictedResponse.MOVE,
            samples, samples * 0.2, 0.2),
        BattleTendencyView(BattleSituation.GENERAL, BattlePredictedResponse.SWITCH,
            samples, samples * 0.8, 0.8),
    ))
}
