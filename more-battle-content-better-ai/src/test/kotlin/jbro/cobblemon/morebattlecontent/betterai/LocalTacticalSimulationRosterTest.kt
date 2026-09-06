package jbro.cobblemon.morebattlecontent.betterai

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalTacticalSimulationRosterTest {
    @Test
    fun `cooperation move coverage distinguishes absent source data from loader loss`() {
        val raw = LocalTacticalSimulationRoster.rentalSetRoots().flatMap { root ->
            root.getAsJsonArray("rental_sets").map { it.asJsonObject }
        }
        val loaded = LocalTacticalSimulationRoster.loadAll().entries
        val groups = linkedMapOf(
            "redirection" to setOf("followme", "ragepowder"),
            "protect" to setOf("protect"),
            "selected_setup" to setOf("swordsdance", "nastyplot", "calmmind", "dragondance"),
            "selected_ally_spread" to setOf("earthquake", "surf", "discharge"),
        )
        assertTrue(raw.isNotEmpty())
        assertEquals(raw.size, raw.map { it["set_id"].asString }.distinct().size)
        groups.forEach { (group, moves) ->
            val sourceIds = raw.filter { entry ->
                entry.getAsJsonArray("moves").any { it.asString.substringAfter(':') in moves }
            }.map { it["set_id"].asString }.toSet()
            val loadedIds = loaded.filter { entry ->
                entry.moves.any { it.id.substringAfter(':') in moves }
            }.map { it.setId }.toSet()
            println("COOPERATION_DATA group=$group source=${sourceIds.size} loaded=${loadedIds.size} " +
                "availability=${if (sourceIds.isEmpty()) "ABSENT_FROM_SOURCE" else "PRESENT_NOT_BEHAVIOR_VERIFIED"}")
            assertEquals(sourceIds, loadedIds, "$group presets lost or invented by the simulation loader")
        }
        // Presence is necessary, not sufficient: this does not prove compatible partner teams,
        // public move revelation, joint candidate retention, projected effects or good decisions.
    }

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
        assertEquals(5, slowbro.moves.single { it.id == "cobblemon:slackoff" }.pp)
        assertTrue(slowbro.moves.any { it.category.name == "STATUS" })
        assertTrue(damageLeague.entries.none { it.setId == slowbro.setId })
    }
}
