package jbro.cobblemon.morebattlecontent

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreModuleContractTest {
    @Test
    fun `core metadata and entrypoints match the new product identity`() {
        val stream = javaClass.classLoader.getResourceAsStream("fabric.mod.json")
        assertNotNull(stream, "fabric.mod.json must be packaged")

        val root = stream!!.reader().use { JsonParser.parseReader(it).asJsonObject }
        assertEquals("cobblemon_more_battle_content", root["id"].asString)
        assertEquals("Cobblemon: More Battle Content", root["name"].asString)
        assertEquals("*", root["environment"].asString)

        val main = root.getAsJsonObject("entrypoints").getAsJsonArray("main")[0].asJsonObject
        assertEquals("kotlin", main["adapter"].asString)
        assertEquals("jbro.cobblemon.morebattlecontent.MoreBattleContent", main["value"].asString)

        val client = root.getAsJsonObject("entrypoints").getAsJsonArray("client")[0].asJsonObject
        assertEquals("kotlin", client["adapter"].asString)
        assertEquals("jbro.cobblemon.morebattlecontent.client.MoreBattleContentClient", client["value"].asString)

        val depends = root.getAsJsonObject("depends")
        assertEquals(">=0.19.5", depends["fabricloader"].asString)
        assertEquals(">=1.14.1+kotlin.2.4.20", depends["fabric-language-kotlin"].asString)
        assertEquals(">=1.8.1 <1.9.0", depends["cobblemon"].asString)
        assertEquals("1.2.0+1.8.1+1.21.1-release", depends["mega_showdown"].asString)
        assertTrue(root.getAsJsonObject("suggests").has("better_cobblemon_battlecam"))

        val mixinConfigName = root.getAsJsonArray("mixins").single().asString
        assertEquals("cobblemon_more_battle_content.mixins.json", mixinConfigName)
        val mixinStream = javaClass.classLoader.getResourceAsStream(mixinConfigName)
        assertNotNull(mixinStream, "$mixinConfigName must be packaged")
        val mixinRoot = mixinStream!!.reader().use { JsonParser.parseReader(it).asJsonObject }
        assertEquals("JAVA_21", mixinRoot["compatibilityLevel"].asString)
        assertTrue(mixinRoot.getAsJsonArray("mixins").map { it.asString }.contains("BattleActorMixin"))
        assertTrue(mixinRoot.getAsJsonArray("mixins").map { it.asString }.contains("PlayerBattleActorMixin"))
        assertTrue(mixinRoot.getAsJsonArray("mixins").map { it.asString }.contains("PokemonBattleMixin"))
        assertTrue(mixinRoot.getAsJsonArray("mixins").map { it.asString }.contains("PokemonMixin"))

        assertDoesNotThrow { Class.forName(main["value"].asString) }
        assertDoesNotThrow { Class.forName(client["value"].asString) }
    }
}
