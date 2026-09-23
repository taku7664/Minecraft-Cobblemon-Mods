package jbro.cobblemon.morebattlecontent.internal.pvp.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PvpSelectionViewStateTest {
    @Test
    fun `selection view snapshots caller owned collections`() {
        val pokemonId = UUID.randomUUID()
        val ownParty = mutableListOf(
            PvpSelectionPartySlot(pokemonId, "cobblemon:charizard", null, 50, 50),
            PvpSelectionPartySlot(UUID.randomUUID(), "cobblemon:blastoise", null, 50, 50),
            PvpSelectionPartySlot(UUID.randomUUID(), "cobblemon:venusaur", null, 50, 50),
        )
        val opponentParty = mutableListOf(
            PvpSelectionOpponentSlot("cobblemon:pikachu"),
            PvpSelectionOpponentSlot("cobblemon:raichu"),
            PvpSelectionOpponentSlot("cobblemon:eevee"),
        )
        val selected = mutableSetOf(pokemonId)
        val spectators = mutableListOf(PvpSelectionSpectator(UUID.randomUUID(), "viewer"))
        val state = PvpSelectionViewState(
            matchId = UUID.randomUUID(),
            format = PvpBattleFormat.SINGLE,
            opponentName = "opponent",
            ownParty = ownParty,
            opponentParty = opponentParty,
            selectedPokemonIds = selected,
            selectionDeadlineEpochMillis = 1L,
            waitingForOpponent = false,
            spectators = spectators,
        )

        ownParty.clear()
        opponentParty.clear()
        selected.clear()
        spectators.clear()

        assertEquals(3, state.ownParty.size)
        assertEquals(3, state.opponentParty.size)
        assertEquals(setOf(pokemonId), state.selectedPokemonIds)
        assertEquals(1, state.spectators.size)
    }
}
