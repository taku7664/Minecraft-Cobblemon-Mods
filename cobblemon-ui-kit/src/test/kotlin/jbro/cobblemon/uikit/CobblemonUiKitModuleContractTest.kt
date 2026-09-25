package jbro.cobblemon.uikit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CobblemonUiKitModuleContractTest {
    @Test
    fun `fabric metadata declares a client only library entrypoint`() {
        val metadata = resourceJson("fabric.mod.json")

        assertEquals("cobblemon_ui_kit", metadata.get("id").asString)
        assertEquals("client", metadata.get("environment").asString)
        assertTrue(metadata.getAsJsonObject("entrypoints").getAsJsonArray("client").size() == 1)
        assertFalse(metadata.getAsJsonObject("entrypoints").has("main"))
    }

    @Test
    fun `english and korean bundles have the same nonblank keys`() {
        val english = resourceJson("assets/cobblemon_ui_kit/lang/en_us.json")
        val korean = resourceJson("assets/cobblemon_ui_kit/lang/ko_kr.json")

        assertEquals(english.keySet(), korean.keySet())
        assertTrue(english.entrySet().all { it.value.asString.isNotBlank() })
        assertTrue(korean.entrySet().all { it.value.asString.isNotBlank() })
        assertTrue(english.entrySet().all { !HANGUL.containsMatchIn(it.value.asString) })
    }

    @Test
    fun `public contract does not expose owo types`() {
        val publicTypes = listOf(
            UiButtonSpec::class.java,
            UiThemeSnapshot::class.java,
            UiThemeRegistry::class.java,
            UiButtonStyle::class.java,
            UiButtonMetrics::class.java
        )

        publicTypes.forEach { type ->
            val signatureTypes = buildList {
                type.declaredFields.mapTo(this) { it.type.name }
                type.declaredMethods.forEach { method ->
                    add(method.returnType.name)
                    method.parameterTypes.mapTo(this) { it.name }
                }
            }
            assertTrue(signatureTypes.none { it.startsWith("io.wispforest") }, type.name)
        }
    }

    private fun resourceJson(path: String): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream(path)
        assertNotNull(stream, "Missing resource: $path")
        return stream!!.reader().use { JsonParser.parseReader(it).asJsonObject }
    }

    private companion object {
        val HANGUL = Regex("[가-힣]")
    }
}
