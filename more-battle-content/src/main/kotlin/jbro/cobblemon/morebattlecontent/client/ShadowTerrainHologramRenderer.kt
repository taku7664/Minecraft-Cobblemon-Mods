package jbro.cobblemon.morebattlecontent.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.presentation.BattleArenaHologramProjection
import jbro.cobblemon.morebattlecontent.internal.presentation.HideBattleArenaHologramPayload
import jbro.cobblemon.morebattlecontent.internal.presentation.ShowBattleArenaHologramPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * Re-composites only the opaque/cutout terrain pass. Sky is captured before terrain and entities
 * are rendered afterwards, so the effect never recolors players, Pokemon, the Shadow, or HUDs.
 */
internal object ShadowTerrainHologramRenderer {
    private val transition = ShadowTerrainHologramTransition(FADE_DURATION_NANOS)
    private var backgroundTarget: TextureTarget? = null
    private var terrainTarget: TextureTarget? = null
    private var backgroundCaptured = false
    private var warned = false

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ShowBattleArenaHologramPayload.TYPE) { payload, context ->
            context.client().execute { show(payload.projection) }
        }
        ClientPlayNetworking.registerGlobalReceiver(HideBattleArenaHologramPayload.TYPE) { payload, context ->
            context.client().execute { hide(payload.battleId) }
        }
        WorldRenderEvents.START.register { backgroundCaptured = false }
        WorldRenderEvents.AFTER_SETUP.register(::captureBackground)
        WorldRenderEvents.BEFORE_ENTITIES.register(::compositeTerrain)
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear() }
    }

    fun show(projection: BattleArenaHologramProjection) {
        transition.show(projection, System.nanoTime())
    }

    fun hide(battleId: UUID) {
        transition.hide(battleId, System.nanoTime())
    }

    fun clear() {
        transition.clear()
        backgroundCaptured = false
        destroyTargets()
    }

    private fun captureBackground(@Suppress("UNUSED_PARAMETER") context: WorldRenderContext) {
        if (transition.snapshot(System.nanoTime()) == null) return
        val client = Minecraft.getInstance()
        val main = client.mainRenderTarget
        try {
            ensureTargets(main)
            copyFramebuffer(main, requireNotNull(backgroundTarget), GL11.GL_COLOR_BUFFER_BIT)
            backgroundCaptured = true
        } catch (exception: RuntimeException) {
            backgroundCaptured = false
            warnOnce("Failed to capture the pre-terrain framebuffer; skipping the terrain hologram frame", exception)
            main.bindWrite(false)
        }
    }

    private fun compositeTerrain(context: WorldRenderContext) {
        val snapshot = transition.snapshot(System.nanoTime()) ?: return
        if (!backgroundCaptured || snapshot.strength <= 0F) return
        val shader = ShadowTerrainHologramShader.activeShader() ?: return
        val client = Minecraft.getInstance()
        val main = client.mainRenderTarget
        val background = backgroundTarget ?: return
        val terrain = terrainTarget ?: return

        try {
            copyFramebuffer(main, terrain, GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
            main.bindWrite(false)

            shader.setSampler("SceneSampler", terrain.colorTextureId)
            shader.setSampler("BackgroundSampler", background.colorTextureId)
            shader.setSampler("DepthSampler", terrain.depthTextureId)
            shader.getUniform("EffectStrength")?.set(snapshot.strength)
            shader.getUniform("EffectAgeSeconds")?.set(snapshot.effectAgeSeconds)
            shader.getUniform("GameTime")?.set((System.nanoTime() % EFFECT_TIME_PERIOD_NANOS).toFloat() / EFFECT_TIME_PERIOD_NANOS)
            shader.getUniform("InverseViewProjection")?.set(
                Matrix4f(context.projectionMatrix()).mul(context.positionMatrix()).invert(),
            )
            val camera = context.camera().position
            shader.getUniform("ArenaCenterRelative")?.set(
                (snapshot.arenaCenter.x - camera.x).toFloat(),
                (snapshot.arenaCenter.y - camera.y).toFloat(),
                (snapshot.arenaCenter.z - camera.z).toFloat(),
            )
            shader.getUniform("ArenaOpponentDirection")?.set(
                snapshot.arenaDirection.x.toFloat(),
                snapshot.arenaDirection.z.toFloat(),
            )
            shader.getUniform("LedFloorRadius")?.set(snapshot.projection.ledFloorRadius.toFloat())
            shader.getUniform("CameraWorldPosition")?.set(
                camera.x.toFloat(),
                camera.y.toFloat(),
                camera.z.toFloat(),
            )

            RenderSystem.disableDepthTest()
            RenderSystem.depthMask(false)
            RenderSystem.disableBlend()
            RenderSystem.disableCull()
            RenderSystem.setShader { shader }
            drawFullscreenQuad()
        } catch (exception: RuntimeException) {
            warnOnce("Failed to composite the terrain hologram; skipping the terrain effect", exception)
        } finally {
            RenderSystem.depthMask(true)
            RenderSystem.enableDepthTest()
            RenderSystem.enableCull()
            main.bindWrite(false)
        }
    }

    private fun ensureTargets(main: RenderTarget) {
        if (backgroundTarget?.viewWidth == main.viewWidth && backgroundTarget?.viewHeight == main.viewHeight &&
            terrainTarget?.viewWidth == main.viewWidth && terrainTarget?.viewHeight == main.viewHeight
        ) return

        destroyTargets()
        backgroundTarget = TextureTarget(main.viewWidth, main.viewHeight, false, Minecraft.ON_OSX).apply {
            setFilterMode(GL11.GL_NEAREST)
        }
        terrainTarget = TextureTarget(main.viewWidth, main.viewHeight, true, Minecraft.ON_OSX).apply {
            setFilterMode(GL11.GL_NEAREST)
        }
        main.bindWrite(false)
    }

    private fun copyFramebuffer(source: RenderTarget, destination: RenderTarget, mask: Int) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId)
        GL30.glBlitFramebuffer(
            0,
            0,
            source.viewWidth,
            source.viewHeight,
            0,
            0,
            destination.viewWidth,
            destination.viewHeight,
            mask,
            GL11.GL_NEAREST,
        )
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId)
        source.bindWrite(false)
    }

    private fun drawFullscreenQuad() {
        val builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
        builder.addVertex(-1F, -1F, 0F).setUv(0F, 0F)
        builder.addVertex(1F, -1F, 0F).setUv(1F, 0F)
        builder.addVertex(1F, 1F, 0F).setUv(1F, 1F)
        builder.addVertex(-1F, 1F, 0F).setUv(0F, 1F)
        BufferUploader.drawWithShader(builder.buildOrThrow())
    }

    private fun destroyTargets() {
        backgroundTarget?.destroyBuffers()
        terrainTarget?.destroyBuffers()
        backgroundTarget = null
        terrainTarget = null
    }

    private fun warnOnce(message: String, exception: RuntimeException) {
        if (warned) return
        warned = true
        MoreBattleContent.LOGGER.error(message, exception)
    }

    private const val FADE_DURATION_NANOS = 600_000_000L
    private const val EFFECT_TIME_PERIOD_NANOS = 60_000_000_000L
}
