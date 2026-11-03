package jbro.cobblemon.mcc.internal.hub

import java.nio.file.Files
import jbro.cobblemon.mcc.api.reload.MccReloadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentSwitchConfigTest {
    private val tower = "more_cobblemon_contents:battle_tower"
    private val pwt = "more_cobblemon_contents:pwt"
    private val installed = listOf(tower, pwt)

    @Test
    fun `every installed content is on by default and an unlisted one stays on`() {
        val config = ContentSwitchConfig.defaults(installed)
        assertTrue(installed.all(config::enabled))
        assertTrue(ContentSwitchConfig(emptyMap()).enabled(tower))
    }

    @Test
    fun `a missing file section or entry is on and asks for the file to be completed`() {
        listOf("{}", """{"contents": {}}""", """{"contents": {"$tower": false}}""").forEach { json ->
            val read = ContentSwitchConfig.read(json, installed)
            assertTrue(read.config.enabled(pwt), json)
            assertTrue(read.incomplete, json)
            assertTrue(read.problems.isEmpty(), json)
        }
        assertFalse(ContentSwitchConfig.read("""{"contents": {"$tower": false}}""", installed).config.enabled(tower))
    }

    @Test
    fun `an entry that is not true or false stays on and is reported`() {
        listOf("\"off\"", "0", "null", "[]").forEach { value ->
            val read = ContentSwitchConfig.read("""{"contents": {"$tower": $value, "$pwt": true}}""", installed)
            assertTrue(read.config.enabled(tower), value)
            assertEquals(1, read.problems.size, value)
        }
        assertEquals(1, ContentSwitchConfig.read("""{"contents": []}""", installed).problems.size)
    }

    @Test
    fun `contents of mods not installed now keep their entries and a bad one is reported`() {
        val gone = "more_cobblemon_contents:removed"
        val read = ContentSwitchConfig.read("""{"contents": {"$gone": false, "Not An Id": true, "$tower": true, "$pwt": true}}""", installed)
        assertFalse(read.config.enabled(gone))
        assertEquals(1, read.problems.size)
        assertEquals(read.config, ContentSwitchConfig.read(ContentSwitchConfig.write(read.config), installed + gone).config)
    }

    @Test
    fun `written defaults read back unchanged and complete`() {
        val config = ContentSwitchConfig.defaults(installed)
        val read = ContentSwitchConfig.read(ContentSwitchConfig.write(config), installed)
        assertEquals(config, read.config)
        assertFalse(read.incomplete)
        assertTrue(read.problems.isEmpty())
    }

    @Test
    fun `malformed JSON throws`() {
        assertThrows(Exception::class.java) { ContentSwitchConfig.read("{", installed) }
    }

    @Test
    fun `the file is created on, switches apply on reload, and a broken file keeps the switches in use`() {
        val path = Files.createTempDirectory("content-switches").resolve("more-cobblemon-contents").resolve("content_switches.json")
        assertInstanceOf(MccReloadResult.Applied::class.java, ContentSwitchConfigFile.load(path, installed))
        assertEquals(ContentSwitchConfig.defaults(installed), ContentSwitchConfig.read(Files.readString(path), installed).config)

        Files.writeString(path, """{"contents": {"$pwt": false}}""")
        assertInstanceOf(MccReloadResult.Applied::class.java, ContentSwitchConfigFile.load(path, installed))
        assertFalse(ContentSwitchConfigFile.current.enabled(pwt))
        // The missing Tower entry was written in, on.
        assertTrue(ContentSwitchConfig.read(Files.readString(path), installed).config.enabled(tower))

        listOf("{", """{"contents": {"$pwt": "on"}}""").forEach { broken ->
            Files.writeString(path, broken)
            assertInstanceOf(MccReloadResult.Kept::class.java, ContentSwitchConfigFile.load(path, installed), broken)
            assertFalse(ContentSwitchConfigFile.current.enabled(pwt), broken)
            assertEquals(broken, Files.readString(path))
        }
    }
}
