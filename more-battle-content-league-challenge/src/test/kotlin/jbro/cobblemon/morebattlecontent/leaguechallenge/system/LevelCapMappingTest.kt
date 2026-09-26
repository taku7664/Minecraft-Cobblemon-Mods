package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LevelCapMappingTest {
    @Test fun `all required caps must map before a challenge starts`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            LevelCapMapping.resolve(mapOf("1" to 15, "2" to 20), setOf(15, 20, 100))
        }
        assertEquals("cap_unmapped:100", failure.message)
    }

    @Test fun `canonical positive tier wins deterministically without nearest cap fallback`() {
        val tiers = mapOf("5" to 20, "2" to 20, "01" to 15, "0" to 15, "-1" to 15, "initial" to 15)
        assertEquals(mapOf(20 to 2), LevelCapMapping.resolve(tiers, setOf(20)))
        assertThrows(IllegalStateException::class.java) { LevelCapMapping.resolve(tiers, setOf(15)) }
    }
}
