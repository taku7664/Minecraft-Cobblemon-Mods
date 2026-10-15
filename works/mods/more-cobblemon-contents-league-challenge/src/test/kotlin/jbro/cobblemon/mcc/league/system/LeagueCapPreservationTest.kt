package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeagueCapPreservationTest {
    private val tiers = mapOf("1" to 16, "2" to 80, "3" to 100, "8" to 24)
    @Test fun `numeric levels rather than tier ordering decide whether a cap increases`() {
        assertEquals(3, LevelCapMapping.nonDecreasingTier(tiers, 3, 16))
        assertEquals(2, LevelCapMapping.nonDecreasingTier(tiers, 8, 80))
        assertEquals(2, LevelCapMapping.nonDecreasingTier(tiers, 2, 80))
    }
    @Test fun `a lower unmapped entitlement cannot invalidate an existing higher cap`() {
        assertEquals(3, LevelCapMapping.nonDecreasingTier(tiers, 3, 30))
    }
}
