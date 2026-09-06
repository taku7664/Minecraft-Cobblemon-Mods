package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedAiDecisionLoopTest {
    @Test
    fun `real local brain choices advance native battle through replacement and victory`(@TempDir directory: Path) {
        val result = EmbeddedAiDecisionLoop.run(directory)
        assertEquals("p1", result.final["winner"].asString)
        assertTrue(result.choices.size >= 2)
        assertEquals(result.choices.size, result.searchAdjustedDecisions,
            "Every native request must reach the real selector with a recursive-search contribution")
        assertTrue(result.inputs.all { it.getAsJsonObject("own").getAsJsonArray("moves").size() == 2 })
        for (input in result.inputs) {
            val context = EmbeddedAiDecisionLoop.context(input)
            val own = context.state.pokemon.single { it.side == BattleSide.ALLY }
            val opponent = context.state.pokemon.single { it.side == BattleSide.OPPONENT }
            assertEquals(BattleCombatStatKnowledge.EXACT_OWN, own.combatStats!!.knowledge)
            assertEquals(BattleIntegerRange(110, 110), own.combatStats!!.maxHp)
            assertEquals(BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE, opponent.combatStats!!.knowledge)
            assertEquals(BattleIntegerRange(17, 21), opponent.combatStats!!.maxHp)
            assertEquals(2, context.publicActionCatalog.forPokemon(own.battlePokemonId).size)
            assertTrue(context.publicActionCatalog.forPokemon(opponent.battlePokemonId).isEmpty())
            assertFalse(context.publicActionCatalog.isMoveSetComplete(opponent.battlePokemonId))
        }
        val log = result.final.getAsJsonArray("publicLog").map { it.asString }
        assertEquals(2, log.count { it.startsWith("|faint|p2a:") })
        assertEquals(2, log.count { it.startsWith("|switch|p2a:") })
        assertEquals(result.choices.size, log.count { it.startsWith("|move|p1a:") })
    }

    @Test
    fun `unused hidden opponent set changes do not alter decision input or chosen actions`(@TempDir directory: Path) {
        val original = EmbeddedAiDecisionLoop.run(directory.resolve("original"))
        val changed = EmbeddedAiDecisionLoop.run(directory.resolve("changed"), hiddenVariant = true)
        assertEquals(original.inputs, changed.inputs)
        assertEquals(original.choices, changed.choices)
        assertEquals(original.searchAdjustedDecisions, changed.searchAdjustedDecisions)
        assertTrue(original.searchAdjustedDecisions > 0)
        assertNotEquals(original.final["refereeTeams"], changed.final["refereeTeams"])
        assertTrue(original.inputs.none { it.toString().contains("pp_update") })
    }
}
