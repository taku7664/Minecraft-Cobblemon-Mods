package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRentalSet
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryStatSpread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Cobblemon173FactoryPokemonFactoryTest {
    @Test
    fun `maps the complete rental set at the explicitly selected battle level`() {
        val properties = Cobblemon173FactoryPokemonFactory.toProperties(rental(), FactoryLevelMode.OPEN_LEVEL)

        assertEquals("cobblemon:rotom", properties.species)
        assertEquals("wash", properties.form)
        assertEquals("levitate", properties.ability)
        assertEquals("cobblemon:lum_berry", properties.heldItem)
        assertEquals("cobblemon:timid", properties.nature)
        assertEquals(listOf("hydropump", "voltswitch"), properties.moves)
        assertEquals(100, properties.level)
        assertEquals(31, requireNotNull(properties.ivs)[Stats.SPEED])
        assertEquals(252, requireNotNull(properties.evs)[Stats.SPECIAL_ATTACK])
    }

    @Test
    fun `rejects unknown level mode ids instead of silently substituting a level`() {
        assertThrows(IllegalArgumentException::class.java) {
            FactoryLevelMode.fromId("level_100")
        }
    }

    private fun rental() = FactoryRentalSet(
        setId = "factory_rotom_wash",
        speciesId = "cobblemon:rotom",
        formId = "wash",
        moveIds = listOf("cobblemon:hydropump", "cobblemon:voltswitch"),
        abilityId = "cobblemon:levitate",
        heldItemId = "cobblemon:lum_berry",
        natureId = "cobblemon:timid",
        ivs = FactoryStatSpread(30, 29, 28, 27, 26, 31),
        evs = FactoryStatSpread(4, 0, 0, 252, 0, 252),
    )
}
