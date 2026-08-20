package jbro.cobblemon.morebattlecontent.client

import com.google.gson.JsonParser
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowPokemonHologramShaderTest {
    @Test
    fun `pokemon hologram resources are packaged with entity uniforms`() {
        val root = "/assets/cobblemon_more_battle_content/shaders/core/shadow_pokemon_hologram"
        val json = JsonParser.parseString(resource("$root.json")).asJsonObject
        val uniforms = json.getAsJsonArray("uniforms").map { it.asJsonObject["name"].asString }.toSet()

        assertEquals("cobblemon_more_battle_content:shadow_pokemon_hologram", json["vertex"].asString)
        assertEquals("cobblemon_more_battle_content:shadow_pokemon_hologram", json["fragment"].asString)
        assertTrue(json.getAsJsonArray("samplers").any { it.asJsonObject["name"].asString == "Sampler0" })
        assertTrue(uniforms.containsAll(setOf("ModelViewMat", "ProjMat", "ColorModulator", "CameraWorldPosition", "GameTime")))
        assertFalse(
            uniforms.any { it == "SceneSampler" || it == "BackgroundSampler" || it == "DepthSampler" },
            "an entity pass has no post-process samplers to bind",
        )
    }

    @Test
    fun `terrain hologram look is rebuilt on model geometry`() {
        val root = "/assets/cobblemon_more_battle_content/shaders/core/shadow_pokemon_hologram"
        val vertex = resource("$root.vsh")
        val fragment = resource("$root.fsh")

        assertTrue(fragment.contains("HOLOGRAM_CYAN"), "the Pokemon projection must use the terrain cyan")
        assertTrue(fragment.contains("vec3(0.055, 0.82, 1.0)"))
        assertTrue(fragment.contains("CENTER_HOLOGRAM_WHITE"))
        assertFalse(fragment.contains("SHADOW_DARK_YELLOW"), "the trainer projection tint must not leak in")
        assertTrue(fragment.contains("BASE_TERRAIN_TINT_STRENGTH = 0.61"), "tint strength must match the terrain pass")
        assertTrue(fragment.contains("mix(vec3(luminance), source.rgb, 0.42)"), "texture detail must survive as much as on terrain")
        assertTrue(fragment.contains("orderedDither"), "the pixel grid must come from the terrain 4x4 dither")
        assertTrue(fragment.contains("surfaceCurvature"), "creases must glow like the terrain edges")
        assertTrue(vertex.contains("in vec3 Normal"), "the model supplies its own normal instead of a depth rebuild")
        assertTrue(fragment.contains("in vec3 modelNormal"))
    }

    @Test
    fun `model hologram motion is carried over`() {
        val root = "/assets/cobblemon_more_battle_content/shaders/core/shadow_pokemon_hologram"
        val vertex = resource("$root.vsh")
        val fragment = resource("$root.fsh")

        assertTrue(vertex.contains("glitchOffset"), "the video glitch must displace vertices")
        assertTrue(vertex.contains("regionSequence"), "glitch pulses must walk through body regions")
        assertTrue(fragment.contains("channelShift"), "the glitch must split sampled colour channels")
        assertTrue(fragment.contains("GameTime * 1350.0"), "the scanline must climb at the model hologram rate")
        assertTrue(fragment.contains("SINGLE_SCAN_WIDTH = 0.085"), "the halo must stay as wide as the model line")
        assertTrue(fragment.contains("SCAN_CORE_WIDTH"), "the line needs a bright core inside the halo")
        assertTrue(vertex.contains("worldPosition = Position + CameraWorldPosition"))
        assertTrue(fragment.contains("worldPosition.y"), "the line must stay anchored in world space")
    }

    @Test
    fun `dedicated shader only accepts complete entity vertices`() {
        assertTrue(ShadowPokemonHologramShader.supports(DefaultVertexFormat.NEW_ENTITY))
        assertFalse(ShadowPokemonHologramShader.supports(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP))
        assertFalse(ShadowPokemonHologramShader.supports(DefaultVertexFormat.POSITION_COLOR))
    }

    @Test
    fun `fallback tint is the hologram cyan rather than the trainer yellow`() {
        assertEquals(0.055F, ShadowPokemonHologramAppearance.FALLBACK_RED)
        assertEquals(0.82F, ShadowPokemonHologramAppearance.FALLBACK_GREEN)
        assertEquals(1.0F, ShadowPokemonHologramAppearance.FALLBACK_BLUE)
        assertEquals(158, ShadowPokemonHologramAppearance.alpha(38, 0.62F))
    }

    private fun resource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
        assertNotNull(stream, "$path must be packaged")
        return stream!!.bufferedReader().use { it.readText() }
    }
}
