package jbro.cobblemon.policy

import com.google.gson.JsonParser
import jbro.cobblemon.policy.welcome.WelcomeKit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyDataTest {
    private fun json(path: String) = JsonParser.parseString(checkNotNull(javaClass.getResource(path)) { "$path missing" }.readText()).asJsonObject

    @Test
    fun `legendary spawns use the Cobblemon 1_8 position key`() {
        val spawns = json("/resourcepacks/legendary_spawns/data/jbro_policy/spawn_pool_world/legendary_wild_spawns.json").getAsJsonArray("spawns")
        assertEquals(103, spawns.size())
        for (spawn in spawns.map { it.asJsonObject }) {
            val id = spawn.get("id").asString
            assertTrue(id.startsWith("jbro-legendary-")) { id }
            assertFalse(spawn.has("context")) { "$id still uses the pre-1.8 context key" }
            assertTrue(spawn.get("spawnablePositionType").asString in setOf("grounded", "surface", "submerged")) { id }
            assertEquals("ultra-rare", spawn.get("bucket").asString)
        }
    }

    @Test
    fun `experience candy crafting is unlocked and XS cooking yields three`() {
        for (path in listOf("campfire_pot/exp_candy_xs", "mod_compatibility/create/campfire_pot/exp_candy_xs_create")) {
            val recipe = json("/resourcepacks/no_stat_candy_l_xl/data/cobblemon/recipe/$path.json")
            assertEquals("cobblemon:cooking_pot_shapeless", recipe.get("type").asString)
            assertEquals("cobblemon:exp_candy_xs", recipe.getAsJsonObject("result").get("id").asString)
            assertEquals(3, recipe.getAsJsonObject("result").get("count").asInt)
            if (path.startsWith("campfire_pot/")) assertFalse(recipe.has("fabric:load_conditions"))
            else assertEquals("fabric:all_mods_loaded", recipe.getAsJsonArray("fabric:load_conditions").single().asJsonObject.get("condition").asString)
        }
        val recipes = listOf("s", "m", "l", "xl").map { "campfire_pot/exp_candy_$it" } +
            listOf("xs_from_exp_candy_s", "s_from_exp_candy_m", "m_from_exp_candy_l", "l_from_exp_candy_xl").map { "exp_candy_$it" }
        for (path in recipes) {
            assertEquals(null, javaClass.getResource("/resourcepacks/no_stat_candy_l_xl/data/cobblemon/recipe/$path.json"))
        }
        for (stat in listOf("courage", "health", "mighty", "quick", "smart", "tough")) for (size in listOf("l", "xl")) {
            assertEquals(null, javaClass.getResource("/resourcepacks/no_stat_candy_l_xl/data/cobblemon/recipe/campfire_pot/${stat}_candy_$size.json"))
        }
    }

    @Test
    fun `the Ability Patch recipe never loads, so the BP shop is the only way to a hidden ability`() {
        val recipe = json("/resourcepacks/no_ability_patch/data/mega_showdown/recipe/ability_patch.json")
        assertTrue(recipe.has("fabric:load_conditions"))
        for (lang in listOf("ko_kr", "en_us")) assertTrue(json("/assets/jbro_policy/lang/$lang.json").has("pack.jbro_policy.no_ability_patch"))
    }

    @Test
    fun `player chat reads name colon message`() {
        val chat = json("/data/jbro_policy/chat_type/chat.json").getAsJsonObject("chat")
        val key = chat.get("translation_key").asString
        assertEquals(listOf("sender", "content"), chat.getAsJsonArray("parameters").map { it.asString })
        for (lang in listOf("ko_kr", "en_us")) assertEquals("%s: %s", json("/assets/jbro_policy/lang/$lang.json").get(key).asString)
    }

    @Test
    fun `plaza dimension uses the plaza biome`() {
        val settings = json("/data/jbro_policy/dimension/plaza.json").getAsJsonObject("generator").getAsJsonObject("settings")
        assertEquals("jbro_policy:plaza", settings.get("biome").asString)
        json("/data/jbro_policy/worldgen/biome/plaza.json")
    }

    @Test
    fun `lang files share their keys`() {
        assertEquals(json("/assets/jbro_policy/lang/en_us.json").keySet(), json("/assets/jbro_policy/lang/ko_kr.json").keySet())
    }

    @Test
    fun `only a brand new player gets the welcome kit`() {
        assertTrue(WelcomeKit.isFirstJoin(alreadyGranted = false, leaveGameCount = 0))
        assertFalse(WelcomeKit.isFirstJoin(alreadyGranted = true, leaveGameCount = 0))
        assertFalse(WelcomeKit.isFirstJoin(alreadyGranted = false, leaveGameCount = 3))
    }
}
