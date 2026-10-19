package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.matchup.LocalSwitchRates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalSwitchRatesTest {
    @Test
    fun `with nothing counted a rate is its prior`() {
        val tally = LocalSwitchRates.Tally()
        assertEquals(0.19, tally.rate(true, 0.19, 5.0), 1e-9)
        assertEquals(0.05, tally.rate(false, 0.05, 5.0), 1e-9)
    }

    @Test
    fun `counted switches pull the rate away from the prior by their weight`() {
        val tally = LocalSwitchRates.Tally()
        // Fifteen threatened positions, twelve of them switches: (12 + 5 * 0.2) / (15 + 5) = 0.65.
        repeat(12) { tally.add(1.0, true) }
        repeat(3) { tally.add(1.0, false) }
        assertEquals(0.65, tally.rate(true, 0.2, 5.0), 1e-9)
        // Nothing calm was counted, so the calm rate is still the prior.
        assertEquals(0.1, tally.rate(false, 0.1, 5.0), 1e-9)
    }

    @Test
    fun `a position is split between the two situations by its threat`() {
        val tally = LocalSwitchRates.Tally()
        tally.add(0.25, true)
        // Threatened: (0.25 + 1 * 0) / (0.25 + 1); calm: (0.75 + 1 * 0) / (0.75 + 1).
        assertEquals(0.25 / 1.25, tally.rate(true, 0.0, 1.0), 1e-9)
        assertEquals(0.75 / 1.75, tally.rate(false, 0.0, 1.0), 1e-9)
    }
}
