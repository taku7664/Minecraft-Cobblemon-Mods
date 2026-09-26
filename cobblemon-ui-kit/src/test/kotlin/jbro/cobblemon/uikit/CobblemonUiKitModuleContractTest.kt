package jbro.cobblemon.uikit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class CobblemonUiKitModuleContractTest {
    @Test
    fun `fabric metadata declares a client only library entrypoint`() {
        val metadata = resourceJson("fabric.mod.json")

        assertEquals("cobblemon_ui_kit", metadata.get("id").asString)
        assertEquals("client", metadata.get("environment").asString)
        assertTrue(metadata.getAsJsonObject("entrypoints").getAsJsonArray("client").size() == 1)
        assertFalse(metadata.getAsJsonObject("entrypoints").has("main"))
        assertEquals(
            ">=1.8.1 <1.9.0",
            metadata.getAsJsonObject("depends").get("cobblemon").asString
        )
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
            UiButtonMetrics::class.java,
            UiSurfaceTokens::class.java,
            UiSurfaceStyle::class.java,
            UiSurfaceOverrides::class.java,
            UiShape::class.java,
            UiFill::class.java,
            UiBorder::class.java,
            UiTabSpec::class.java,
            UiListItemSpec::class.java,
            UiBadgeSpec::class.java,
            UiToggleSpec::class.java,
            UiProgressSpec::class.java,
            UiScrollState::class.java
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

    @Test
    fun `component gallery never blurs its own widgets`() {
        val source = sourceText(
            "src/main/kotlin/jbro/cobblemon/uikit/client/ComponentGalleryScreen.kt"
        )

        assertFalse(source.contains("renderTransparentBackground("))
        assertTrue(source.contains("theme.colors.backdrop"))
        assertTrue(source.contains("override fun renderBackground("))
        assertTrue(source.contains(") = Unit"))
    }

    @Test
    fun `pixel theme ships deterministic nearest neighbor sprites`() {
        listOf(
            "assets/cobblemon_ui_kit/textures/gui/pixel/info.png",
            "assets/cobblemon_ui_kit/textures/gui/pixel/selector.png"
        ).forEach { path ->
            val stream = javaClass.classLoader.getResourceAsStream(path)
            assertNotNull(stream, "Missing pixel sprite: $path")
            val image = stream!!.use(ImageIO::read)
            assertNotNull(image, "Unreadable pixel sprite: $path")
            assertEquals(8, image.width, path)
            assertEquals(8, image.height, path)
        }
    }

    private fun resourceJson(path: String): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream(path)
        assertNotNull(stream, "Missing resource: $path")
        return stream!!.reader().use { JsonParser.parseReader(it).asJsonObject }
    }

    private fun sourceText(relativePath: String): String {
        val candidates = listOf(
            Path.of(relativePath),
            Path.of("cobblemon-ui-kit").resolve(relativePath)
        )
        val path = candidates.firstOrNull(Files::exists)
        assertNotNull(path, "Missing source: $relativePath")
        return Files.readString(path!!)
    }

    private companion object {
        val HANGUL = Regex("[가-힣]")
    }
}
