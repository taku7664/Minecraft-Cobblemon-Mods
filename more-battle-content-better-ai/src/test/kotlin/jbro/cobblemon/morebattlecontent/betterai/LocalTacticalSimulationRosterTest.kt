package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class LocalTacticalSimulationRosterTest {
    @Test
    fun `simulation roster uses complete fixed factory presets`() {
        val roster = LocalTacticalSimulationRoster.load()

        assertTrue(roster.entries.size >= 100, "Expected at least 100 complete damage-only presets")
        assertTrue(roster.entries.map { it.speciesId }.distinct().size >= 50, "Expected at least 50 species")
        roster.entries.forEach { entry ->
            assertEquals(4, entry.moves.size, entry.setId)
            assertEquals(4, entry.moves.map { it.id }.distinct().size, entry.setId)
            assertTrue(entry.abilityId.startsWith("cobblemon:"), entry.setId)
            assertTrue(entry.heldItemId.startsWith("cobblemon:"), entry.setId)
            assertTrue(entry.natureId.startsWith("cobblemon:"), entry.setId)
            assertTrue(entry.evs.total <= 510, entry.setId)
        }
    }

    @Test
    fun `every battle draws new legal teams from whole presets`() {
        val roster = LocalTacticalSimulationRoster.load()
        val random = Random(20_260_822)
        val teams = List(1_000) { roster.randomTeam(random, size = 3) }

        teams.forEach { team ->
            assertEquals(3, team.map { it.speciesId }.distinct().size)
            assertEquals(3, team.map { it.heldItemId }.distinct().size)
            team.forEach { selected ->
                assertEquals(selected, roster.entries.single { it.setId == selected.setId })
            }
        }
        val signatures = teams.map { team -> team.joinToString("|") { it.setId } }
        assertTrue(signatures.distinct().size >= 990, "Too many repeated teams: ${signatures.distinct().size}/1000")
        assertTrue(signatures.zipWithNext().none { (left, right) -> left == right }, "Adjacent battles reused the same team")
    }

    @Test
    fun `seeded random teams are varied and reproducible`() {
        val roster = LocalTacticalSimulationRoster.load()
        fun sequence() = Random(73_193).let { random ->
            List(100) { roster.randomTeam(random, size = 3).map { it.setId } }
        }

        val first = sequence()
        val replay = sequence()
        assertEquals(first, replay)
        assertTrue(first.distinct().size > 95)
    }

    @Test
    fun `scenario roster loads complete presets that contain status moves without changing damage league`() {
        val damageLeague = LocalTacticalSimulationRoster.load()
        val scenarioRoster = LocalTacticalSimulationRoster.loadAll()
        val slowbro = scenarioRoster.entries.single { it.setId == "slowbro_preset_3" }

        assertTrue(scenarioRoster.entries.size > damageLeague.entries.size)
        assertEquals(4, slowbro.moves.size)
        assertTrue(slowbro.moves.any { it.id == "cobblemon:slackoff" })
        assertTrue(slowbro.moves.any { it.category.name == "STATUS" })
        assertTrue(damageLeague.entries.none { it.setId == slowbro.setId })
    }
}
