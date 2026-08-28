package jbro.cobblemon.morebattlecontent.client

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
import org.lwjgl.system.MemoryStack

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
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute(::clear) }
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
        if (ExternalShaderPackState.isRenderingShadowPass()) return
        try {
            preserveFramebufferBindings { active ->
                ensureTargets(active.width, active.height)
                copyFramebuffer(active, requireNotNull(backgroundTarget), GL11.GL_COLOR_BUFFER_BIT)
                backgroundCaptured = true
            }
        } catch (exception: RuntimeException) {
            backgroundCaptured = false
            warnOnce("Failed to capture the pre-terrain framebuffer; skipping the terrain hologram frame", exception)
        }
    }

    private fun compositeTerrain(context: WorldRenderContext) {
        val snapshot = transition.snapshot(System.nanoTime()) ?: return
        if (ExternalShaderPackState.isRenderingShadowPass()) return
        if (!backgroundCaptured || snapshot.strength <= 0F) return
        val shader = ShadowTerrainHologramShader.activeShader() ?: return

        try {
            preserveFramebufferBindings { active ->
                if (active.width <= 0 || active.height <= 0) return@preserveFramebufferBindings
                ensureTargets(active.width, active.height)
                val background = backgroundTarget ?: return@preserveFramebufferBindings
                val terrain = terrainTarget ?: return@preserveFramebufferBindings
                copyFramebuffer(active, terrain, GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
                bindForDrawing(active)

                shader.setSampler("SceneSampler", terrain.colorTextureId)
                shader.setSampler("BackgroundSampler", background.colorTextureId)
                shader.setSampler("DepthSampler", terrain.depthTextureId)
                shader.getUniform("EffectStrength")?.set(snapshot.strength)
                shader.getUniform("EffectAgeSeconds")?.set(snapshot.effectAgeSeconds)
                shader.getUniform("GameTime")?.set(
                    (System.nanoTime() % EFFECT_TIME_PERIOD_NANOS).toFloat() / EFFECT_TIME_PERIOD_NANOS,
                )
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

                preserveFixedFunctionState {
                    RenderSystem.disableDepthTest()
                    RenderSystem.depthMask(false)
                    RenderSystem.disableBlend()
                    RenderSystem.disableCull()
                    RenderSystem.setShader { shader }
                    drawFullscreenQuad()
                }
            }
        } catch (exception: RuntimeException) {
            warnOnce("Failed to composite the terrain hologram; skipping the terrain effect", exception)
        }
    }

    private fun ensureTargets(width: Int, height: Int) {
        if (backgroundTarget?.viewWidth == width && backgroundTarget?.viewHeight == height &&
            terrainTarget?.viewWidth == width && terrainTarget?.viewHeight == height
        ) return

        destroyTargets()
        backgroundTarget = TextureTarget(width, height, false, Minecraft.ON_OSX).apply {
            setFilterMode(GL11.GL_NEAREST)
        }
        terrainTarget = TextureTarget(width, height, true, Minecraft.ON_OSX).apply {
            setFilterMode(GL11.GL_NEAREST)
        }
    }

    private fun copyFramebuffer(source: FramebufferBindings, destination: TextureTarget, mask: Int) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.drawFramebuffer)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId)
        GL30.glBlitFramebuffer(
            source.viewportX,
            source.viewportY,
            source.viewportX + source.width,
            source.viewportY + source.height,
            0,
            0,
            source.width,
            source.height,
            mask,
            GL11.GL_NEAREST,
        )
    }

    private fun bindForDrawing(bindings: FramebufferBindings) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, bindings.readFramebuffer)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bindings.drawFramebuffer)
        GL11.glViewport(bindings.viewportX, bindings.viewportY, bindings.width, bindings.height)
    }

    private inline fun <T> preserveFramebufferBindings(block: (FramebufferBindings) -> T): T {
        val bindings = captureFramebufferBindings()
        return try {
            block(bindings)
        } finally {
            bindForDrawing(bindings)
        }
    }

    private fun captureFramebufferBindings(): FramebufferBindings {
        val viewport = MemoryStack.stackPush().use { stack ->
            val values = stack.mallocInt(4)
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, values)
            IntArray(4) { values[it] }
        }
        return FramebufferBindings(
            readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
            drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            viewportX = viewport[0],
            viewportY = viewport[1],
            width = viewport[2],
            height = viewport[3],
        )
    }

    private inline fun preserveFixedFunctionState(block: () -> Unit) {
        val depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        val blend = GL11.glIsEnabled(GL11.GL_BLEND)
        val cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
        val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        try {
            block()
        } finally {
            if (depthTest) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest()
            if (blend) RenderSystem.enableBlend() else RenderSystem.disableBlend()
            if (cull) RenderSystem.enableCull() else RenderSystem.disableCull()
            RenderSystem.depthMask(depthMask)
        }
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

    private data class FramebufferBindings(
        val readFramebuffer: Int,
        val drawFramebuffer: Int,
        val viewportX: Int,
        val viewportY: Int,
        val width: Int,
        val height: Int,
    )

    private const val FADE_DURATION_NANOS = 600_000_000L
    private const val EFFECT_TIME_PERIOD_NANOS = 60_000_000_000L
}
