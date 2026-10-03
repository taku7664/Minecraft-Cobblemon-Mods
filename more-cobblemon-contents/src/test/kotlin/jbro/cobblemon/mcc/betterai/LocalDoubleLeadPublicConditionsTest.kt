package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.policy.LocalDoubleLeadPairEvaluation
import jbro.cobblemon.mcc.betterai.policy.LocalLeadChoice
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalDoubleLeadPublicConditionsTest {
    @Test
    fun `a one Pokemon opposing selection cannot appear as two simultaneous preview opponents`() {
        val source = context(listOf("water", "water", "grass"), listOf("fire", "water"), selectionSize = 1)
        val firstPair = setOf(UUID(0, 1), UUID(0, 2))
        val count = (0L until 200L).count { seed ->
            val seeded = BattleLeadChoiceContext(source.format, source.ownTeam, source.ownMoves, source.exactOwnTeam,
                source.opponentTeamPreview, source.trainerProfile, seed = seed)
            requireNotNull(LocalLeadChoice.choose(seeded)).leads.toSet() == firstPair
        }
        assertTrue(count > 100, "Both water users have better average matchups against a single unknown selected foe: $count")
    }

    @Test
    fun `Snow does not prove physical protection when the public opponent can use special type pressure`() {
        fun score(ability: String): Double {
            val source = context(List(3) { "ice" }, List(2) { "ice" }, ability = ability)
            return LocalDoubleLeadPairEvaluation.scores(source, source.ownTeam, source.opponentTeamPreview.pokemon,
                detailed = true).getValue(listOf(UUID(0, 1), UUID(0, 2)))
        }
        // All attacks here are Special. Snow changes neither these attacks nor the equally strong
        // opposing Special pressure, so the worst public type threat remains available.
        assertEquals(score("none"), score("snowwarning"), 1e-9)
    }

    private fun context(types: List<String>, opposingTypes: List<String>, selectionSize: Int = 2,
        ability: String = "none"): BattleLeadChoiceContext {
        val own = types.mapIndexed { slot, type ->
            BattlePokemonStateView(UUID(0, slot + 1L), BattleSide.ALLY, null, "cobblemon:$type", null, 50,
                1.0, null, emptyMap(), emptySet(), null, null, false, setOf(type),
                BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100))
        }
        val moves = BattlePublicActionCatalogView(own.map { pokemon -> BattlePokemonActionCatalogView(
            pokemon.battlePokemonId, listOf(BattlePublicMoveOptionView("${pokemon.knownTypeIds.single()}move",
                BattleMoveCandidateView(pokemon.knownTypeIds.single(), BattleMoveDamageCategory.SPECIAL,
                    90.0, 100.0, 0, 10), BattlePublicMoveKnowledge.EXACT_OWN)), moveSetComplete = true) })
        val builds = BattleExactOwnTeamView(own.mapIndexed { slot, pokemon ->
            BattleExactPokemonBuildView(pokemon.battlePokemonId, if (slot == 0) ability else "none", null,
                "serious", "N", EVS, EVS.mapValues { 31 }, null, pokemon.speciesId)
        })
        val preview = BattleOpponentTeamPreviewView(selectionSize, opposingTypes.mapIndexed { slot, type ->
            BattleOpponentTeamPreviewPokemonView(slot, "cobblemon:$type", null, 50, setOf(type),
                BattleCombatStatRangesView(BattleIntegerRange(149, 151), BattleIntegerRange(99, 101),
                    BattleIntegerRange(99, 101), BattleIntegerRange(99, 101), BattleIntegerRange(99, 101),
                    BattleIntegerRange(99, 101), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
        })
        return BattleLeadChoiceContext(BattleFormat.DOUBLE, own, moves, builds, preview, BattleTrainerProfile.boss())
    }

    private companion object {
        val EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
    }
}
