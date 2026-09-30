package jbro.cobblemon.mcc.internal.hub

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleHubTabConfigTest {
    private val league = "more_cobblemon_contents_league_challenge:league_terminal"
    private val tower = "more_cobblemon_contents_battle_tower:battle_tower_terminal"
    private val defaults = mapOf(
        league to listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, "more_cobblemon_contents:league_challenge"),
        tower to listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, "more_cobblemon_contents:battle_tower"),
    )

    @Test
    fun `the command shows the dashboard, the shop and PvP by default`() {
        assertEquals(listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, "more_cobblemon_contents:pvp"), BattleHubTabConfig.defaults(defaults).command)
    }

    @Test
    fun `written defaults read back unchanged and complete`() {
        val config = BattleHubTabConfig.defaults(defaults)
        val read = BattleHubTabConfig.read(BattleHubTabConfig.write(config), defaults)
        assertEquals(config, read.config)
        assertFalse(read.incomplete)
        assertTrue(read.problems.isEmpty())
    }

    @Test
    fun `an admin list replaces a terminal's tabs and a new terminal gets its defaults`() {
        val read = BattleHubTabConfig.read("""{"command": ["more_cobblemon_contents:dashboard"],
            "terminals": {"$league": ["more_cobblemon_contents:league_challenge", "more_cobblemon_contents:pvp"]}}""", defaults)
        assertEquals(listOf(BattleHubIds.DASHBOARD), read.config.command)
        assertEquals(listOf("more_cobblemon_contents:league_challenge", "more_cobblemon_contents:pvp"), read.config.terminals[league])
        assertEquals(defaults[tower], read.config.terminals[tower])
        assertTrue(read.incomplete)
        assertTrue(read.problems.isEmpty())
    }

    @Test
    fun `a broken list falls back to its default and is reported`() {
        listOf("[]", "\"more_cobblemon_contents:shop\"", "[\"Not An Id\"]", "[\"more_cobblemon_contents:shop\", \"more_cobblemon_contents:shop\"]").forEach { tabs ->
            val read = BattleHubTabConfig.read("""{"command": $tabs, "terminals": {}}""", defaults)
            assertEquals(BattleHubTabConfig.DEFAULT_COMMAND, read.config.command, tabs)
            assertEquals(1, read.problems.size, tabs)
        }
    }

    @Test
    fun `terminals of mods not installed now keep their entries`() {
        val gone = "more_cobblemon_contents_removed:terminal"
        val config = BattleHubTabConfig.defaults(defaults).let { it.copy(terminals = it.terminals + (gone to listOf(BattleHubIds.SHOP))) }
        val read = BattleHubTabConfig.read(BattleHubTabConfig.write(config), defaults)
        assertEquals(listOf(BattleHubIds.SHOP), read.config.terminals[gone])
    }

    @Test
    fun `malformed JSON throws`() {
        assertThrows(Exception::class.java) { BattleHubTabConfig.read("{", defaults) }
    }

    @Test
    fun `the file is created with the defaults and a broken file is left alone`() {
        val directory = Files.createTempDirectory("hub-tabs")
        val path = directory.resolve("more-cobblemon-contents").resolve("hub_tabs.json")
        BattleHubTabConfigFile.load(path)
        assertEquals(BattleHubTabConfig.DEFAULT_COMMAND, BattleHubTabConfig.read(Files.readString(path), emptyMap()).config.command)
        Files.writeString(path, """{"command": [], "terminals": {}}""")
        BattleHubTabConfigFile.load(path)
        assertEquals(BattleHubTabConfig.DEFAULT_COMMAND, BattleHubTabConfigFile.current.command)
        assertEquals("""{"command": [], "terminals": {}}""", Files.readString(path))
    }
}
