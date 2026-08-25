package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerTrainerStyleTest {
    @Test
    fun `each trainer style recognizes its own tactical signature`() {
        assertTrue(TowerTrainerStyle.PHYSICAL_PRESSURE.matches(set(nature = "cobblemon:jolly")))
        assertTrue(TowerTrainerStyle.SPECIAL_PRESSURE.matches(set(nature = "cobblemon:modest")))
        assertTrue(TowerTrainerStyle.SETUP_SWEEP.matches(set(moves = listOf("cobblemon:swordsdance"))))
        assertTrue(TowerTrainerStyle.ENDURANCE.matches(set(moves = listOf("cobblemon:recover"))))
        assertTrue(TowerTrainerStyle.FIELD_CONTROL.matches(set(moves = listOf("cobblemon:stealthrock"))))
        assertTrue(TowerTrainerStyle.SPEED_CONTROL.matches(set(moves = listOf("cobblemon:tailwind"))))
        assertTrue(TowerTrainerStyle.WEATHER_CONTROL.matches(set(ability = "cobblemon:drizzle")))
        assertTrue(TowerTrainerStyle.BALANCED.matches(set()))
        assertFalse(TowerTrainerStyle.WEATHER_CONTROL.matches(set(moves = listOf("cobblemon:tackle"))))
    }

    private fun set(
        nature: String = "cobblemon:hardy",
        ability: String? = null,
        moves: List<String> = listOf("cobblemon:tackle"),
    ) = TowerPokemonSet(
        setId = "test",
        setTier = 1,
        speciesId = "cobblemon:eevee",
        formId = null,
        abilityId = ability,
        natureId = nature,
        heldItemId = null,
        moves = moves,
        ivs = TowerStatSpread(31, 31, 31, 31, 31, 31),
        evs = TowerStatSpread(0, 0, 0, 0, 0, 0),
    )
}
