package jbro.cobblemon.morebattlecontent.client

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattle
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.shadow.HideShadowTrainerPayload
import jbro.cobblemon.morebattlecontent.internal.shadow.ShadowTrainerProjection
import jbro.cobblemon.morebattlecontent.internal.shadow.ShadowTrainerProjectionState
import jbro.cobblemon.morebattlecontent.internal.shadow.ShowShadowTrainerPayload
import kotlin.math.roundToInt
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
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

/** Renders a client-only copy of the challenger; no shadow entity is added to either world. */
internal object ShadowTrainerProjectionRenderer {
    private val state = ShadowTrainerProjectionState()
    private var renderedBattleId: UUID? = null
    private var shadowPlayer: ShadowPlayer? = null

    fun register() {
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
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear() }
        WorldRenderEvents.AFTER_ENTITIES.register(::render)
    }

    private fun render(context: WorldRenderContext) {
        val projection = state.current() ?: return
        val client = Minecraft.getInstance()
        val sourcePlayer = client.player ?: return
        val level = client.level ?: return
        val poseStack = context.matrixStack() ?: return
        val buffers = context.consumers() ?: return
        val partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false)
        val shadow = shadowPlayer(level, projection)
        copyVisibleEquipment(sourcePlayer, shadow)
        place(
            shadow,
            projection,
            ShadowTrainerDisplayNameResolver.resolve(projection, CobblemonClient.battle, sourcePlayer.uuid),
        )

        val camera = context.camera().position
        val relativeX = projection.x - camera.x
        val relativeY = projection.y - camera.y
        val relativeZ = projection.z - camera.z
        ShadowHologramShader.setCameraWorldPosition(camera.x, camera.y, camera.z)
        ShadowHologramFloorRenderer.render(
            poseStack,
            buffers,
            relativeX,
            relativeY,
            relativeZ,
        )
        renderPass(
            client,
            shadow,
            poseStack,
            buffers,
            relativeX,
            relativeY,
            relativeZ,
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
        EquipmentSlot.entries.forEach { slot -> target.setItemSlot(slot, source.getItemBySlot(slot).copy()) }
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
        val shaderPackActive = ExternalShaderPackState.isInUse()
        poseStack.pushPose()
        poseStack.translate(x, y, z)
        poseStack.translate(-x, -y, -z)
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
                    useCoreShader = !shaderPackActive,
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
    private val useCoreShader: Boolean,
    private val gameTicks: Double,
    private val cameraY: Double,
) : MultiBufferSource {
    override fun getBuffer(renderType: RenderType): VertexConsumer {
        val fallback = {
            HologramVertexConsumer(
                delegate = delegate.getBuffer(renderType),
                gameTicks = gameTicks,
                cameraY = cameraY,
            )
        }
        return if (useCoreShader) {
            ShadowHologramShader.buffer(delegate, renderType, fallback)
        } else {
            fallback()
        }
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
