package jbro.cobblemon.morebattlecontent.client

import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.client.render.RenderTypeTextureBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowHologramShaderResourcesTest {
    @Test
    fun `dedicated entity shader resources are packaged with animated scanline uniforms`() {
        val root = "/assets/cobblemon_more_battle_content/shaders/core/shadow_hologram"
        val definition = resource("$root.json")
        val vertex = resource("$root.vsh")
        val fragment = resource("$root.fsh")
        val json = JsonParser.parseString(definition).asJsonObject

        assertEquals("cobblemon_more_battle_content:shadow_hologram", json["vertex"].asString)
        assertEquals("cobblemon_more_battle_content:shadow_hologram", json["fragment"].asString)
        assertTrue(json.getAsJsonArray("samplers").any { it.asJsonObject["name"].asString == "Sampler0" })
        assertTrue(json.getAsJsonArray("uniforms").any { it.asJsonObject["name"].asString == "GameTime" })
        assertTrue(json.getAsJsonArray("uniforms").any { it.asJsonObject["name"].asString == "CameraWorldPosition" })
        assertTrue(vertex.contains("worldPosition = Position + CameraWorldPosition"))
        assertTrue(vertex.contains("out vec3 worldPosition"))
        assertTrue(fragment.contains("in vec3 worldPosition"))
        assertTrue(fragment.contains("worldPosition.y"))
        assertFalse(fragment.contains("modelPosition.y"), "scanlines must not use camera-relative model coordinates")
        assertFalse(fragment.contains("gl_FragCoord"), "model noise must stay in world space when the camera moves")
        assertTrue(fragment.contains("scanline"))
        assertTrue(fragment.contains("GameTime"))
        assertTrue(fragment.contains("GameTime * 1350.0"), "scanline speed must be 1.5x the original rate")
        assertTrue(vertex.contains("glitchOffset"), "video glitch must displace model vertices")
        assertTrue(fragment.contains("channelShift"), "video glitch must split sampled color channels")
        assertTrue(fragment.contains("singleScanLine"), "the model must use one soft scanline instead of triple bundles")
        assertTrue(fragment.contains("SINGLE_SCAN_WIDTH = 0.085"), "the gradient line width must be five times wider")
        assertTrue(fragment.contains("exp(-normalizedDistance * normalizedDistance)"), "the thick line must fade continuously")
        assertFalse(fragment.contains("SINGLE_SCAN_CORE"), "a flat opaque line core must not return")
        assertTrue(fragment.contains("SHADOW_DARK_YELLOW"), "the model must use a blackened yellow hologram tint")
        assertTrue(fragment.contains("vec3(0.38, 0.31, 0.075)"))
        assertFalse(fragment.contains("SHADOW_GRAY"), "the discarded silver model tint must not return")
        assertFalse(fragment.contains("vec3 cyan"), "the model must not reuse the terrain hologram color")
        assertTrue(fragment.contains("SCAN_POSITION_JITTER = 0.022"), "scanline spacing must vary more strongly")
        assertTrue(vertex.contains("pulseIndex"), "glitch events must be split into short pulses")
        assertTrue(vertex.contains("regionSequence"), "vertex displacement must advance through body regions")
        assertTrue(fragment.contains("regionSequence"), "signal noise must follow the displaced body region")
        assertFalse(fragment.contains("tripleScanPair"), "widely separated fixed scanline groups must not return")
        assertFalse(fragment.contains("irregularScanlines"), "the old multi-line group must not return")
        assertFalse(fragment.contains("glitchBand"), "the discarded bright-line glitch must not return")
    }

    @Test
    fun `render type texture accessors are client only mixins`() {
        val stream = javaClass.classLoader.getResourceAsStream("cobblemon_more_battle_content.mixins.json")
        assertNotNull(stream)
        val root = stream!!.reader().use { JsonParser.parseReader(it).asJsonObject }
        val clientMixins = root.getAsJsonArray("client").map { it.asString }.toSet()

        assertEquals(
            setOf(
                "client.BattleMoveSelectionMixin",
                "client.ScreenPvpInviteClickMixin",
                "client.RenderTypeCompositeAccessor",
                "client.RenderTypeCompositeStateAccessor",
                "client.RenderTextureStateAccessor",
            ),
            clientMixins,
        )
    }

    @Test
    fun `directly loaded texture bridge is outside the reserved mixin package tree`() {
        assertFalse(
            RenderTypeTextureBridge::class.java.packageName.startsWith(
                "jbro.cobblemon.morebattlecontent.internal.mixin",
            ),
        )
    }

    private fun resource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
        assertNotNull(stream, "$path must be packaged")
        return stream!!.bufferedReader().use { it.readText() }
    }
}
