package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRegisteredTeamSnapshotStoreTest {
    private val playerId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    @Test
    fun `registered snapshots survive adventure-party changes and materialize level-fifty copies`() {
        val sources = (1..3).map { index -> Source(pokemon(index), "original-$index") }.toMutableList()
        val store = PvpRegisteredTeamSnapshotStore(
            sourcesFor = { sources },
            registrationOf = Source::registration,
            snapshotOf = { source, battleLevel -> Snapshot(source.registration, source.value, battleLevel) },
            battleCopyOf = { snapshot -> BattleCopy(snapshot.registration.pokemonId, snapshot.value, snapshot.battleLevel) },
        )
        val team = (PvpTeamRules.register(sources.map(Source::registration), PvpBattleFormat.SINGLE) as
            PvpTeamRegistrationResult.Accepted).team
        val selection = (PvpTeamRules.select(team, (1..3).map(::pokemonId), PvpBattleFormat.SINGLE) as
            PvpTeamSelectionResult.Accepted).team

        assertEquals(PvpRegisteredTeamSnapshotResult.Stored, store.snapshot(playerId, team))
        sources.clear()
        val materialized = store.materialize(playerId, selection)

        assertTrue(materialized is PvpRegisteredBattleTeamResult.Created)
        materialized as PvpRegisteredBattleTeamResult.Created
        assertEquals(listOf("original-1", "original-2", "original-3"), materialized.members.map(BattleCopy::value))
        assertEquals(listOf(50, 50, 50), materialized.members.map(BattleCopy::battleLevel))
    }

    @Test
    fun `missing or changed registrations fail closed and discard removes snapshots`() {
        val sources = (1..3).map { index -> Source(pokemon(index), "original-$index") }.toMutableList()
        val store = PvpRegisteredTeamSnapshotStore(
            sourcesFor = { sources },
            registrationOf = Source::registration,
            snapshotOf = { source, battleLevel -> Snapshot(source.registration, source.value, battleLevel) },
            battleCopyOf = { snapshot -> BattleCopy(snapshot.registration.pokemonId, snapshot.value, snapshot.battleLevel) },
        )
        val team = (PvpTeamRules.register(sources.map(Source::registration), PvpBattleFormat.SINGLE) as
            PvpTeamRegistrationResult.Accepted).team
        val selection = (PvpTeamRules.select(team, (1..3).map(::pokemonId), PvpBattleFormat.SINGLE) as
            PvpTeamSelectionResult.Accepted).team
        store.snapshot(playerId, team)

        val changedSelection = PvpSelectedTeam(
            PvpBattleFormat.SINGLE,
            selection.members.map { if (it.pokemonId == pokemonId(1)) it.copy(level = 51) else it },
        )
        assertTrue(store.materialize(playerId, changedSelection) is PvpRegisteredBattleTeamResult.SnapshotMismatch)
        store.discard(playerId)
        assertEquals(PvpRegisteredBattleTeamResult.NoSnapshot, store.materialize(playerId, selection))
    }

    private fun pokemon(index: Int) = PvpPokemonRegistration(
        pokemonId(index),
        "cobblemon:species$index",
        "cobblemon:item$index",
        25 + index,
    )

    private fun pokemonId(index: Int): UUID = UUID(0, index.toLong())

    private data class Source(val registration: PvpPokemonRegistration, val value: String)
    private data class Snapshot(val registration: PvpPokemonRegistration, val value: String, val battleLevel: Int)
    private data class BattleCopy(val pokemonId: UUID, val value: String, val battleLevel: Int)
}
