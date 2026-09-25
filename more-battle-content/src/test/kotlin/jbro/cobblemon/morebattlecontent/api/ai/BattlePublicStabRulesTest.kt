package jbro.cobblemon.morebattlecontent.api.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BattlePublicStabRulesTest {
    @Test
    fun `ordinary and matching Tera stab keep their distinct multipliers`() {
        assertEquals(1.5, multiplier(setOf("water"), null, null, "water"))
        assertEquals(1.0, multiplier(setOf("water"), null, null, "fire"))
        assertEquals(2.0, multiplier(setOf("water"), "water", emptySet(), "water"))
        assertEquals(1.5, multiplier(setOf("water"), "fire", emptySet(), "water"))
        assertEquals(1.5, multiplier(setOf("water"), "fire", emptySet(), "fire"))
    }

    @Test
    fun `Stellar exact state distinguishes unused and consumed move types`() {
        assertEquals(2.0, multiplier(setOf("water"), "stellar", emptySet(), "water"))
        assertEquals(1.5, multiplier(setOf("water"), "stellar", setOf("water"), "water"))
        assertEquals(STELLAR_FIRST_USE, multiplier(setOf("water"), "stellar", emptySet(), "fire"))
        assertEquals(1.0, multiplier(setOf("water"), "stellar", setOf("fire"), "fire"))
    }

    @Test
    fun `unknown Stellar consumption returns the safe public lower bound`() {
        assertEquals(1.5, multiplier(setOf("water"), "stellar", null, "water"))
        assertEquals(1.0, multiplier(setOf("water"), "stellar", null, "fire"))
        assertNull(multiplier(emptySet(), "stellar", emptySet(), "water"))
    }

    private fun multiplier(
        baseTypes: Set<String>,
        teraType: String?,
        stellarBoostedTypes: Set<String>?,
        moveType: String,
    ) = BattlePublicStabRules.conservativeMultiplier(
        baseTypes,
        teraType,
        stellarBoostedTypes,
        moveType,
    )

    private companion object {
        const val STELLAR_FIRST_USE = 4915.0 / 4096.0
    }
}
