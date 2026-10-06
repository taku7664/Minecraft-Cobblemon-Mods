package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.entity.npc.NPCEntity
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin
import kotlin.math.atan2
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity

/**
 * The trainer a player sees across a managed battle: a Cobblemon NPC of MCC's `managed_trainer` class standing where
 * the virtual anchor would, in the content's skin, so Cobblemon's own send-out, recall, win and lose motions play on
 * it and the battle camera has someone to look at. One per player: a new battle sends the last one away first, so a
 * tower run never shows two. It cannot be pushed, hurt or challenged, stays a while after the battle for its closing
 * words, and is never kept: one found on load (after a crash) is removed.
 */
internal object Cobblemon173TrainerBody {
    val NPC_CLASS: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "managed_trainer")
    private const val TAG = "mcc_managed_trainer"
    /** Long enough for the closing scene the client plays before it lets the battle close. */
    private const val LINGER_TICKS = 300

    private class Body(val npc: NPCEntity, var leaveAtTick: Int? = null)

    private val bodies = HashMap<UUID, Body>()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            val now = server.tickCount
            bodies.entries.removeIf { (_, body) ->
                val leave = body.leaveAtTick
                (body.npc.isRemoved || leave != null && now >= leave).also { if (it) body.npc.discard() }
            }
        }
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (TAG in entity.tags && bodies.values.none { it.npc === entity }) entity.discard()
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> dismiss(handler.player.uuid) }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            bodies.values.forEach { it.npc.discard() }
            bodies.clear()
        }
    }

    /**
     * Stands a trainer at [anchor]'s place facing [player], dressed in [appearance] (Cobblemon's default player when
     * null). Null when it cannot be placed; the battle then keeps the invisible anchor.
     */
    fun spawn(player: ServerPlayer, anchor: Entity, appearance: TrainerResourceSkin?, nameKey: String): NPCEntity? {
        dismiss(player.uuid)
        val npcClass = NPCClasses.getByIdentifier(NPC_CLASS) ?: run {
            MoreCobblemonContents.LOGGER.warn("NPC class {} is missing; the managed trainer stays invisible", NPC_CLASS)
            return null
        }
        return try {
            val level = player.serverLevel()
            val npc = NPCEntity(level)
            npc.npc = npcClass
            val yaw = (Math.toDegrees(atan2(player.z - anchor.z, player.x - anchor.x)) - 90.0).toFloat()
            npc.moveTo(anchor.x, anchor.y, anchor.z, yaw, 0f)
            npc.yHeadRot = yaw
            npc.yBodyRot = yaw
            npc.initialize(1)
            npc.variationAspects.clear()
            appearance?.let { npc.variationAspects += it.aspect }
            npc.updateAspects()
            npc.customName = Component.translatable(nameKey)
            npc.addTag(TAG)
            val body = Body(npc)
            // Listed before it is added, so the load hook knows it is not a leftover.
            bodies[player.uuid] = body
            if (!level.addFreshEntity(npc)) {
                bodies.remove(player.uuid)
                return null
            }
            npc
        } catch (failure: RuntimeException) {
            bodies.remove(player.uuid)
            MoreCobblemonContents.LOGGER.error("The managed trainer for {} could not be placed", player.uuid, failure)
            null
        }
    }

    /** Marks the trainer as in [battleId], which Cobblemon's poser reads for the battle stance. */
    fun battleStarted(playerId: UUID, battleId: UUID) {
        val npc = bodies[playerId]?.npc ?: return
        npc.entityData.set(NPCEntity.BATTLE_IDS, npc.battleIds + battleId)
    }

    /** The battle is over: the trainer stands down and leaves after its closing words. */
    fun battleEnded(playerId: UUID, battleId: UUID, server: net.minecraft.server.MinecraftServer) {
        val body = bodies[playerId] ?: return
        body.npc.entityData.set(NPCEntity.BATTLE_IDS, body.npc.battleIds - battleId)
        body.leaveAtTick = server.tickCount + LINGER_TICKS
    }

    /** Sends [playerId]'s trainer away at once. */
    fun dismiss(playerId: UUID) {
        bodies.remove(playerId)?.npc?.discard()
    }
}
