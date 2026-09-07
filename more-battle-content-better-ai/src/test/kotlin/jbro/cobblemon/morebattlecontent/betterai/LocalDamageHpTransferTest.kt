package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalDamageHpTransfer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalDamageHpTransferTest {
    private fun pokemon(min: Int?, max: Int? = min) = BattlePokemonStateView(
        UUID.randomUUID(), BattleSide.ALLY, 0, "test", null, 50, 0.5, null, emptyMap(), emptySet(), null, null, false,
        combatStats = min?.let { BattleCombatStatRangesView(BattleIntegerRange(it, requireNotNull(max)),
            BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
            BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE) })

    @Test
    fun `transfer bounds contain every exact maximum in the public ranges`() {
        val damage = BattleFractionRange(0.1, 0.2)
        val ratio = BattleFractionRange(0.5, 0.5)
        val bounds = LocalDamageHpTransfer.bounds(damage, ratio, pokemon(200, 240), pokemon(300, 360), 0.5)
        for (actorMax in 200..240) for (targetMax in 300..360) {
            for (hp in 30..72) {
                val fraction = hp.toDouble() / targetMax
                if (fraction !in damage.minimum..damage.maximum) continue
                val exact = LocalDamageHpTransfer.fraction(fraction, 0.5, pokemon(actorMax), pokemon(targetMax)).coerceAtMost(0.5)
                assertTrue(exact >= bounds.minimum && exact <= bounds.maximum, "$actorMax/$targetMax/$hp: $exact outside $bounds")
            }
        }
    }

    @Test
    fun `unknown maximum preserves uncertainty while zero damage proves zero transfer`() {
        assertEquals(BattleFractionRange(0.0, 0.5), LocalDamageHpTransfer.bounds(BattleFractionRange(0.1, 0.2),
            BattleFractionRange(0.5, 0.5), pokemon(200), pokemon(null), 0.5))
        assertEquals(BattleFractionRange(0.0, 0.0), LocalDamageHpTransfer.bounds(BattleFractionRange(0.0, 0.0),
            BattleFractionRange(0.5, 0.5), pokemon(200), pokemon(null), 0.5))
    }
}
