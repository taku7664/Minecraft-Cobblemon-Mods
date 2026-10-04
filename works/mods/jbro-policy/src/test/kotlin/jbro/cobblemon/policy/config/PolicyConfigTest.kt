package jbro.cobblemon.policy.config

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PolicyConfigTest {
    @Test
    fun `defaults keep the old server policy`() {
        val config = PolicyConfig()
        assertEquals(30, config.wildHiddenAbilityRate)
        assertEquals(listOf(20.0, 45.0, 25.0, 10.0), config.wildIvRanges.map { it.chance })
        assertEquals(PlazaSpawn(0.5, 80.0, 0.5), config.plaza)
    }

    @Test
    fun `written defaults read back unchanged`() {
        assertEquals(PolicyConfig(), PolicyConfig.parse(PolicyConfig.toJson(PolicyConfig())))
    }

    @Test
    fun `missing keys keep their defaults`() {
        val config = PolicyConfig.parse("""{"wildHiddenAbilityRate": 5, "plaza": {"y": 120}}""")
        assertEquals(5, config.wildHiddenAbilityRate)
        assertEquals(PlazaSpawn(0.5, 120.0, 0.5), config.plaza)
        assertEquals(PolicyConfig.DEFAULT_IV_RANGES, config.wildIvRanges)
    }

    @Test
    fun `invalid values are rejected`() {
        assertThrows<IllegalArgumentException> { PolicyConfig.parse("""{"wildHiddenAbilityRate": 101}""") }
        assertThrows<IllegalArgumentException> {
            PolicyConfig.parse("""{"wildIvDistribution": {"ranges": [{"min": 10, "max": 5, "chance": 1}]}}""")
        }
    }

    @Test
    fun `a broken file is left alone and the defaults run`() {
        val file = Files.createTempDirectory("jbro-policy").resolve("jbro-policy.json")
        Files.writeString(file, "{ not json")
        var warned = false
        assertEquals(PolicyConfig(), PolicyConfig.load(file) { _, _ -> warned = true })
        assertTrue(warned)
        assertEquals("{ not json", Files.readString(file))
    }

    @Test
    fun `a missing file gets the defaults written`() {
        val file = Files.createTempDirectory("jbro-policy").resolve("jbro-policy.json")
        assertFalse(Files.exists(file))
        PolicyConfig.load(file) { _, _ -> }
        assertEquals(PolicyConfig(), PolicyConfig.parse(Files.readString(file)))
    }
}
