package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.LocalForcedReplacementResolver
import jbro.cobblemon.mcc.betterai.state.LocalSimultaneousReplacementProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalForcedEntryOrderRegressionTest {
    @Test
    fun `both replacements are present before Intimidate runs`() {
        val (state, source) = fixture("intimidate", 100, "intimidate", 200)
        val result = replace(state, source).single().state
        assertEquals(listOf(-1, -1), result.pokemon.filter { !it.fainted }.map { it.statStages["attack"] })
    }

    @Test
    fun `slower weather ability resolves last regardless of which trainer owns it`() {
        val (state, source) = fixture("drought", 100, "drizzle", 200)
        assertEquals("sunnyday", replace(state, source).single().state.field.weather?.effectId)
        val (reverse, reverseSource) = fixture("drought", 200, "drizzle", 100)
        assertEquals("raindance", replace(reverse, reverseSource).single().state.field.weather?.effectId)
    }

    @Test
    fun `tied simultaneous weather entrants branch both official orders`() {
        val (state, source) = fixture("drought", 100, "drizzle", 100)
        val branches = replace(state, source)
        assertEquals(setOf("sunnyday", "raindance"), branches.map { it.state.field.weather?.effectId }.toSet())
        assertEquals(listOf(0.5, 0.5), branches.map { it.probability })
    }

    private fun replace(state: BattleStateView, source: BattleDecisionContext) = LocalSimultaneousReplacementProjector.project(
        state, LocalForcedReplacementResolver.plans(state, BattleSide.ALLY).choices.single(),
        LocalForcedReplacementResolver.plans(state, BattleSide.OPPONENT).choices.single(), source,
    )

    private fun fixture(allyAbility: String, allySpeed: Int, foeAbility: String, foeSpeed: Int): Pair<BattleStateView, BattleDecisionContext> {
        fun pokemon(side: BattleSide, alive: Boolean, ability: String, speed: Int) = BattlePokemonStateView(
            UUID.randomUUID(), side, if (alive) null else 0, "showdown:probe", null, 50,
            if (alive) 1.0 else 0.0, null, emptyMap(), emptySet(), ability, null, !alive, setOf("normal"),
            BattleCombatStatRangesView(BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
                BattleIntegerRange(speed, speed), if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN
                else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE),
        )
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 4,
            listOf(pokemon(BattleSide.ALLY, false, "none", 50), pokemon(BattleSide.ALLY, true, allyAbility, allySpeed),
                pokemon(BattleSide.OPPONENT, false, "none", 50), pokemon(BattleSide.OPPONENT, true, foeAbility, foeSpeed)),
            BattleFieldStateView.empty(), BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
        val source = BattleDecisionContext(UUID.randomUUID(), state,
            listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)), Long.MAX_VALUE,
            BattleTacticalMemoryView.empty(), BattlePublicActionCatalogView(emptyList()))
        return state to source
    }
}
