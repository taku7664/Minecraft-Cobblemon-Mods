package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Public entry effects must use the same boost rules as a declared move. */
class LocalIntimidateEntryReactionTest {
    private val fixture = LocalEvaluationRegressionFixture

    @Test
    fun `Contrary reverses the entry drop`() {
        assertEquals(1, stage(project("contrary"), "attack"))
    }

    @Test
    fun `Simple doubles the entry drop`() {
        assertEquals(-2, stage(project("simple"), "attack"))
    }

    @Test
    fun `a Substitute blocks Intimidate before Contrary can reverse it`() {
        assertEquals(0, stage(project("contrary", substitute = true), "attack"))
    }

    @Test
    fun `Flower Veil protects a Grass partner but not a Normal partner`() {
        assertEquals(0, stage(project(null, flowerVeil = true, types = setOf("grass")), "attack"))
        assertEquals(-1, stage(project(null, flowerVeil = true), "attack"))
    }

    @Test
    fun `Mist blocks the entry drop`() {
        assertEquals(0, stage(project(null, mist = true), "attack"))
    }

    @Test
    fun `White Herb restores and is consumed by the entry drop`() {
        val target = target(project(null, item = "whiteherb"))
        assertEquals(0, target.statStages["attack"] ?: 0)
        assertEquals("", target.knownHeldItemId)
    }

    @Test
    fun `Rattled still answers the boost attempt when Clear Amulet stops the drop`() {
        val state = project("rattled", item = "clearamulet")
        assertEquals(0, stage(state, "attack"))
        assertEquals(1, stage(state, "speed"))
    }

    @Test
    fun `Substitute stops the entire Intimidate attempt including Rattled`() {
        assertEquals(0, stage(project("rattled", substitute = true), "speed"))
    }

    @Test
    fun `Defiant reacts to an actual entry drop and cannot react below the stage floor`() {
        assertEquals(1, stage(project("defiant"), "attack"))
        assertEquals(-6, stage(project("defiant", stages = mapOf("attack" to -6)), "attack"))
    }

    @Test
    fun `Guard Dog cannot replace a capped zero drop with a boost`() {
        assertEquals(1, stage(project("guarddog"), "attack"))
        assertEquals(-6, stage(project("guarddog", stages = mapOf("attack" to -6)), "attack"))
    }

    @Test
    fun `Neutralizing Gas suppresses the entry ability`() {
        assertEquals(0, stage(project("contrary", gas = true), "attack"))
    }

    @Test
    fun `Rattled answers a capped zero drop but Competitive does not`() {
        assertEquals(1, stage(project("rattled", stages = mapOf("attack" to -6)), "speed"))
        assertEquals(0, stage(project("competitive", stages = mapOf("attack" to -6)), "special_attack"))
    }

    @Test
    fun `Mirror Armor cannot reflect a capped zero drop`() {
        val state = project("mirrorarmor", stages = mapOf("attack" to -6))
        val entrant = state.pokemon.single { it.side == BattleSide.ALLY }
        assertEquals(0, entrant.statStages["attack"] ?: 0)
    }

    @Test
    fun `Flower Veil and Mist permit the positive Contrary result`() {
        assertEquals(1, stage(project("contrary", flowerVeil = true, types = setOf("grass")), "attack"))
        assertEquals(1, stage(project("contrary", mist = true), "attack"))
    }

    @Test
    fun `White Herb waits until both Mirror Armor reflections in the entry batch finish`() {
        val entrant = fixture.mon(BattleSide.ALLY, 0, ability = "intimidate").copyState(knownHeldItemId = "whiteherb")
        val first = fixture.mon(BattleSide.OPPONENT, 0, ability = "mirrorarmor")
        val second = fixture.mon(BattleSide.OPPONENT, 1, ability = "mirrorarmor")
        val projected = LocalEntryAbilityProjector.project(fixture.state(entrant, first, second,
            format = BattleFormat.DOUBLE), entrant.battlePokemonId)
        val after = projected.pokemon.single { it.battlePokemonId == entrant.battlePokemonId }
        // Current Cobblemon Showdown logs both -1 drops before the end-of-batch Herb update.
        assertEquals(0, after.statStages["attack"] ?: 0)
        assertEquals("", after.knownHeldItemId)
    }

    private fun project(ability: String?, item: String = "", substitute: Boolean = false,
        flowerVeil: Boolean = false, types: Set<String> = setOf("normal"), mist: Boolean = false,
        stages: Map<String, Int> = emptyMap(), gas: Boolean = false): BattleStateView {
        val entrant = fixture.mon(BattleSide.ALLY, 0, ability = "intimidate")
        val foe = fixture.mon(BattleSide.OPPONENT, 0, ability = ability)
            .copyState(knownTypeIds = types, knownHeldItemId = item, statStages = stages,
                knownVolatileEffectIds = if (substitute) setOf("substitute") else emptySet())
        val extra = if (flowerVeil || gas) listOf(fixture.mon(BattleSide.OPPONENT, 1,
            ability = if (gas) "neutralizinggas" else "flowerveil")) else emptyList()
        var state = fixture.state(*(listOf(entrant, foe) + extra).toTypedArray(),
            format = if (extra.isEmpty()) BattleFormat.SINGLE else BattleFormat.DOUBLE)
        if (mist) state = state.derive(field = BattleFieldStateView(null, null, emptyList(), emptyList(),
            mapOf(BattleSide.ALLY to emptyList(), BattleSide.OPPONENT to listOf(BattleTimedEffectView("mist", 3)))))
        return LocalEntryAbilityProjector.project(state, entrant.battlePokemonId)
    }

    private fun target(state: BattleStateView) = state.pokemon.single { it.side == BattleSide.OPPONENT && it.activeSlot == 0 }
    private fun stage(state: BattleStateView, stat: String) = target(state).statStages.entries
        .firstOrNull { LocalStatStageChange.normalise(it.key) == stat }?.value ?: 0
}
