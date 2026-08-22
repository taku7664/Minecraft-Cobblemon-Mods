package jbro.cobblemon.morebattlecontent.internal.tower

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerLegendaryClassPolicyTest {
    @Test
    fun `covers legendary mythical ultra beast and paradox categories`() {
        assertEquals(TowerLegendaryClassCategory.LEGENDARY, entry("cobblemon:mewtwo").category)
        assertEquals(TowerLegendaryClassCategory.MYTHICAL, entry("cobblemon:arceus").category)
        assertEquals(TowerLegendaryClassCategory.ULTRA_BEAST, entry("cobblemon:kartana").category)
        assertEquals(TowerLegendaryClassCategory.PARADOX, entry("cobblemon:iron_valiant").category)
        assertNull(TowerLegendaryClassPolicy.entryFor("cobblemon:pikachu"))
    }

    @Test
    fun `labels classify addon species while explicit paradox fallback covers compact ids`() {
        assertNotNull(TowerLegendaryClassPolicy.entryFor("addon:custom_mon", setOf("legendary")))
        assertEquals(
            TowerLegendaryClassCategory.PARADOX,
            entry("cobblemon:fluttermane").category,
        )
    }

    @Test
    fun `pro stage strongly favors power grade four while fame remains an independent multiplier`() {
        val weak = TowerLegendaryClassEntry(TowerLegendaryClassCategory.LEGENDARY, 1, 1)
        val broken = TowerLegendaryClassEntry(TowerLegendaryClassCategory.LEGENDARY, 4, 1)
        val famousBroken = broken.copy(fameWeight = 4)

        assertTrue(
            TowerLegendaryClassPolicy.selectionWeight(TowerStreakStage.PRO, broken) >
                TowerLegendaryClassPolicy.selectionWeight(TowerStreakStage.INTRODUCTORY, broken),
        )
        assertTrue(
            TowerLegendaryClassPolicy.selectionWeight(TowerStreakStage.PRO, weak) <
                TowerLegendaryClassPolicy.selectionWeight(TowerStreakStage.INTRODUCTORY, weak),
        )
        assertEquals(
            TowerLegendaryClassPolicy.selectionWeight(TowerStreakStage.PRO, broken) * 4,
            TowerLegendaryClassPolicy.selectionWeight(TowerStreakStage.PRO, famousBroken),
        )
    }

    private fun entry(speciesId: String): TowerLegendaryClassEntry =
        requireNotNull(TowerLegendaryClassPolicy.entryFor(speciesId))
}
