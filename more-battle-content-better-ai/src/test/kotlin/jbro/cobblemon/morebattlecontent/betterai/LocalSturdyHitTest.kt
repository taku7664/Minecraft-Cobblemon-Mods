package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDirectHitMechanics
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalAppliedDirectHit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalSturdyHitTest {
    @Test
    fun `full HP sturdy survives without consuming a held focus sash`() {
        for (item in listOf(null, "focussash")) {
            val hit = hit(1.0, item, ignoreAbility = false)
            val target = hit.state.pokemon.single { it.side == BattleSide.OPPONENT }
            assertFalse(target.fainted)
            assertEquals(0.005, target.hpFraction, 1e-9)
            assertEquals(0.995, hit.directDamageFraction, 1e-9)
            assertEquals(item, target.knownHeldItemId)
        }
    }

    @Test
    fun `damaged or ignored sturdy does not prevent a knockout`() {
        for ((hp, ignore) in listOf(0.99 to false, 1.0 to true)) {
            val hit = hit(hp, null, ignore)
            assertTrue(hit.state.pokemon.single { it.side == BattleSide.OPPONENT }.fainted)
            assertEquals(hp, hit.directDamageFraction, 1e-9)
        }
    }

    @Test
    fun `ignoring sturdy still allows focus sash to activate`() {
        val target = hit(1.0, "focussash", ignoreAbility = true).state.pokemon.single { it.side == BattleSide.OPPONENT }
        assertEquals(0.005, target.hpFraction, 1e-9)
        assertNull(target.knownHeldItemId)
    }

    private fun hit(hp: Double, item: String?, ignoreAbility: Boolean): LocalAppliedDirectHit {
        val pokemon = BattleSide.entries.map { side ->
            BattlePokemonStateView(UUID(0, side.ordinal + 10L), side, 0, "cobblemon:probe", null, 50,
                if (side == BattleSide.ALLY) 1.0 else hp, null, emptyMap(), emptySet(),
                if (side == BattleSide.OPPONENT) "sturdy" else null, item, false,
                combatStats = if (side == BattleSide.ALLY) BattleCombatStatRangesView.exact(200, 100, 100, 100, 100, 100)
                    else publicExactStats(200, 100, 100, 100, 100, 100))
        }
        val state = BattleStateView(UUID(0, 1), BattleFormat.SINGLE, 1, pokemon, BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
        return LocalDirectHitMechanics.apply(state, pokemon.first().battlePokemonId, pokemon.last().battlePokemonId,
            2.0, emptyList(), ignoreAbility)
    }
}
