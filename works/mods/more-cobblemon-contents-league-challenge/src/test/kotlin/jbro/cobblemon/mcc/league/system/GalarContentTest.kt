package jbro.cobblemon.mcc.league.system

import com.google.gson.JsonParser
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GalarContentTest {
    private val ns = "more_cobblemon_contents_league_challenge"
    private fun json(kind: String, id: String): String = javaClass.getResourceAsStream("/data/$ns/league-challenge/$kind/$id.json")!!
        .bufferedReader().use { it.readText() }
    private fun catalog(): LeagueCatalog = LeagueCatalogParser.parse(LeagueCatalogParser.directories.associateWith { kind ->
        val ids = when (kind) {
            "leagues" -> listOf("galar")
            "appearances" -> LeagueTeamDataTest.galarPeople.map { "galar/$it" }
            else -> LeagueTeamDataTest.galarTeams
        }
        ids.associate { "$ns:$it" to json(kind, it) }
    }, "$ns:galar")
    private fun fields(line: String) = line.split(' ').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun `bundled routes have eight stages ten leaders seven cup encounters and both version choices`() {
        val c = catalog()
        assertEquals(36, c.challenges.size)
        assertEquals(8, c.gymStages.size)
        assertEquals(10, c.gyms.size)
        assertEquals(listOf("bea", "allister"), c.gymStages[3].map { it.substringAfterLast('/') })
        assertEquals(listOf("gordie", "melony"), c.gymStages[5].map { it.substringAfterLast('/') })
        assertEquals(7, c.finals.size)
        assertEquals(listOf("$ns:galar/cup_bea", "$ns:galar/cup_allister"), c.finalChoices["$ns:galar/cup_bea"])
        assertEquals("$ns:sinnoh", c.unlockLeague)
        assertEquals("DYNAMAX", LeagueEngine(c).mechanic(LeagueProgress()))
        c.challenges.values.forEach { ch ->
            assertEquals("DYNAMAX", ch.mechanic)
            assertEquals(if (ch.id.substringAfter(':') in listOf("galar/raihan", "galar/raihan_hard")) "DOUBLE" else "SINGLE", ch.format)
        }
        assertEquals(listOf(22, 26, 29, 38, 40, 44, 48, 50), c.gymStages.map { LeagueEngine(c).battleCap(it.first()) })
        assertEquals(67, LeagueEngine(c).battleCap(c.finals.first()))
    }

    @Test fun `normal gym parties preserve original levels abilities moves and empty held items`() {
        val c = catalog()
        val expected = listOf(listOf(19,20), listOf(22,23,24), listOf(25,25,27), listOf(34,34,35,36), listOf(34,34,35,36),
            listOf(36,36,37,38), listOf(40,40,41,42), listOf(40,40,41,42), listOf(44,45,45,46), listOf(46,47,46,48))
        c.gyms.forEachIndexed { i, id ->
            val team = c.challenges.getValue(id).team
            assertEquals(expected[i], team.map { fields(it).getValue("level").toInt() })
            team.forEach { line ->
                val f = fields(line)
                assertNull(f["held_item"])
                assertTrue(f.filterKeys { it.endsWith("_iv") }.values.all { it == "20" })
                assertTrue(f.filterKeys { it.endsWith("_ev") }.values.all { it == "0" })
            }
        }
        assertEquals("magicalleaf,round,rapidspin", fields(c.challenges.getValue("$ns:galar/milo").team.first())["moves"])
        assertEquals("zenmode", fields(c.challenges.getValue("$ns:galar/melony").team[1])["ability"])
        assertEquals("false", fields(c.challenges.getValue("$ns:galar/nessa").team.last())["gmax_factor"])
        assertEquals("true", fields(c.challenges.getValue("$ns:galar/cup_nessa").team.last())["gmax_factor"])
    }

    @Test fun `hard teams use six trained members with role based evs and no assault vest status move`() {
        val c = catalog()
        c.challenges.filterKeys { it.endsWith("_hard") }.values.forEach { ch ->
            assertEquals(6, ch.team.size)
            assertEquals(if ("/cup_" in ch.id || ch.id.endsWith("/leon_hard")) 5 else 4, ch.skill)
            ch.team.forEach { line ->
                val f = fields(line)
                assertTrue(f.filterKeys { it.endsWith("_iv") }.values.all { it == "31" })
                assertEquals(508, f.filterKeys { it.endsWith("_ev") }.values.sumOf(String::toInt))
                assertNotNull(f["held_item"])
                if (f["held_item"] == "cobblemon:assault_vest")
                    assertEquals("fakeout,drainpunch,knockoff,icepunch", f["moves"])
            }
        }
        val pex = fields(c.challenges.getValue("$ns:galar/nessa_hard").team[2])
        assertEquals("252", pex["hp_ev"])
        assertEquals("252", pex["defence_ev"])
        assertEquals("0", pex["speed_ev"])
    }

    @Test fun `every appearance is bundled as a valid vanilla size skin and every name is translated`() {
        val ko = JsonParser.parseString(javaClass.getResourceAsStream("/assets/$ns/lang/ko_kr.json")!!.bufferedReader().use { it.readText() }).asJsonObject
        val en = JsonParser.parseString(javaClass.getResourceAsStream("/assets/$ns/lang/en_us.json")!!.bufferedReader().use { it.readText() }).asJsonObject
        catalog().challenges.values.forEach { ch ->
            val skin = requireNotNull(ch.skin).substringAfter(':')
            val image = ImageIO.read(javaClass.getResourceAsStream("/assets/$ns/$skin"))
            assertEquals(64, image.width); assertEquals(64, image.height)
            assertTrue((image.getRGB(11,11) ushr 24) > 0)
            assertTrue(ko.has(ch.nameKey)); assertTrue(en.has(ch.nameKey))
            ch.scenes.values.flatten().forEach { assertTrue(ko.has(it) && en.has(it), it) }
        }
    }

    @Test fun `locked region and another regional run cannot be bypassed by a direct request`() {
        val c = catalog()
        assertEquals("league_locked", assertThrows(IllegalStateException::class.java) { LeagueWorldRules.requireCanChallenge(c, emptyMap()) }.message)
        val unlocked = mapOf("$ns:sinnoh" to LeagueProgress(champion = true))
        assertDoesNotThrow { LeagueWorldRules.requireCanChallenge(c, unlocked) }
        val active = unlocked.mapValues { (_, p) -> p.copy(run = LeagueRun(java.util.UUID.randomUUID(), listOf(c.challenges.getValue(c.gyms.first())), listOf("snapshot"))) }
        assertEquals("run_active", assertThrows(IllegalStateException::class.java) { LeagueWorldRules.requireCanChallenge(c, active) }.message)
    }
}
