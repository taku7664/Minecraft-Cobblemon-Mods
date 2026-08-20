package jbro.cobblemon.morebattlecontent.client

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowTerrainHologramShaderResourcesTest {
    @Test
    fun `terrain compositor packages depth isolated hologram contract`() {
        val root = "/assets/cobblemon_more_battle_content/shaders/core/shadow_terrain_hologram"
        val definition = resource("$root.json")
        val vertex = resource("$root.vsh")
        val fragment = resource("$root.fsh")
        val json = JsonParser.parseString(definition).asJsonObject

        assertEquals("cobblemon_more_battle_content:shadow_terrain_hologram", json["vertex"].asString)
        assertEquals("cobblemon_more_battle_content:shadow_terrain_hologram", json["fragment"].asString)
        val samplers = json.getAsJsonArray("samplers").map { it.asJsonObject["name"].asString }.toSet()
        assertEquals(setOf("SceneSampler", "BackgroundSampler", "DepthSampler"), samplers)
        val uniforms = json.getAsJsonArray("uniforms").map { it.asJsonObject["name"].asString }.toSet()
        assertTrue(
            uniforms.containsAll(
                setOf(
                    "GameTime",
                    "EffectAgeSeconds",
                    "EffectStrength",
                    "InverseViewProjection",
                    "ArenaCenterRelative",
                    "ArenaOpponentDirection",
                    "CameraWorldPosition",
                ),
            ),
        )

        assertTrue(vertex.contains("TexCoord"))
        assertTrue(fragment.contains("DepthSampler"))
        assertTrue(fragment.contains("SKY_DEPTH_THRESHOLD"))
        assertTrue(fragment.contains("orderedDither"))
        assertTrue(fragment.contains("risingScan"))
        assertTrue(fragment.contains("originalLuminance"))
        assertTrue(fragment.contains("BackgroundSampler"))
        assertTrue(fragment.contains("worldRelative"))
        assertTrue(fragment.contains("dFdx(worldPosition)"))
        assertTrue(fragment.contains("dFdy(worldPosition)"))
        assertTrue(fragment.contains("surfaceNormal"))
        assertTrue(fragment.contains("surfaceDistance"))
        assertTrue(fragment.contains("arenaCenterWorld"))
        assertTrue(fragment.contains("ARENA_INNER_RADIUS"))
        assertTrue(fragment.contains("ARENA_OUTER_RADIUS"))
        assertTrue(fragment.contains("smoothstep(ARENA_INNER_RADIUS, ARENA_OUTER_RADIUS, arenaDistance)"))
        assertFalse(fragment.contains("1.0 - smoothstep(ARENA_INNER_RADIUS, ARENA_OUTER_RADIUS, arenaDistance)"))
        assertTrue(fragment.contains("softScan"))
        assertTrue(fragment.contains("exp(-normalizedDistance * normalizedDistance)"))
        assertTrue(fragment.contains("RING_SPACING = 1.8"))
        assertTrue(fragment.contains("gaussianCore"))
        assertTrue(fragment.contains("gaussianHalo"))
        assertTrue(fragment.contains("CENTER_HOLOGRAM_WHITE"))
        assertTrue(fragment.contains("mix(HOLOGRAM_CYAN, CENTER_HOLOGRAM_WHITE, centerWhiteness)"))
        assertTrue(fragment.contains("backgroundReveal *= spatialStrength"))
        assertFalse(fragment.contains("stadiumBase"), "the center must not be covered by a separate round floor tile")
        assertFalse(fragment.contains("centerComposited"), "the center must use the same terrain hologram as the exterior")
        assertTrue(fragment.contains("centerLedMask"))
        assertTrue(fragment.contains("ledCellCenter"))
        assertTrue(fragment.contains("LED_CORE_INNER_RADIUS = 0.19"))
        assertTrue(fragment.contains("LED_CORE_OUTER_RADIUS = 0.40"))
        assertTrue(fragment.contains("LED_HALO_RADIUS = 0.52"))
        assertTrue(fragment.contains("ArenaOpponentDirection"))
        assertTrue(fragment.contains("POKEBALL_RED"))
        assertTrue(fragment.contains("POKEBALL_CHARCOAL"))
        assertTrue(fragment.contains("BASE_TERRAIN_TINT_STRENGTH = 0.61"))
        assertTrue(fragment.contains("CENTER_TERRAIN_TINT_STRENGTH = 0.92"))
        assertTrue(fragment.contains("terrainTintStrength = mix("))
        assertTrue(fragment.contains("opponentHalf"))
        assertTrue(fragment.contains("centerBandMask"))
        assertTrue(fragment.contains("buttonRingMask"))
        assertTrue(fragment.contains("buttonCoreMask"))
        assertTrue(fragment.contains("regularLedMask"))
        assertTrue(fragment.contains("repeatingTap"))
        assertTrue(fragment.contains("LED_PATTERN_PERIOD_SECONDS"))
        assertTrue(fragment.contains("mod(max(EffectAgeSeconds, 0.0), LED_PATTERN_PERIOD_SECONDS)"))
        assertFalse(fragment.contains("idleChase"), "the faint chase must not hide the repeating full LED pattern")
        assertTrue(fragment.contains("EffectAgeSeconds"))
        assertTrue(fragment.contains("surfaceUpMask"), "the LED floor must stay on upward-facing terrain")
        assertFalse(fragment.contains("fineScan"), "dense secondary rings must not return")
        assertFalse(fragment.contains("verticalPixel"), "scanlines must not remain anchored to screen pixels")
    }

    @Test
    fun `arena led floor is a separate branch that leaves the terrain hologram untouched`() {
        val root = "/assets/cobblemon_more_battle_content/shaders/core/shadow_terrain_hologram"
        val fragment = resource("$root.fsh")
        val uniforms = JsonParser.parseString(resource("$root.json")).asJsonObject
            .getAsJsonArray("uniforms")
            .map { it.asJsonObject["name"].asString }
            .toSet()

        assertTrue(uniforms.contains("LedFloorRadius"), "the arena floor radius must reach the shader")
        assertTrue(fragment.contains("uniform float LedFloorRadius"))
        assertTrue(
            fragment.contains("if (LedFloorRadius > 0.001) {"),
            "the arena must take its own branch so the overworld hologram path is never re-entered",
        )
        assertTrue(fragment.contains("vec3 arenaLedFloor(vec3 sceneColor, vec3 worldDelta)"))

        // The hologram path must remain exactly what the Battle Tower and Battle Factory already use.
        assertTrue(fragment.contains("float arenaHeightMask = 1.0 - smoothstep(1.35, 2.40, abs(worldDelta.y));"))
        assertTrue(fragment.contains("vec3 hologramTerrain = mix(scene.rgb, projectedTerrain, clamp(EffectStrength, 0.0, 1.0));"))

        // Straight fronts travelling inward along the player-to-opponent axis, not expanding rings.
        assertTrue(fragment.contains("dot(worldDelta.xz, ArenaOpponentDirection)"))
        assertTrue(fragment.contains("LedFloorRadius * (1.0 - travel)"))
        assertFalse(
            fragment.contains("arenaLedFloor") && fragment.contains("ARENA_LED_SWEEP_SECONDS = 0"),
            "the sweep period must be a real duration",
        )

        // The fronts must dim away where they meet instead of the cycle clipping the soft band,
        // and a rest gap must separate one wave from the next.
        assertTrue(fragment.contains("ARENA_LED_FADE_SECONDS"))
        assertTrue(fragment.contains("ARENA_LED_REST_SECONDS = 1.0"))
        assertTrue(fragment.contains("mod(max(EffectAgeSeconds, 0.0), ARENA_LED_CYCLE_SECONDS)"))
        assertTrue(
            fragment.contains("clamp(cycle / ARENA_LED_SWEEP_SECONDS, 0.0, 1.0)"),
            "the fronts must hold at the centre rather than wrapping straight back to the rim",
        )
        assertFalse(
            fragment.contains("mod(max(EffectAgeSeconds, 0.0), ARENA_LED_SWEEP_SECONDS)"),
            "cycling on the sweep alone is what clipped the band at the meeting point",
        )
    }

    private fun resource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
        assertNotNull(stream, "$path must be packaged")
        return stream!!.bufferedReader().use { it.readText() }
    }
}
