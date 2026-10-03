package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Expectations are from the current server's real boost event, including per-stat reactive boosts. */
class LocalSequentialBoostReactionTest {
    private val fixture = LocalEvaluationRegressionFixture

    @Test
    fun `two actual lowered stats each trigger Defiant`() {
        val after = change("defiant", linkedMapOf("def" to -1, "spe" to -1))
        assertEquals(4, stage(after, "attack"))
        assertEquals(-1, stage(after, "defence"))
        assertEquals(-1, stage(after, "speed"))
    }

    @Test
    fun `Competitive runs between the declared drops and caps each reaction`() {
        val after = change("competitive", linkedMapOf("def" to -1, "spa" to -6), mapOf("spa" to 5))
        assertEquals(2, stage(after, "special_attack"))
    }

    @Test
    fun `reversing the drops preserves the actual Competitive event order`() {
        val after = change("competitive", linkedMapOf("spa" to -6, "def" to -1), mapOf("spa" to 5))
        assertEquals(3, stage(after, "special_attack"))
    }

    @Test
    fun `a capped zero drop does not add a second Defiant reaction`() {
        val after = change("defiant", linkedMapOf("def" to -1, "spe" to -1), mapOf("spe" to -6))
        assertEquals(2, stage(after, "attack"))
        assertEquals(-6, stage(after, "speed"))
    }

    @Test
    fun `the original Defiant user reacts to a drop reflected by an opposing Mirror Armor`() {
        assertEquals(1, stage(reflect("defiant"), "attack"))
    }

    @Test
    fun `the original user's Clear Amulet stops a reflected drop`() {
        assertEquals(0, stage(reflect(null, "clearamulet"), "attack"))
    }

    @Test
    fun `the Mirror Armor holder's own Clear Amulet stops the drop before reflection`() {
        assertEquals(0, stage(reflect(null, targetItem = "clearamulet"), "attack"))
    }

    @Test
    fun `two Mirror Armor abilities reflect exactly once`() {
        assertEquals(-1, stage(reflect("mirrorarmor"), "attack"))
    }

    @Test
    fun `the Mirror Armor ability reflection bypasses the original user's Substitute`() {
        assertEquals(-1, stage(reflect(null, substitute = true), "attack"))
    }

    private fun change(ability: String, changes: Map<String, Int>, stages: Map<String, Int> = emptyMap()): BattlePokemonStateView {
        val source = fixture.mon(BattleSide.ALLY, 0)
        val target = fixture.mon(BattleSide.OPPONENT, 0, ability = ability).copyState(statStages = stages)
        return LocalStatStageChange.apply(fixture.state(source, target), target.battlePokemonId,
            source.battlePokemonId, changes).pokemon.single { it.battlePokemonId == target.battlePokemonId }
    }

    private fun reflect(ability: String?, item: String = "", substitute: Boolean = false,
        targetItem: String = ""): BattlePokemonStateView {
        val source = fixture.mon(BattleSide.ALLY, 0, ability = ability).copyState(knownHeldItemId = item,
            knownVolatileEffectIds = if (substitute) setOf("substitute") else emptySet())
        val target = fixture.mon(BattleSide.OPPONENT, 0, ability = "mirrorarmor").copyState(knownHeldItemId = targetItem)
        val result = LocalStatStageChange.apply(fixture.state(source, target), target.battlePokemonId,
            source.battlePokemonId, mapOf("atk" to -1))
        assertEquals(0, stage(result.pokemon.single { it.battlePokemonId == target.battlePokemonId }, "attack"))
        return result.pokemon.single { it.battlePokemonId == source.battlePokemonId }
    }

    private fun stage(pokemon: BattlePokemonStateView, stat: String) = pokemon.statStages.entries
        .firstOrNull { LocalStatStageChange.normalise(it.key) == stat }?.value ?: 0
}
