package jbro.cobblemon.mcc.league.system

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeagueCatalogParserTest {
    private val ns = "more_cobblemon_contents_league_challenge"
    private val names = listOf("roark", "gardenia", "fantina", "maylene", "wake", "byron", "candice", "volkner", "aaron", "bertha", "flint", "lucian", "cynthia")
    private fun resources(): MutableMap<String, Map<String, String>> = LeagueCatalogParser.directories.associateWith { directory ->
        val ids = when (directory) { "leagues" -> listOf("active"); "appearances" -> listOf("default") + names; else -> names + names.map { "${it}_hard" } }
        ids.associate { name -> "$ns:$name" to javaClass.getResourceAsStream("/data/$ns/league-challenge/$directory/$name.json")!!.bufferedReader().use { it.readText() } }
    }.toMutableMap()

    @Test fun `bundled Platinum order and reference graph are valid`() {
        val catalog = LeagueCatalogParser.parse(resources(), "$ns:active")
        assertEquals(names.take(8).map { "$ns:$it" }, catalog.gyms)
        assertEquals(names.drop(8).map { "$ns:$it" }, catalog.finals)
        assertEquals("$ns:sinnoh", catalog.id)
        assertEquals(6, catalog.challenges.getValue("$ns:cynthia").team.size)
        assertEquals(names.take(8).map { "$ns:${it}_hard" }, catalog.hardGyms)
        assertEquals(names.drop(8).map { "$ns:${it}_hard" }, catalog.hardFinals)
    }

    @Test fun `every League trainer wears its own RCT Trainers+ skin in both difficulties`() {
        val catalog = LeagueCatalogParser.parse(resources(), "$ns:active")
        val roles = names.associateWith { name -> when (name) { "cynthia" -> "champion"; in names.drop(8) -> "elite_four"; else -> "gym_leader" } }
        names.forEach { name ->
            listOf(name, "${name}_hard").forEach { id ->
                val skin = catalog.challenges.getValue("$ns:$id").skin
                assertTrue(skin != null && skin.matches(Regex("rctmod:textures/trainers/single/${roles.getValue(name)}_${name}_[0-9a-f]{4}\\.png")), "$id wears $skin")
            }
        }
    }

    @Test fun `the bundled league spawns wild Pokemon from level ten up to three below the cap, leaning by four chunk areas`() {
        assertEquals(WildLevelRule(belowCap = 10, spread = 7, regionChunks = 4, floorLevel = 10), LeagueCatalogParser.parse(resources(), "$ns:active").wildLevel)
    }

    @Test fun `wild level fields are optional and each falls back alone`() {
        val resources = resources()
        val root = JsonParser.parseString(resources.getValue("leagues").getValue("$ns:active")).asJsonObject
        root.remove("wild_level")
        resources["leagues"] = mapOf("$ns:active" to root.toString())
        assertEquals(WildLevelRule(), LeagueCatalogParser.parse(resources, "$ns:active").wildLevel)
        root.add("wild_level", JsonParser.parseString("{\"spread\":3}"))
        resources["leagues"] = mapOf("$ns:active" to root.toString())
        assertEquals(WildLevelRule(spread = 3), LeagueCatalogParser.parse(resources, "$ns:active").wildLevel)
        root.add("wild_level", JsonParser.parseString("{\"region_chunks\":0}"))
        resources["leagues"] = mapOf("$ns:active" to root.toString())
        assertThrows(IllegalArgumentException::class.java) { LeagueCatalogParser.parse(resources, "$ns:active") }
    }

    @Test fun `Cynthia speaks at every scene moment in both difficulties`() {
        val catalog = LeagueCatalogParser.parse(resources(), "$ns:active")
        listOf("cynthia", "cynthia_hard").forEach { id ->
            assertEquals(SCENE_MOMENTS, catalog.challenges.getValue("$ns:$id").scenes.keys, id)
        }
        assertEquals(emptyMap<String, List<String>>(), catalog.challenges.getValue("$ns:roark").scenes)
    }

    @Test fun `an unknown scene moment or an empty one rejects the catalog`() {
        listOf("{\"after_lunch\":[\"some.key\"]}", "{\"player_won\":[]}").forEach { scenes ->
            val resources = resources()
            val trainer = JsonParser.parseString(resources.getValue("trainers").getValue("$ns:roark")).asJsonObject
            trainer.add("scenes", JsonParser.parseString(scenes))
            resources["trainers"] = resources.getValue("trainers") + ("$ns:roark" to trainer.toString())
            assertThrows(IllegalArgumentException::class.java) { LeagueCatalogParser.parse(resources, "$ns:active") }
        }
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
            resources["appearances"] = resources.getValue("appearances").mapValues { appearance }
            assertThrows(IllegalArgumentException::class.java) { LeagueCatalogParser.parse(resources, "$ns:active") }
        }
    }

    @Test fun `negative reward is rejected rather than converted into a debit`() {
        val resources = resources()
        resources["rewards"] = resources.getValue("rewards") + ("$ns:roark" to "{\"schema_version\":1,\"unlock_cap\":20,\"first_bp\":-1,\"repeat_bp\":0}")
        assertThrows(IllegalArgumentException::class.java) { LeagueCatalogParser.parse(resources, "$ns:active") }
    }
}
