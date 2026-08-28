package jbro.cobblemon.morebattlecontent.client

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShadowHologramRenderIsolationTest {
    @Test
    fun `optional trainer projection networking cannot escape into battle lifecycle`() {
        val networking = Files.readString(
            Path.of(
                "src/main/kotlin/jbro/cobblemon/morebattlecontent/internal/shadow/" +
                    "ShadowTrainerProjection.kt",
            ),
        )
        val objectBody = networking.substringAfter("internal object ShadowTrainerProjectionNetworking")

        assertTrue(objectBody.substringAfter("fun show").substringBefore("fun hide").contains("catch (exception: RuntimeException)"))
        assertTrue(objectBody.substringAfter("fun hide").contains("catch (exception: RuntimeException)"))
        assertTrue(objectBody.contains("continuing without the optional effect"))
        assertTrue(objectBody.contains("core battle cleanup will continue"))
    }

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
    fun `shader pack terrain keeps a distinct pre terrain background for dither reveal`() {
        val renderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowTerrainHologramRenderer.kt"),
        )
        val captureBackground = renderer.substringAfter("private fun captureBackground")
            .substringBefore("private fun compositeTerrain")

        assertFalse(captureBackground.contains("if (ExternalShaderPackState.isInUse()) return"))
        assertTrue(renderer.contains("shaderPackBackgroundCaptured"))
        assertTrue(renderer.contains("val shaderPackBackground = backgroundTarget"))
        assertTrue(
            renderer.contains(
                "drawComposite(frame, snapshot, active, finalScene, shaderPackBackground, terrain, true, shader)",
            ),
        )
    }

    @Test
    fun `terrain render targets are released only after the fade is no longer retained`() {
        val renderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowTerrainHologramRenderer.kt"),
        )
        val idleRelease = renderer.substringAfter("private fun releaseTargetsWhenIdle")
            .substringBefore("private fun captureBackground")

        assertTrue(renderer.contains("WorldRenderEvents.START.register"))
        assertTrue(renderer.contains("releaseTargetsWhenIdle(System.nanoTime())"))
        assertTrue(idleRelease.contains("transition.snapshot(nowNanos)"))
        assertTrue(idleRelease.contains("!transition.isRetained()"))
        assertTrue(idleRelease.contains("destroyTargets()"))
    }

    @Test
    fun `shader pack model uses the original core shader after external finalization`() {
        val projectionRenderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowTrainerProjectionRenderer.kt"),
        )
        val lateMixin = Files.readString(
            Path.of(
                "src/main/java/jbro/cobblemon/morebattlecontent/internal/mixin/client/" +
                    "LevelRendererLateHologramMixin.java",
            ),
        )

        assertTrue(projectionRenderer.contains("WorldRenderEvents.LAST.register(::prepareShaderPackRender)"))
        assertTrue(projectionRenderer.contains("if (ExternalShaderPackState.isInUse()) return"))
        assertTrue(projectionRenderer.contains("fun renderAfterExternalShaderPack()"))
        assertTrue(projectionRenderer.contains("client.renderBuffers().bufferSource()"))
        assertTrue(projectionRenderer.contains("buffers.endBatch()"))
        assertTrue(projectionRenderer.contains("ShadowHologramShader.buffer"))
        assertFalse(projectionRenderer.contains("useCoreShader = !shaderPackActive"))
        assertTrue(lateMixin.contains("ShadowTerrainHologramRenderer.compositeAfterExternalShaderPack()"))
        assertTrue(lateMixin.contains("ShadowTrainerProjectionRenderer.renderAfterExternalShaderPack()"))
    }

    @Test
    fun `shader pack model snapshots camera matrices instead of retaining the mutable render context`() {
        val projectionRenderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowTrainerProjectionRenderer.kt"),
        )
        val terrainRenderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowTerrainHologramRenderer.kt"),
        )

        assertFalse(projectionRenderer.contains("pendingShaderPackContext: WorldRenderContext?"))
        assertTrue(projectionRenderer.contains("pendingShaderPackFrame: TrainerHologramRenderFrame?"))
        assertTrue(projectionRenderer.contains("TrainerHologramRenderFrame.capture(context)"))
        assertTrue(projectionRenderer.contains("Matrix4f(source.last().pose())"))
        assertTrue(projectionRenderer.contains("Matrix3f(source.last().normal())"))
        assertTrue(projectionRenderer.contains("Matrix4f(RenderSystem.getModelViewMatrix())"))
        assertTrue(projectionRenderer.contains("Matrix4f(RenderSystem.getProjectionMatrix())"))
        assertTrue(projectionRenderer.contains("frame.withCapturedRenderSystemState"))
        assertFalse(projectionRenderer.contains("poseStack.translate(x, y, z)"))
        assertFalse(projectionRenderer.contains("poseStack.translate(-x, -y, -z)"))
        assertTrue(projectionRenderer.contains("HOLOGRAM_ARMOR_SLOTS.forEach"))
        assertFalse(projectionRenderer.contains("EquipmentSlot.entries.forEach"))
        assertFalse(terrainRenderer.contains("pendingShaderPackContext: WorldRenderContext?"))
        assertTrue(terrainRenderer.contains("pendingShaderPackFrame: TerrainHologramRenderFrame?"))
        assertTrue(terrainRenderer.contains("TerrainHologramRenderFrame.capture(context)"))
    }

    @Test
    fun `floor glow writes depth so the late shader compositor preserves it`() {
        val floorRenderer = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/client/ShadowHologramFloorRenderer.kt"),
        )

        assertTrue(floorRenderer.contains("setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)"))
    }
}
