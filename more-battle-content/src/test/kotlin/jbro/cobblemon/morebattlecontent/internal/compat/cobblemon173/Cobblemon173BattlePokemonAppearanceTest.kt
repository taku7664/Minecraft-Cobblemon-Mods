package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173BattlePokemonAppearanceTest {
    @Test
    fun `MBC battle hides held items only on disposable battle copies`() {
        var sourceVisible = true
        var originalBattleCopyVisible = true
        var effectedBattleCopyVisible = true
        val target = object : Cobblemon173BattlePokemonAppearanceTarget {
            override fun setOriginalBattleCopyHeldItemVisible(visible: Boolean) {
                originalBattleCopyVisible = visible
            }

            override fun setEffectedBattleCopyHeldItemVisible(visible: Boolean) {
                effectedBattleCopyVisible = visible
            }
        }

        Cobblemon173BattlePokemonAppearance.hideHeldItem(target)

        assertTrue(sourceVisible)
        assertFalse(originalBattleCopyVisible)
        assertFalse(effectedBattleCopyVisible)
    }
}
