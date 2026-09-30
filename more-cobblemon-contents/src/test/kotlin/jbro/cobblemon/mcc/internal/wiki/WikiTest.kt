package jbro.cobblemon.mcc.internal.wiki

import java.nio.file.Files
import java.util.UUID
import jbro.cobblemon.mcc.internal.command.BattleContentCommandsTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WikiTest {
    @Test
    fun `the wiki is off by default and its file reads back`() {
        assertFalse(WikiConfig().enabled)
        val config = WikiConfig(enabled = true, port = 8123, publicUrl = "http://play.example.com:8123/")
        assertEquals(config, WikiConfig.read(WikiConfig.write(config)))
        assertEquals("http://play.example.com:8123", config.base)
        assertEquals("http://localhost:8100", WikiConfig().base)
        val path = Files.createTempDirectory("wiki").resolve("wiki.json")
        assertEquals(WikiConfig(), WikiConfig.load(path))
        assertTrue(Files.exists(path))
        Files.writeString(path, "{")
        assertFalse(WikiConfig.load(path).enabled)
    }

    @Test
    fun `a token names one player, survives a restart, and a reset ends the old one`() {
        val file = Files.createTempDirectory("wiki").resolve("data").resolve("tokens.json")
        val alex = UUID.randomUUID()
        val sam = UUID.randomUUID()
        val tokens = WikiTokens(file)
        val first = tokens.tokenFor(alex)
        assertEquals(first, tokens.tokenFor(alex))
        assertNotEquals(first, tokens.tokenFor(sam))
        assertTrue(first.length >= 32)
        assertEquals(alex, tokens.playerFor(first))
        assertNull(tokens.playerFor(first.dropLast(1)))

        val restarted = WikiTokens(file).also(WikiTokens::load)
        assertEquals(alex, restarted.playerFor(first))
        val second = restarted.reset(alex)
        assertNull(restarted.playerFor(first))
        assertEquals(alex, restarted.playerFor(second))
    }

    @Test
    fun `wiki commands are hidden while the wiki is off and the link for someone else is for operators`() {
        val root = WikiCommands.build().build()
        assertFalse(root.requirement.test(BattleContentCommandsTest.source(0)))
        assertFalse(root.requirement.test(BattleContentCommandsTest.source(4)))
        val link = root.getChild("link")
        assertFalse(link.requirement.test(BattleContentCommandsTest.source(0)))
        assertTrue(link.requirement.test(BattleContentCommandsTest.source(2)))
        assertTrue(root.getChild("reset").requirement.test(BattleContentCommandsTest.source(0)))
    }
}
