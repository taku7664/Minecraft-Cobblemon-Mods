package jbro.cobblemon.policy

import jbro.cobblemon.policy.plaza.HubReturnRule
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HubReturnRuleTest {
    private val hubs = listOf("myroom:rooms")

    @Test
    fun `pvp detour keeps the original point with an existing configuration`() {
        val integrated = HubReturnRule.otherHubs(hubs)
        assertFalse(HubReturnRule.isStale(HubReturnRule.PVP_LOUNGE, integrated))
        assertFalse(HubReturnRule.shouldSaveOnEntry(HubReturnRule.PVP_LOUNGE, hasPoint = true, integrated))
        assertFalse(HubReturnRule.isStale(HubReturnRule.PLAZA, integrated))
        assertFalse(HubReturnRule.isStale("myroom:rooms", integrated))
        assertTrue(HubReturnRule.isStale("minecraft:overworld", integrated))
        assertTrue(HubReturnRule.shouldSaveOnEntry("minecraft:overworld", hasPoint = true, integrated))
    }

    @Test
    fun `pvp preservation does not require replacing custom hub settings`() {
        val integrated = HubReturnRule.otherHubs(listOf("custom:hub"))
        assertFalse(HubReturnRule.isStale("custom:hub", integrated))
        assertFalse(HubReturnRule.isStale(HubReturnRule.PVP_LOUNGE, integrated))
        assertTrue(HubReturnRule.isStale("custom:outside", integrated))
    }

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
