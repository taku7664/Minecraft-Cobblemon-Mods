package kr.parkjh.pokefusion

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PokeFusionModuleContractTest {
    private val resources = Path.of("src/main/resources")

    @Test
    fun `modmenu entrypoint and optional dependency are declared`() {
        val metadata = JsonParser.parseString(Files.readString(resources.resolve("fabric.mod.json"))).asJsonObject

        assertEquals(
            "kr.parkjh.pokefusion.client.PokeFusionModMenu",
            metadata.getAsJsonObject("entrypoints").getAsJsonArray("modmenu")[0].asString
        )
        assertEquals("*", metadata.getAsJsonObject("suggests").get("modmenu").asString)
    }

    @Test
    fun `english and korean translations expose the same config keys`() {
        val english = language("en_us")
        val korean = language("ko_kr")

        assertEquals(english.keySet(), korean.keySet())
        assertTrue(english.has("config.pokefusion.command_permission_level"))
        assertTrue(english.has("config.pokefusion.scope"))
    }

    private fun language(code: String) = JsonParser.parseString(
        Files.readString(resources.resolve("assets/pokefusion/lang/$code.json"))
    ).asJsonObject
}
