package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TowerPokemonSetTest {
    @Test
    fun `rejects mixed incomplete and out of range mechanic properties`() {
        assertThrows(IllegalArgumentException::class.java) {
            set(teraType = "fire", dmaxLevel = 10, gmaxFactor = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            set(dmaxLevel = 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            set(dmaxLevel = 11, gmaxFactor = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            set(teraType = "stellar")
        }
    }

    private fun set(
        teraType: String? = null,
        dmaxLevel: Int? = null,
        gmaxFactor: Boolean? = null,
    ) = TowerPokemonSet(
        setId = "test",
        setTier = 1,
        speciesId = "cobblemon:eevee",
        formId = null,
        abilityId = null,
        natureId = "cobblemon:hardy",
        heldItemId = null,
        moves = listOf("cobblemon:tackle"),
        ivs = TowerStatSpread(0, 0, 0, 0, 0, 0),
        evs = TowerStatSpread(0, 0, 0, 0, 0, 0),
        teraType = teraType,
        dmaxLevel = dmaxLevel,
        gmaxFactor = gmaxFactor,
    )
}
