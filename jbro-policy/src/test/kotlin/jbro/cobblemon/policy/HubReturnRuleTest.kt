package jbro.cobblemon.policy

import jbro.cobblemon.policy.plaza.HubReturnRule
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HubReturnRuleTest {
    private val hubs = listOf("myroom:rooms")

    @Test
    fun `entering from outside always saves a new point`() {
        assertTrue(HubReturnRule.shouldSaveOnEntry("minecraft:overworld", hasPoint = false, hubs))
        assertTrue(HubReturnRule.shouldSaveOnEntry("minecraft:overworld", hasPoint = true, hubs))
    }

    @Test
    fun `entering from another hub keeps the point of the trip`() {
        assertFalse(HubReturnRule.shouldSaveOnEntry("myroom:rooms", hasPoint = true, hubs))
        assertTrue(HubReturnRule.shouldSaveOnEntry("myroom:rooms", hasPoint = false, hubs))
    }

    @Test
    fun `only dimensions outside every hub end the trip`() {
        assertTrue(HubReturnRule.isStale("minecraft:the_nether", hubs))
        assertFalse(HubReturnRule.isStale(HubReturnRule.PLAZA, hubs))
        assertFalse(HubReturnRule.isStale("myroom:rooms", hubs))
        assertTrue(HubReturnRule.isStale("myroom:rooms", emptyList()))
    }
}
