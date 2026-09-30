package jbro.cobblemon.policy.api

import jbro.cobblemon.policy.config.PolicyConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TipsTest {
    @Test
    fun `the same tip never shows twice in a row`() {
        for (last in 0 until 5) for (roll in 0 until 5) assertNotEquals(last, Tips.pick(5, last, roll))
    }

    @Test
    fun `other rolls are kept`() {
        assertEquals(3, Tips.pick(5, 1, 3))
        assertEquals(0, Tips.pick(1, 0, 0))
        assertEquals(2, Tips.pick(5, -1, 2))
    }

    @Test
    fun `tips come from the config`() {
        val config = PolicyConfig.parse("""{"tipIntervalSeconds": 60, "tips": ["하나", "둘"]}""")
        assertEquals(60, config.tipIntervalSeconds)
        assertEquals(listOf("하나", "둘"), config.tips)
        assertEquals(30, PolicyConfig().tipIntervalSeconds)
        assertTrue(PolicyConfig().tips.isNotEmpty())
        assertThrows<IllegalArgumentException> { PolicyConfig.parse("""{"tipIntervalSeconds": -1}""") }
    }
}
