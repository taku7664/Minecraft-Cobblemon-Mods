package jbro.cobblemon.mcc.betterai.engine

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The current embedded server data passes the held-item Pokemon as TakeItem's second argument. */
class EngineBoosterEnergyTakeItemParityTest {
    @Test
    fun `a Paradox attacker can remove Booster Energy from an ordinary holder`() {
        val scenario = RefScenario("knockoff-booster-ordinary-holder",
            p1 = listOf(RefSet("Corviknight", listOf("splash"), ability = "Mirror Armor", item = "Booster Energy")),
            p2 = listOf(RefSet("Iron Hands", listOf("knockoff"), ability = "Static", item = "Leftovers")),
            turns = listOf("move 1" to "move 1"))
        val differences = EngineReferee.compare(listOf(scenario))
        assertTrue(differences.isEmpty()) { differences.joinToString("\n") }
    }

    @Test
    fun `Trick cannot give Booster Energy to a Paradox recipient`() {
        val scenario = RefScenario(
            "trick-booster-paradox-recipient",
            p1 = listOf(RefSet("Alakazam", listOf("trick"), ability = "Synchronize", item = "Booster Energy")),
            // Static prevents a start-of-battle Quark Drive consumption from hiding the TakeItem check.
            p2 = listOf(RefSet("Iron Hands", listOf("splash"), ability = "Static", item = "Leftovers")),
            turns = listOf("move 1" to "move 1"),
        )
        val differences = EngineReferee.compare(listOf(scenario))
        assertTrue(differences.isEmpty()) { differences.joinToString("\n") }
    }
}
