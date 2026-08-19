package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerRegisteredTeamSnapshotStoreTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `confirmed six member team survives adventure party replacement`() {
        val adventureParty = registrations().map(::Source).toMutableList()
        val store = store { adventureParty }
        val registered = acceptedTeam(registrations())
        val selected = acceptedSelection(registered, TowerBattleFormat.SINGLE, listOf(3, 1, 2))

        assertEquals(TowerRegisteredTeamSnapshotResult.Stored, store.snapshot(playerId, registered))
        adventureParty.clear()
        adventureParty += Source(registration(7))

        val firstBattle = store.materialize(playerId, selected)
        val secondBattle = store.materialize(playerId, selected)

        firstBattle as TowerRegisteredBattleTeamResult.Created
        secondBattle as TowerRegisteredBattleTeamResult.Created
        assertEquals(listOf(3L, 1L, 2L), firstBattle.members.map { it.pokemonId.leastSignificantBits })
        assertEquals(listOf(43, 41, 42), firstBattle.members.map(BattleCopy::level))
        firstBattle.members.zip(secondBattle.members).forEach { (first, second) -> assertNotSame(first, second) }
    }

    @Test
    fun `failed replacement does not overwrite the last complete snapshot`() {
        val adventureParty = registrations().map(::Source).toMutableList()
        val store = store { adventureParty }
        val registered = acceptedTeam(registrations())
        val selected = acceptedSelection(registered, TowerBattleFormat.SINGLE, listOf(1, 2, 3))
        assertEquals(TowerRegisteredTeamSnapshotResult.Stored, store.snapshot(playerId, registered))
        adventureParty.removeAt(5)

        assertTrue(store.snapshot(playerId, registered) is TowerRegisteredTeamSnapshotResult.Rejected)
        assertTrue(store.materialize(playerId, selected) is TowerRegisteredBattleTeamResult.Created)
    }

    @Test
    fun `discard removes the in-memory snapshot`() {
        val registered = acceptedTeam(registrations())
        val selected = acceptedSelection(registered, TowerBattleFormat.SINGLE, listOf(1, 2, 3))
        val store = store { registrations().map(::Source) }
        store.snapshot(playerId, registered)

        store.discard(playerId)

        assertEquals(TowerRegisteredBattleTeamResult.NoSnapshot, store.materialize(playerId, selected))
    }

    private fun store(sources: (UUID) -> Collection<Source>?) = TowerRegisteredTeamSnapshotStore(
        sourcesFor = sources,
        registrationOf = Source::registration,
        snapshotOf = { source, battleLevel -> Snapshot(source.registration.pokemonId, source.registration.speciesId, battleLevel) },
        battleCopyOf = { snapshot -> BattleCopy(snapshot.pokemonId, snapshot.speciesId, snapshot.level) },
    )

    private fun registrations() = (1..6).map(::registration)

    private fun registration(index: Int) = TowerPokemonRegistration(
        pokemonId = UUID(0, index.toLong()),
        speciesId = "cobblemon:species_$index",
        heldItemId = if (index == 6) null else "minecraft:item_$index",
        level = 40 + index,
    )

    private fun acceptedTeam(registrations: List<TowerPokemonRegistration>): TowerRegisteredTeam =
        (TowerTeamRules.register(registrations) as TowerTeamRegistrationResult.Accepted).team

    private fun acceptedSelection(
        team: TowerRegisteredTeam,
        format: TowerBattleFormat,
        indexes: List<Int>,
    ): TowerSelectedTeam = (TowerTeamRules.select(
        team,
        format,
        indexes.map { UUID(0, it.toLong()) },
    ) as TowerTeamSelectionResult.Accepted).selection

    private data class Source(val registration: TowerPokemonRegistration)
    private data class Snapshot(val pokemonId: UUID, val speciesId: String, val level: Int)
    private data class BattleCopy(val pokemonId: UUID, val speciesId: String, val level: Int)
}
