package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDirectHitMechanics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalDirectHitHpArithmeticTest {
    @Test
    fun `three exact hundred HP hits remove a three hundred HP target`() {
        var state = state(1.0)
        repeat(3) { index ->
            state = hit(state, 100.0 / 300.0)
            val target = state.pokemon.last()
            assertEquals((200 - index * 100).toDouble() / 300, target.hpFraction,
                "Integer HP subtraction must not accumulate a fractional survivor")
        }
        assertTrue(state.pokemon.last().fainted)
        assertEquals(0, state.remainingPokemonBySide.getValue(BattleSide.OPPONENT))
    }

    @Test
    fun `a real one HP survivor stays alive`() {
        val result = hit(state(101.0 / 300), 100.0 / 300)
        assertEquals(1.0 / 300, result.pokemon.last().hpFraction)
        assertFalse(result.pokemon.last().fainted)
    }

    @Test
    fun `non integer expected HP is not rounded to the nearest real HP`() {
        val hp = 100.25 / 300
        val result = hit(state(hp), 100.0 / 300)
        assertEquals(hp - 100.0 / 300, result.pokemon.last().hpFraction)
        assertFalse(result.pokemon.last().fainted)
    }

    @Test
    fun `unknown or ranged maximum HP does not authorize integer rounding`() {
        for (maximum in listOf(null, BattleIntegerRange(300, 400))) {
            val hp = 101.0 / 300
            val result = hit(state(hp, maximum), 100.0 / 300)
            assertEquals(hp - 100.0 / 300, result.pokemon.last().hpFraction)
            assertFalse(result.pokemon.last().fainted)
        }
    }

    private fun hit(state: BattleStateView, damage: Double) = LocalDirectHitMechanics.apply(
        state, UUID(0, 1), UUID(0, 2), damage, emptyList(), false,
    ).state

    private fun state(hp: Double, maximum: BattleIntegerRange? = BattleIntegerRange(300, 300)): BattleStateView {
        val pokemon = BattleSide.entries.map { side ->
            BattlePokemonStateView(UUID(0, side.ordinal + 1L), side, 0, "cobblemon:probe", null,
                50, if (side == BattleSide.ALLY) 1.0 else hp, null, emptyMap(), emptySet(), null, null,
                false, knownTypeIds = setOf("normal"), combatStats = if (side == BattleSide.ALLY)
                    BattleCombatStatRangesView.exact(300, 100, 100, 100, 100, 100)
                else maximum?.let { publicExactStats(300, 100, 100, 100, 100, 100).copy(maxHp = it) })
        }
        return BattleStateView(UUID(0, 3), BattleFormat.SINGLE, 1, pokemon, BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
    }
}
