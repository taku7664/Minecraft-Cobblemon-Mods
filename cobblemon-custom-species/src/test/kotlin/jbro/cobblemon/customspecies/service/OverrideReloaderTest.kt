package jbro.cobblemon.customspecies.service

import jbro.cobblemon.customspecies.config.CustomSpeciesConfigParser
import jbro.cobblemon.customspecies.config.ConfigValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OverrideReloaderTest {
    private val parser = CustomSpeciesConfigParser()
    private val attack100 = parser.parse(
        """{"schema":1,"overrides":[{"species":"cobblemon:charizard","form":"base","base_stats":{"attack":100}}]}"""
    )

    // Stands in for Cobblemon, which replaces every Species object from JSON on each data reload.
    private var live = FakeSpeciesCatalog.charizard()
    private val reloader = OverrideReloader { live }

    private fun cobblemonReload() {
        live = FakeSpeciesCatalog.charizard()
    }

    private fun liveAttack(): Int? = live.requireTarget("cobblemon:charizard", "base").baseStats["attack"]

    @Test
    fun `overrides land on the species Cobblemon rebuilt for this reload`() {
        reloader.reload { attack100 }
        cobblemonReload()

        val outcome = reloader.reload { attack100 }

        assertEquals(OverrideReloader.Outcome.Applied(1), outcome)
        assertEquals(100, liveAttack())
    }

    @Test
    fun `rejected config reapplies the last accepted one on the rebuilt species`() {
        reloader.reload { attack100 }
        cobblemonReload()

        val outcome = reloader.reload { throw ConfigValidationException("broken") }

        assertTrue(outcome is OverrideReloader.Outcome.Rejected)
        assertEquals(1, (outcome as OverrideReloader.Outcome.Rejected).restoredOverrides)
        assertEquals(100, liveAttack())
    }

    @Test
    fun `rejected first config leaves Cobblemon defaults active`() {
        val outcome = reloader.reload { throw ConfigValidationException("broken") }

        assertEquals(0, (outcome as OverrideReloader.Outcome.Rejected).restoredOverrides)
        assertEquals(84, liveAttack())
    }
}
