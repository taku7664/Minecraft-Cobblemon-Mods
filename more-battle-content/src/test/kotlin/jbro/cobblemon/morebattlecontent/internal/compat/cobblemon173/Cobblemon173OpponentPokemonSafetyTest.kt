package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173OpponentPokemonSafetyTest {
    @Test
    fun `npc battle pokemon is marked as trainer-owned and neither displays nor drops its held item`() {
        val target = object : Cobblemon173OpponentPokemonSafetyTarget {
            var npcOwned = false
            var visible = true
            var canDrop = true

            override fun markNpcOwned() {
                npcOwned = true
            }

            override fun setHeldItemVisible(visible: Boolean) {
                this.visible = visible
            }

            override fun setCanDropHeldItem(canDrop: Boolean) {
                this.canDrop = canDrop
            }
        }

        Cobblemon173OpponentPokemonSafety.apply(target)

        assertTrue(target.npcOwned)
        assertFalse(target.visible)
        assertFalse(target.canDrop)
    }
}
