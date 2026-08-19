package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerPokemonSet
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerStatSpread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class Cobblemon173OpponentPokemonPropertiesFactoryTest {
    @Test
    fun `maps resource ids to the property names expected by Cobblemon`() {
        val set = TowerPokemonSet(
            setId = "rain_pelipper",
            setTier = 2,
            speciesId = "cobblemon:pelipper",
            formId = "standard",
            abilityId = "cobblemon:drizzle",
            natureId = "cobblemon:timid",
            heldItemId = "cobblemon:damp_rock",
            moves = listOf("cobblemon:tailwind", "cobblemon:hurricane", "cobblemon:weatherball", "cobblemon:protect"),
            ivs = TowerStatSpread(20, 19, 18, 17, 16, 15),
            evs = TowerStatSpread(1, 2, 3, 100, 50, 96),
            dmaxLevel = 10,
            gmaxFactor = true,
        )

        val properties = Cobblemon173OpponentPokemonPropertiesFactory.toProperties(set)

        assertEquals("cobblemon:pelipper", properties.species)
        assertEquals("standard", properties.form)
        assertEquals("drizzle", properties.ability)
        assertEquals("cobblemon:timid", properties.nature)
        assertEquals("cobblemon:damp_rock", properties.heldItem)
        assertEquals(listOf("tailwind", "hurricane", "weatherball", "protect"), properties.moves)
        assertEquals(50, properties.level)
        assertNotNull(properties.ivs)
        assertNotNull(properties.evs)
        assertEquals(20, requireNotNull(properties.ivs)[Stats.HP])
        assertEquals(15, requireNotNull(properties.ivs)[Stats.SPEED])
        assertEquals(100, requireNotNull(properties.evs)[Stats.SPECIAL_ATTACK])
        assertEquals(96, requireNotNull(properties.evs)[Stats.SPEED])
        assertNull(properties.teraType)
        assertEquals(10, properties.dmaxLevel)
        assertEquals(true, properties.gmaxFactor)
    }

    @Test
    fun `preserves optional form ability and item as absent`() {
        val set = TowerPokemonSet(
            setId = "plain",
            setTier = 1,
            speciesId = "cobblemon:eevee",
            formId = null,
            abilityId = null,
            natureId = "cobblemon:hardy",
            heldItemId = null,
            moves = listOf("cobblemon:tackle"),
            ivs = TowerStatSpread(0, 0, 0, 0, 0, 0),
            evs = TowerStatSpread(0, 0, 0, 0, 0, 0),
        )

        val properties = Cobblemon173OpponentPokemonPropertiesFactory.toProperties(set)

        assertNull(properties.form)
        assertNull(properties.ability)
        assertNull(properties.heldItem)
        assertNull(properties.teraType)
        assertNull(properties.dmaxLevel)
        assertNull(properties.gmaxFactor)
    }

    @Test
    fun `maps tera type without manufacturing dynamax properties`() {
        val set = TowerPokemonSet(
            setId = "tera_water",
            setTier = 2,
            speciesId = "cobblemon:pelipper",
            formId = null,
            abilityId = "cobblemon:drizzle",
            natureId = "cobblemon:timid",
            heldItemId = "cobblemon:damp_rock",
            moves = listOf("cobblemon:hurricane"),
            ivs = TowerStatSpread(20, 20, 20, 20, 20, 20),
            evs = TowerStatSpread(0, 0, 0, 252, 0, 0),
            teraType = "water",
        )

        val properties = Cobblemon173OpponentPokemonPropertiesFactory.toProperties(set)

        assertEquals("water", properties.teraType)
        assertNull(properties.dmaxLevel)
        assertNull(properties.gmaxFactor)
    }
}
