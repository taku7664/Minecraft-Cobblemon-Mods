package jbro.cobblemon.morebattlecontent.client

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowHologramRenderIsolationTest {
    @Test
    fun `managed battle holograms never replace the Pokemon renderer`() {
        val mixins = Files.readString(Path.of("src/main/resources/cobblemon_more_battle_content.mixins.json"))
        val clientInitializer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/MoreBattleContentClient.kt"),
        )

        assertFalse(mixins.contains("PokemonHologramRendererMixin"))
        assertFalse(clientInitializer.contains("ShadowPokemonHologramShader"))
        assertFalse(
            Files.exists(
                Path.of(
                    "src/main/java/jbro/cobblemon/morebattlecontent/internal/mixin/client/" +
                        "PokemonHologramRendererMixin.java",
                ),
            ),
        )
    }

    @Test
    fun `terrain hologram never changes or rebuilds the external shader pack`() {
        val renderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowTerrainHologramRenderer.kt"),
        )
        val clientSources = Files.walk(Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.map(Files::readString).toList().joinToString("\n")
        }

        assertFalse(clientSources.contains("setShadersEnabled"))
        assertFalse(clientSources.contains("destroyEverything"))
        assertFalse(clientSources.contains("loadShaderpack"))
        assertFalse(renderer.contains("BattleHologramIrisSuspension"))
        assertTrue(renderer.contains("preserveFramebufferBindings"))

        val compositor = renderer.substringAfter("private fun compositeTerrain")
        assertTrue(
            compositor.indexOf("ensureTargets(active.width, active.height)") <
                compositor.indexOf("val background = backgroundTarget"),
            "The compositor must resize targets before retaining references to them",
        )
    }

    @Test
    fun `shader pack terrain is composited after the external pipeline finishes`() {
        val renderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowTerrainHologramRenderer.kt"),
        )
        val mixins = Files.readString(Path.of("src/main/resources/cobblemon_more_battle_content.mixins.json"))
        val lateMixin = Path.of(
            "src/main/java/jbro/cobblemon/morebattlecontent/internal/mixin/client/" +
                "LevelRendererLateHologramMixin.java",
        )

        assertTrue(renderer.contains("WorldRenderEvents.LAST.register(::prepareShaderPackComposite)"))
        assertTrue(renderer.contains("if (ExternalShaderPackState.isInUse()) return"))
        assertTrue(renderer.contains("FinalSceneSampler"))
        assertTrue(renderer.contains("FinalDepthSampler"))
        assertTrue(mixins.contains("client.LevelRendererLateHologramMixin"))
        assertTrue(Files.exists(lateMixin))
        val lateMixinSource = Files.readString(lateMixin)
        assertTrue(lateMixinSource.contains("priority = 900"))
        assertTrue(lateMixinSource.contains("@At(\"RETURN\")"))
        assertTrue(lateMixinSource.contains("compositeAfterExternalShaderPack"))
    }

    @Test
    fun `shader pack model fallback uses a translucent render type`() {
        val projectionRenderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowTrainerProjectionRenderer.kt"),
        )
        val shader = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowHologramShader.kt"),
        )

        assertTrue(projectionRenderer.contains("ShadowHologramShader.shaderPackFallbackBuffer"))
        assertTrue(shader.contains("shaderPackFallbackTypes"))
        assertTrue(shader.contains("GameRenderer.getRendertypeEntityTranslucentShader"))
    }

    @Test
    fun `floor glow writes depth so the late shader compositor preserves it`() {
        val floorRenderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowHologramFloorRenderer.kt"),
        )

        assertTrue(floorRenderer.contains("setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)"))
    }
}
