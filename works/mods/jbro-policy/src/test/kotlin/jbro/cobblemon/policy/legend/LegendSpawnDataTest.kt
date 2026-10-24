package jbro.cobblemon.policy.legend

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegendSpawnDataTest {
    private fun json(path: String): JsonObject = JsonParser.parseString(
        checkNotNull(javaClass.getResource(path)) { "$path missing" }.readText(),
    ).asJsonObject

    private val spawns = json("/resourcepacks/legendary_spawns/data/jbro_policy/spawn_pool_world/legendary_wild_spawns.json")
        .getAsJsonArray("spawns").map { it.asJsonObject }

    @Test
    fun `every spawn explicitly limits dimensions so shared biome tags cannot leak into other worlds`() {
        val endOnly = setOf("cosmoem", "deoxys", "eternatus", "hoopa", "palkia")
        val overworldAndEnd = setOf("arceus", "cosmog", "genesect", "magearna", "necrozma", "rayquaza", "regidrago", "typenull")
        val ancient = setOf("koraidon", "gougingfire", "ragingbolt", "walkingwake")
        val future = setOf("miraidon", "ironboulder", "ironcrown", "ironleaves")
        for (spawn in spawns) {
            val species = spawn.get("pokemon").asString.substringBefore(' ')
            val expected = when (species) {
                in ancient -> setOf("cobblemon_dimensions:ancient")
                in future -> setOf("cobblemon_dimensions:future")
                in endOnly -> setOf("minecraft:the_end")
                in overworldAndEnd -> setOf("minecraft:overworld", "minecraft:the_end")
                "chiyu", "heatran" -> setOf("minecraft:the_nether")
                "entei" -> setOf("minecraft:overworld", "minecraft:the_nether")
                "giratina" -> setOf("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")
                else -> setOf("minecraft:overworld")
            }
            val actual = spawn.getAsJsonObject("condition").getAsJsonArray("dimensions")?.map { it.asString }?.toSet()
            assertEquals(expected, actual, species)
        }
    }

    @Test
    fun `aquatic spawns select water or lava instead of a land-only preset`() {
        val aquatic = spawns.filter { it.get("spawnablePositionType").asString != "grounded" }
        assertEquals(9, aquatic.size)
        for (spawn in aquatic) {
            val species = spawn.get("pokemon").asString
            val expected = if (species == "chiyu") "lava" else "water"
            assertEquals(listOf(expected), spawn.getAsJsonArray("presets").map { it.asString }, species)
            assertEquals("#minecraft:$expected", json("/data/cobblemon/spawn_detail_presets/$expected.json")
                .getAsJsonObject("condition").get("fluid").asString, species)
        }
    }

    @Test
    fun `conditions and presets use fields supported by the actual Cobblemon position class`() {
        for (spawn in spawns) {
            val type = spawn.get("spawnablePositionType").asString.replaceFirstChar { it.uppercase() }
            // Reflection does not initialize Minecraft registries. Read the inherited Gson field schema from the
            // dependency itself: neededBaseBlocks is valid for Grounded, but silently ignored for Surface/Submerged.
            val conditionClass = Class.forName("com.cobblemon.mod.common.api.spawning.condition.${type}SpawningCondition", false, javaClass.classLoader)
            val fields = generateSequence(conditionClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                .map { it.name }.toSet() + "type"
            val objects = mutableListOf(spawn)
            for (preset in spawn.getAsJsonArray("presets")) objects += json("/data/cobblemon/spawn_detail_presets/${preset.asString}.json")
            for (obj in objects) for (key in listOf("condition", "anticondition")) {
                val condition = obj.getAsJsonObject(key) ?: continue
                assertTrue(condition.size() > 0, "${spawn.get("id")} has an empty $key")
                assertTrue(fields.containsAll(condition.keySet()), "${spawn.get("id")} ignores $key fields ${condition.keySet() - fields}")
            }
        }
    }
}
