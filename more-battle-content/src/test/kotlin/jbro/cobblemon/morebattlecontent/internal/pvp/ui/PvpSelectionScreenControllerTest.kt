package jbro.cobblemon.morebattlecontent.internal.pvp.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSelectionScreenControllerTest {
    private val matchId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val ids = (1..6).map { UUID(0, it.toLong()) }

    @Test
    fun `selection remains private locally until exact team is submitted`() {
        val sent = ArrayList<PvpSelectionIntent>()
        val controller = PvpSelectionScreenController(state(), sent::add)

        ids.take(3).forEach { assertTrue(controller.toggle(it)) }
        assertFalse(controller.toggle(ids[3]))
        assertTrue(controller.submit())

        val intent = sent.single() as PvpSelectionIntent.Submit
        assertEquals(ids.take(3), intent.pokemonIds)
        assertTrue(controller.isPending)
    }

    @Test
    fun `server rejection unlocks controls without changing local selection`() {
        val sent = ArrayList<PvpSelectionIntent>()
        val controller = PvpSelectionScreenController(state(), sent::add)
        ids.take(3).forEach(controller::toggle)
        controller.submit()
        val requestId = sent.single().requestId

        controller.applyRejected(requestId, "pvp.error")

        assertFalse(controller.isPending)
        assertEquals(ids.take(3).toSet(), controller.selectedPokemonIds)
        assertEquals("pvp.error", controller.feedbackKey)
    }

    @Test
    fun `ready player can request unready before the opponent locks`() {
        val sent = ArrayList<PvpSelectionIntent>()
        val controller = PvpSelectionScreenController(state().copy(waitingForOpponent = true), sent::add)

        assertTrue(controller.unready())
        assertTrue(sent.single() is PvpSelectionIntent.Unready)
        assertTrue(controller.isPending)
    }

    private fun state() = PvpSelectionViewState(
        matchId = matchId,
        format = PvpBattleFormat.SINGLE,
        opponentName = "Opponent",
        ownParty = ids.mapIndexed { index, id ->
            PvpSelectionPartySlot(id, "cobblemon:species_$index", null, 50, 50)
        },
        opponentParty = (1..3).map { PvpSelectionOpponentSlot("cobblemon:opponent_$it") },
        selectedPokemonIds = emptySet(),
        selectionDeadlineEpochMillis = 100_000L,
        waitingForOpponent = false,
    )
}
