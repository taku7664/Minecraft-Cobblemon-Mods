package jbro.cobblemon.morebattlecontent.internal.tower.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerPlayScreenControllerTest {
    private val pokemonId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val requestId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val entryContextId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")

    @Test
    fun `selection click sends revisioned intent without optimistic selection`() {
        val sent = mutableListOf<TowerPlayIntent>()
        val controller = TowerPlayScreenController(state(), { requestId }, sent::add)

        assertTrue(controller.toggleSelection(pokemonId))

        assertEquals(
            listOf(TowerPlayIntent.ToggleSelection(requestId, entryContextId, expectedRevision = 4, pokemonId)),
            sent,
        )
        assertEquals(emptySet<UUID>(), controller.state.selectedPokemonIds)
        assertTrue(controller.isPending)
    }

    @Test
    fun `matching accepted result replaces complete server state`() {
        val controller = TowerPlayScreenController(state(), { requestId }) {}
        controller.toggleSelection(pokemonId)
        val acceptedState = state(revision = 5, selected = setOf(pokemonId))

        controller.apply(TowerPlayMutationResult.Accepted(requestId, acceptedState))

        assertEquals(acceptedState, controller.state)
        assertFalse(controller.isPending)
        assertEquals(null, controller.feedbackKey)
    }

    @Test
    fun `rejected result keeps server selection and exposes message key`() {
        val initial = state()
        val controller = TowerPlayScreenController(initial, { requestId }) {}
        controller.toggleSelection(pokemonId)

        controller.apply(
            TowerPlayMutationResult.Rejected(
                requestId = requestId,
                currentRevision = 4,
                messageKey = "screen.cobblemon_more_battle_content.tower.error.selection_rejected",
            ),
        )

        assertEquals(initial, controller.state)
        assertFalse(controller.isPending)
        assertEquals(
            "screen.cobblemon_more_battle_content.tower.error.selection_rejected",
            controller.feedbackKey,
        )
    }

    @Test
    fun `stale or unrelated response cannot overwrite current state`() {
        val otherRequest = UUID.fromString("99999999-8888-7777-6666-555555555555")
        val controller = TowerPlayScreenController(state(), { requestId }) {}
        controller.toggleSelection(pokemonId)

        controller.apply(TowerPlayMutationResult.Accepted(otherRequest, state(revision = 10)))
        controller.apply(TowerPlayMutationResult.Accepted(requestId, state(revision = 3)))

        assertEquals(4, controller.state.revision)
        assertTrue(controller.isPending)
    }

    @Test
    fun `only one mutation may be pending`() {
        val sent = mutableListOf<TowerPlayIntent>()
        val controller = TowerPlayScreenController(state(), { requestId }, sent::add)

        assertTrue(controller.toggleSelection(pokemonId))
        assertFalse(controller.changeFormat(TowerBattleFormat.DOUBLE))
        assertEquals(1, sent.size)
    }

    @Test
    fun `view state detaches party selected ids and errors`() {
        val party = validParty().toMutableList()
        val selected = mutableSetOf(pokemonId)
        val errors = mutableListOf("error.one")
        val state = state(party = party, selected = selected, errors = errors)

        party.clear()
        selected.clear()
        errors.clear()

        assertEquals(6, state.party.size)
        assertEquals(setOf(pokemonId), state.selectedPokemonIds)
        assertEquals(listOf("error.one"), state.errorKeys)
        assertNotSame(party, state.party)
    }

    @Test
    fun `rejected result detaches field errors`() {
        val fieldErrors = mutableMapOf("selection" to "error.selection")
        val result = TowerPlayMutationResult.Rejected(requestId, 4, "error.rejected", fieldErrors)

        fieldErrors.clear()

        assertEquals(mapOf("selection" to "error.selection"), result.fieldErrors)
        assertNotSame(fieldErrors, result.fieldErrors)
    }

    @Test
    fun `registration errors do not suppress a lock request with complete client prerequisites`() {
        val party = validParty()
        val selected = party.take(3).mapTo(LinkedHashSet(), TowerPlayPartySlot::pokemonId)
        val state = state(
            party = party,
            selected = selected,
            errors = listOf(TowerPlayMessageKeys.DUPLICATE_SPECIES),
            mechanic = MajorBattleMechanic.MEGA,
        )

        assertTrue(TowerPlayInteractionPolicy.canRequestLock(state, isPending = false))
    }

    @Test
    fun `lock remains disabled until selections and mechanic are complete`() {
        val incomplete = state(selected = emptySet(), mechanic = null)

        assertFalse(TowerPlayInteractionPolicy.canRequestLock(incomplete, isPending = false))
        assertFalse(TowerPlayInteractionPolicy.canRequestLock(incomplete, isPending = true))
    }

    @Test
    fun `rejected lock preserves specific field feedback before the generic message`() {
        val controller = TowerPlayScreenController(state(), { requestId }) {}
        controller.lockTeam()

        controller.apply(
            TowerPlayMutationResult.Rejected(
                requestId = requestId,
                currentRevision = 4,
                messageKey = TowerPlayMessageKeys.TEAM_INVALID,
                fieldErrors = linkedMapOf("species" to TowerPlayMessageKeys.DUPLICATE_SPECIES),
            ),
        )

        assertEquals(listOf(TowerPlayMessageKeys.DUPLICATE_SPECIES), controller.fieldFeedbackKeys)
        assertEquals(TowerPlayMessageKeys.TEAM_INVALID, controller.feedbackKey)
    }

    private fun state(
        revision: Long = 4,
        party: List<TowerPlayPartySlot> = validParty(),
        selected: Set<UUID> = emptySet(),
        errors: List<String> = emptyList(),
        mechanic: MajorBattleMechanic? = null,
    ) = TowerPlayViewState(
        entryContextId = entryContextId,
        revision = revision,
        format = TowerBattleFormat.SINGLE,
        phase = TowerPlayPhase.SELECTING,
        party = party,
        selectedPokemonIds = selected,
        rank = TowerRank.RANK_1,
        rankPoints = 0,
        winsRequired = 2,
        masterCycleWins = 0,
        bpBalance = 0,
        errorKeys = errors,
        selectedMechanic = mechanic,
    )

    private fun validParty(): List<TowerPlayPartySlot> = (1..6).map { index ->
        TowerPlayPartySlot(
            slot = index - 1,
            pokemonId = if (index == 1) pokemonId else UUID(0, index.toLong()),
            speciesId = "cobblemon:species_$index",
            heldItemId = if (index == 6) null else "minecraft:item_$index",
            level = 40 + index,
            battleLevel = minOf(40 + index, 50),
        )
    }
}
