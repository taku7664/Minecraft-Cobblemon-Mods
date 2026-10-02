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
    fun `stat candy L and XL recipes never load`() {
        for (stat in listOf("courage", "health", "mighty", "quick", "smart", "tough")) for (size in listOf("l", "xl")) {
            val recipe = json("/resourcepacks/no_stat_candy_l_xl/data/cobblemon/recipe/campfire_pot/${stat}_candy_$size.json")
            assertTrue(recipe.has("fabric:load_conditions")) { "${stat}_candy_$size" }
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
