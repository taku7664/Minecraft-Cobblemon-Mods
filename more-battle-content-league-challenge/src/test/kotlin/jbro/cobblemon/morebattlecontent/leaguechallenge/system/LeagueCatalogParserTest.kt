package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeagueCatalogParserTest {
    private val ns = "cobblemon_more_battle_content_league_challenge"
    private val names = listOf("roark", "gardenia", "fantina", "maylene", "wake", "byron", "candice", "volkner", "aaron", "bertha", "flint", "lucian", "cynthia")
    private fun resources(): MutableMap<String, Map<String, String>> = LeagueCatalogParser.directories.associateWith { directory ->
        val ids = when (directory) { "leagues" -> listOf("active"); "appearances" -> listOf("default"); else -> names }
        ids.associate { name -> "$ns:$name" to javaClass.getResourceAsStream("/data/$ns/mbc-league-challenge/$directory/$name.json")!!.bufferedReader().use { it.readText() } }
    }.toMutableMap()

    @Test fun `bundled Platinum order and reference graph are valid`() {
        val catalog = LeagueCatalogParser.parse(resources(), "$ns:active")
        assertEquals(names.take(8).map { "$ns:$it" }, catalog.gyms)
        assertEquals(names.drop(8).map { "$ns:$it" }, catalog.finals)
        assertEquals("$ns:sinnoh", catalog.id)
        assertEquals(6, catalog.challenges.getValue("$ns:cynthia").team.size)
    }

    @Test fun `missing team rejects whole catalog`() {
        val resources = resources()
        resources["teams"] = resources.getValue("teams") - "$ns:cynthia"
        assertThrows(IllegalArgumentException::class.java) { LeagueCatalogParser.parse(resources, "$ns:active") }
    }

    @Test fun `duplicate gym is invalid`() {
        val resources = resources()
        val root = JsonParser.parseString(resources.getValue("leagues").getValue("$ns:active")).asJsonObject
        root.getAsJsonArray("gyms").set(1, root.getAsJsonArray("gyms")[0])
        resources["leagues"] = mapOf("$ns:active" to root.toString())
        assertThrows(IllegalArgumentException::class.java) { LeagueCatalogParser.parse(resources, "$ns:active") }
    }

    @Test fun `unsupported schema and remote skin are rejected`() {
        for (appearance in listOf("{\"schema_version\":2}", "{\"schema_version\":1,\"model\":\"default\",\"skin\":\"https://evil.test/skin.png\"}")) {
            val resources = resources()
            resources["appearances"] = mapOf("$ns:default" to appearance)
            assertThrows(IllegalArgumentException::class.java) { LeagueCatalogParser.parse(resources, "$ns:active") }
        }
    }

    @Test fun `negative reward is rejected rather than converted into a debit`() {
        val resources = resources()
        resources["rewards"] = resources.getValue("rewards") + ("$ns:roark" to "{\"schema_version\":1,\"unlock_cap\":20,\"first_bp\":-1,\"repeat_bp\":0}")
        assertThrows(IllegalArgumentException::class.java) { LeagueCatalogParser.parse(resources, "$ns:active") }
    }
}
