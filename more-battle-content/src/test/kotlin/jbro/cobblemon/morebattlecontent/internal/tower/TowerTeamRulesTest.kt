package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerTeamRulesTest {
    @Test
    fun `registers six distinct species with distinct held items`() {
        val candidates = validCandidates().toMutableList()

        val result = TowerTeamRules.register(candidates)

        assertTrue(result is TowerTeamRegistrationResult.Accepted)
        val team = (result as TowerTeamRegistrationResult.Accepted).team
        assertEquals(candidates, team.members)
        assertNotSame(candidates, team.members)

        candidates.clear()
        assertEquals(6, team.members.size)
    }

    @Test
    fun `reports every registration conflict in deterministic order`() {
        val firstId = id(1)
        val result = TowerTeamRules.register(
            listOf(
                pokemon(1, "cobblemon:pikachu", "minecraft:leftovers"),
                pokemon(1, "cobblemon:raichu", "minecraft:choice_scarf"),
                pokemon(3, "cobblemon:pikachu", "minecraft:leftovers"),
                pokemon(4, "cobblemon:gengar"),
                pokemon(5, "cobblemon:dragonite"),
            ),
        )

        assertEquals(
            TowerTeamRegistrationResult.Rejected(
                listOf(
                    TowerTeamRegistrationIssue.WrongTeamSize(actual = 5),
                    TowerTeamRegistrationIssue.DuplicatePokemon(firstId),
                    TowerTeamRegistrationIssue.DuplicateSpecies("cobblemon:pikachu"),
                    TowerTeamRegistrationIssue.DuplicateHeldItem("minecraft:leftovers"),
                ),
            ),
            result,
        )
    }

    @Test
    fun `allows multiple empty held item slots`() {
        val result = TowerTeamRules.register(
            validCandidates().mapIndexed { index, candidate ->
                if (index < 3) candidate.copy(heldItemId = null) else candidate
            },
        )

        assertTrue(result is TowerTeamRegistrationResult.Accepted)
    }

    @Test
    fun `selects exactly three singles or four doubles in requested order`() {
        val team = acceptedTeam()

        val singles = TowerTeamRules.select(
            team,
            TowerBattleFormat.SINGLE,
            listOf(id(3), id(1), id(5)),
        )
        val doubles = TowerTeamRules.select(
            team,
            TowerBattleFormat.DOUBLE,
            listOf(id(6), id(4), id(2), id(1)),
        )

        assertEquals(
            listOf(id(3), id(1), id(5)),
            (singles as TowerTeamSelectionResult.Accepted).selection.members.map { it.pokemonId },
        )
        assertEquals(
            listOf(id(6), id(4), id(2), id(1)),
            (doubles as TowerTeamSelectionResult.Accepted).selection.members.map { it.pokemonId },
        )
    }

    @Test
    fun `rejects wrong selection size duplicates and unregistered pokemon`() {
        val team = acceptedTeam()
        val unknown = id(99)

        val result = TowerTeamRules.select(
            team,
            TowerBattleFormat.DOUBLE,
            listOf(id(1), id(1), unknown),
        )

        assertEquals(
            TowerTeamSelectionResult.Rejected(
                listOf(
                    TowerTeamSelectionIssue.WrongSelectionSize(
                        format = TowerBattleFormat.DOUBLE,
                        actual = 3,
                    ),
                    TowerTeamSelectionIssue.DuplicatePokemon(id(1)),
                    TowerTeamSelectionIssue.UnregisteredPokemon(unknown),
                ),
            ),
            result,
        )
    }

    @Test
    fun `caps levels above fifty without raising lower levels`() {
        assertEquals(1, pokemon(1, "cobblemon:pikachu", level = 1).battleLevel)
        assertEquals(50, pokemon(1, "cobblemon:pikachu", level = 50).battleLevel)
        assertEquals(50, pokemon(1, "cobblemon:pikachu", level = 51).battleLevel)
        assertEquals(50, pokemon(1, "cobblemon:pikachu", level = 100).battleLevel)
    }

    private fun acceptedTeam(): TowerRegisteredTeam =
        (TowerTeamRules.register(validCandidates()) as TowerTeamRegistrationResult.Accepted).team

    private fun validCandidates(): List<TowerPokemonRegistration> = listOf(
        pokemon(1, "cobblemon:pikachu", "minecraft:leftovers"),
        pokemon(2, "cobblemon:gengar", "minecraft:choice_scarf"),
        pokemon(3, "cobblemon:dragonite", "minecraft:lum_berry"),
        pokemon(4, "cobblemon:lucario", "minecraft:life_orb"),
        pokemon(5, "cobblemon:milotic"),
        pokemon(6, "cobblemon:arcanine", "minecraft:sitrus_berry"),
    )

    private fun pokemon(
        id: Int,
        speciesId: String,
        heldItemId: String? = null,
        level: Int = 50,
    ) = TowerPokemonRegistration(
        pokemonId = id(id),
        speciesId = speciesId,
        heldItemId = heldItemId,
        level = level,
    )

    private fun id(value: Int): UUID = UUID(0, value.toLong())
}
