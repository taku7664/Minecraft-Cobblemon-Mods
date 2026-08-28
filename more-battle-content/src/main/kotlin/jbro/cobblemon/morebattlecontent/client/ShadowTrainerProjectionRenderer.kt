package jbro.cobblemon.morebattlecontent.client

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexSorting
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattle
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.shadow.HideShadowTrainerPayload
import jbro.cobblemon.morebattlecontent.internal.shadow.ShadowTrainerProjection
import jbro.cobblemon.morebattlecontent.internal.shadow.ShadowTrainerProjectionState
import jbro.cobblemon.morebattlecontent.internal.shadow.ShowShadowTrainerPayload
import kotlin.math.roundToInt
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f

/** Renders a client-only copy of the challenger; no shadow entity is added to either world. */
internal object ShadowTrainerProjectionRenderer {
    private val state = ShadowTrainerProjectionState()
    private var renderedBattleId: UUID? = null
    private var shadowPlayer: ShadowPlayer? = null
    private var pendingShaderPackFrame: TrainerHologramRenderFrame? = null

    fun register() {
        MbcClientSessionReset.onReset("trainer hologram", ::clear)
        ClientPlayNetworking.registerGlobalReceiver(ShowShadowTrainerPayload.TYPE) { payload, context ->
            context.client().execute {
                val isNewProjection = state.current()?.battleId != payload.projection.battleId
                state.show(payload.projection)
                if (isNewProjection) {
                    clearCachedPlayer()
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(HideShadowTrainerPayload.TYPE) { payload, context ->
            context.client().execute {
                state.hide(payload.battleId)
                if (state.current() == null) clearCachedPlayer()
            }
        }
        WorldRenderEvents.START.register { pendingShaderPackFrame = null }
        WorldRenderEvents.AFTER_ENTITIES.register(::renderBeforeExternalFinalization)
        WorldRenderEvents.LAST.register(::prepareShaderPackRender)
    }

    private fun renderBeforeExternalFinalization(context: WorldRenderContext) {
        if (ExternalShaderPackState.isInUse()) return
        val buffers = context.consumers() ?: return
        val frame = TrainerHologramRenderFrame.capture(context) ?: return
        render(frame, buffers)
    }

    private fun prepareShaderPackRender(context: WorldRenderContext) {
        pendingShaderPackFrame = if (
            ExternalShaderPackState.isInUse() && !ExternalShaderPackState.isRenderingShadowPass() &&
            state.current() != null
        ) {
            TrainerHologramRenderFrame.capture(context)
        } else {
            null
        }
    }

    /** Draws the original GLSL model after Iris has finalized its world framebuffer. */
    @JvmStatic
    fun renderAfterExternalShaderPack() {
        val frame = pendingShaderPackFrame ?: return
        pendingShaderPackFrame = null
        if (!ExternalShaderPackState.isInUse() || ExternalShaderPackState.isRenderingShadowPass()) return
        val client = Minecraft.getInstance()
        val buffers = client.renderBuffers().bufferSource()
        frame.withCapturedRenderSystemState {
            try {
                render(frame, buffers)
            } finally {
                buffers.endBatch()
            }
        }
    }

    private fun render(frame: TrainerHologramRenderFrame, buffers: MultiBufferSource) {
        val projection = state.current() ?: return
        val client = Minecraft.getInstance()
        val sourcePlayer = client.player ?: return
        val level = client.level ?: return
        val poseStack = frame.newPoseStack()
        val partialTick = frame.partialTick
        val shadow = shadowPlayer(level, projection)
        copyVisibleEquipment(sourcePlayer, shadow)
        place(
            shadow,
            projection,
            ShadowTrainerDisplayNameResolver.resolve(projection, CobblemonClient.battle, sourcePlayer.uuid),
        )

        val camera = frame.cameraPosition
        val relative = frame.relativePosition(Vec3(projection.x, projection.y, projection.z))
        ShadowHologramShader.setCameraWorldPosition(camera.x, camera.y, camera.z)
        ShadowHologramFloorRenderer.render(
            poseStack,
            buffers,
            relative.x,
            relative.y,
            relative.z,
        )
        renderPass(
            client,
            shadow,
            poseStack,
            buffers,
            relative.x,
            relative.y,
            relative.z,
            projection.yaw,
            partialTick,
            camera.y,
        )
    }

    private fun shadowPlayer(level: ClientLevel, projection: ShadowTrainerProjection): ShadowPlayer {
        val cached = shadowPlayer
        if (cached != null && cached.clientLevel === level && renderedBattleId == projection.battleId) return cached
        return ShadowPlayer(level, GameProfile(projection.profileId, projection.profileName)).also {
            shadowPlayer = it
            renderedBattleId = projection.battleId
        }
    }

    private fun place(player: ShadowPlayer, projection: ShadowTrainerProjection, displayName: Component?) {
        player.setPos(projection.x, projection.y, projection.z)
        player.yRot = projection.yaw
        player.yRotO = projection.yaw
        player.yBodyRot = projection.yaw
        player.yBodyRotO = projection.yaw
        player.yHeadRot = projection.yaw
        player.yHeadRotO = projection.yaw
        player.setGlowingTag(false)
        player.isInvisible = true
        player.customName = displayName
        player.isCustomNameVisible = displayName != null
    }

    private fun copyVisibleEquipment(source: net.minecraft.client.player.LocalPlayer, target: ShadowPlayer) {
        HOLOGRAM_ARMOR_SLOTS.forEach { slot -> target.setItemSlot(slot, source.getItemBySlot(slot).copy()) }
    }

    private fun renderPass(
        client: Minecraft,
        shadow: ShadowPlayer,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        partialTick: Float,
        cameraY: Double,
    ) {
        val gameTicks = (client.level?.gameTime ?: 0L).toDouble() + partialTick
        poseStack.pushPose()
        try {
            client.entityRenderDispatcher.render(
                shadow,
                x,
                y,
                z,
                yaw,
                partialTick,
                poseStack,
                HologramBufferSource(
                    delegate = buffers,
                    gameTicks = gameTicks,
                    cameraY = cameraY,
                ),
                LightTexture.FULL_BRIGHT,
            )
        } finally {
            poseStack.popPose()
        }
    }

    private fun clear() {
        state.clear()
        pendingShaderPackFrame = null
        clearCachedPlayer()
    }

    private fun clearCachedPlayer() {
        shadowPlayer = null
        renderedBattleId = null
    }

    private class ShadowPlayer(level: ClientLevel, profile: GameProfile) : RemotePlayer(level, profile) {
        override fun shouldShowName(): Boolean = customName != null
        override fun isInvisibleTo(player: Player): Boolean = false
    }

    private val HOLOGRAM_ARMOR_SLOTS = listOf(
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD,
    )

}

/** Immutable camera transform captured before Iris tears down the world's render state. */
internal class TrainerHologramRenderFrame private constructor(
    private val poseMatrix: Matrix4f,
    private val normalMatrix: Matrix3f,
    private val modelViewMatrix: Matrix4f,
    private val projectionMatrix: Matrix4f,
    private val vertexSorting: VertexSorting,
    val cameraPosition: Vec3,
    val partialTick: Float,
) {
    fun newPoseStack(): PoseStack = PoseStack().also { target ->
        target.last().pose().set(poseMatrix)
        target.last().normal().set(normalMatrix)
    }

    fun relativePosition(worldPosition: Vec3): Vec3 = worldPosition.subtract(cameraPosition)

    fun <T> withCapturedRenderSystemState(block: () -> T): T {
        val previousProjection = Matrix4f(RenderSystem.getProjectionMatrix())
        val previousVertexSorting = RenderSystem.getVertexSorting()
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        return try {
            modelViewStack.set(modelViewMatrix)
            RenderSystem.applyModelViewMatrix()
            RenderSystem.setProjectionMatrix(projectionMatrix, vertexSorting)
            block()
        } finally {
            RenderSystem.setProjectionMatrix(previousProjection, previousVertexSorting)
            modelViewStack.popMatrix()
            RenderSystem.applyModelViewMatrix()
        }
    }

    companion object {
        fun capture(context: WorldRenderContext): TrainerHologramRenderFrame? {
            val source = context.matrixStack() ?: return null
            val camera = context.camera().position
            return capture(
                source = source,
                cameraPosition = Vec3(camera.x, camera.y, camera.z),
                partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false),
                modelViewMatrix = Matrix4f(RenderSystem.getModelViewMatrix()),
                projectionMatrix = Matrix4f(RenderSystem.getProjectionMatrix()),
                vertexSorting = RenderSystem.getVertexSorting(),
            )
        }

        internal fun capture(
            source: PoseStack,
            cameraPosition: Vec3,
            partialTick: Float,
            modelViewMatrix: Matrix4f = Matrix4f(),
            projectionMatrix: Matrix4f = Matrix4f(),
            vertexSorting: VertexSorting = VertexSorting.DISTANCE_TO_ORIGIN,
        ): TrainerHologramRenderFrame {
            return TrainerHologramRenderFrame(
                poseMatrix = Matrix4f(source.last().pose()),
                normalMatrix = Matrix3f(source.last().normal()),
                modelViewMatrix = Matrix4f(modelViewMatrix),
                projectionMatrix = Matrix4f(projectionMatrix),
                vertexSorting = vertexSorting,
                cameraPosition = Vec3(cameraPosition.x, cameraPosition.y, cameraPosition.z),
                partialTick = partialTick,
            )
        }
    }
}

internal object ShadowTrainerDisplayNameResolver {
    fun resolve(
        projection: ShadowTrainerProjection,
        battle: ClientBattle?,
        localPlayerId: UUID,
    ): Component? {
        if (battle == null || battle.battleId != projection.battleId) return null
        val ownSide = battle.sides.firstOrNull { side -> side.actors.any { it.uuid == localPlayerId } } ?: return null
        return battle.sides.asSequence()
            .filter { it !== ownSide }
            .flatMap { it.actors.asSequence() }
            .firstOrNull()
            ?.displayName
    }
}

internal object ShadowHologramAppearance {
    const val FALLBACK_RED = 0.38F
    const val FALLBACK_GREEN = 0.31F
    const val FALLBACK_BLUE = 0.075F

    fun alpha(@Suppress("UNUSED_PARAMETER") sourceAlpha: Int, opacity: Float): Int =
        (255 * opacity.coerceIn(0F, 1F)).roundToInt()
}

private class HologramBufferSource(
    private val delegate: MultiBufferSource,
    private val gameTicks: Double,
    private val cameraY: Double,
) : MultiBufferSource {
    override fun getBuffer(renderType: RenderType): VertexConsumer {
        val fallback = {
            HologramVertexConsumer(
                delegate = ShadowHologramShader.shaderPackFallbackBuffer(
                    delegate,
                    renderType,
                ) { delegate.getBuffer(renderType) },
                gameTicks = gameTicks,
                cameraY = cameraY,
            )
        }
        return ShadowHologramShader.buffer(delegate, renderType, fallback)
    }
}

private class HologramVertexConsumer(
    private val delegate: VertexConsumer,
    private val gameTicks: Double,
    private val cameraY: Double,
) : VertexConsumer {
    private var signal = ShadowHologramFallbackSignal.sample(gameTicks, cameraY)

    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
        signal = ShadowHologramFallbackSignal.sample(gameTicks, y + cameraY)
        delegate.addVertex(x + signal.horizontalOffset, y, z)
        return this
    }

    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer {
        delegate.setColor(
            (red * ShadowHologramAppearance.FALLBACK_RED * signal.brightness).roundToInt().coerceIn(0, 255),
            (green * ShadowHologramAppearance.FALLBACK_GREEN * signal.brightness).roundToInt().coerceIn(0, 255),
            (blue * ShadowHologramAppearance.FALLBACK_BLUE * signal.brightness).roundToInt().coerceIn(0, 255),
            ShadowHologramAppearance.alpha(alpha, signal.alpha),
        )
        return this
    }

    override fun setUv(u: Float, v: Float): VertexConsumer {
        delegate.setUv(u, v)
        return this
    }

    override fun setUv1(u: Int, v: Int): VertexConsumer {
        delegate.setUv1(u, v)
        return this
    }

    override fun setUv2(u: Int, v: Int): VertexConsumer {
        delegate.setUv2(u, v)
        return this
    }

    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer {
        delegate.setNormal(x, y, z)
        return this
    }
}
