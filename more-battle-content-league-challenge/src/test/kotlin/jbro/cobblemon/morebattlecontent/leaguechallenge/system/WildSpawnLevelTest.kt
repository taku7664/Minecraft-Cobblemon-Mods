package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WildSpawnLevelTest {
    @Test fun `inclusive reduction produces both ends of the requested range`() {
        assertEquals(15, WildSpawnLevel.fromReduction(15, 0))
        assertEquals(5, WildSpawnLevel.fromReduction(15, 10))
        assertEquals(20, WildSpawnLevel.fromReduction(20, 0))
        assertEquals(10, WildSpawnLevel.fromReduction(20, 10))
    }

    @Test fun `low caps never create a zero level pokemon`() {
        assertEquals(1, WildSpawnLevel.fromReduction(5, 10))
    }

    @Test fun `invalid reductions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.fromReduction(15, -1) }
        assertThrows(IllegalArgumentException::class.java) { WildSpawnLevel.fromReduction(15, 11) }
    }
}
