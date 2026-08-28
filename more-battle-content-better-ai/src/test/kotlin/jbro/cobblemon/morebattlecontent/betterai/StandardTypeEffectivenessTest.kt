package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.mechanics.StandardTypeEffectiveness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Guards the type chart against a missing cell.
 *
 * A dropped entry does not fail loudly. It silently reads as neutral, and the only symptom is a
 * trainer occasionally choosing a move the chart should have ruled out - which looks like a broken
 * evaluation rather than missing data. Grass against Ice was absent, so Energy Ball into an
 * Abomasnow priced at 0.5x instead of 0.25x.
 *
 * Counting each attacking type's non-neutral entries is what catches that class of error: a spot
 * check only ever finds the cell someone already suspected.
 */
class StandardTypeEffectivenessTest {
    @Test
    fun `every attacking type has its full set of non-neutral matchups`() {
        // Gen 6 onwards. Each count is (super effective) + (not very effective) + (immune) for that
        // attacking type, taken from the published chart rather than from the table under test.
        val expected = mapOf(
            "normal" to 3, "fire" to 8, "water" to 6, "electric" to 6, "grass" to 11,
            "ice" to 8, "fighting" to 11, "poison" to 7, "ground" to 8, "flying" to 6,
            "psychic" to 5, "bug" to 10, "rock" to 7, "ghost" to 4, "dragon" to 3,
            "dark" to 5, "steel" to 7, "fairy" to 6,
        )
        val actual = expected.keys.associateWith { attacker ->
            expected.keys.count { defender ->
                StandardTypeEffectiveness.multiplier(attacker, setOf(defender)) != 1.0
            }
        }
        assertEquals(expected, actual, "A count that is one short means a matchup silently reads neutral.")
    }

    @Test
    fun `dual type defenders multiply both halves`() {
        // The cell that was missing, and the reason it mattered: a Fire attacker sees 4x into the same
        // defender, so the gap between the right move and the wrong one is sixteenfold.
        assertEquals(0.25, StandardTypeEffectiveness.multiplier("grass", setOf("grass", "ice")))
        assertEquals(4.0, StandardTypeEffectiveness.multiplier("fire", setOf("grass", "ice")))
        assertEquals(0.0, StandardTypeEffectiveness.multiplier("normal", setOf("ghost", "fire")))
    }
}
