package jbro.cobblemon.mcc.internal.presentation

import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity

internal enum class BattleSceneCloseReason { NONE, PLAYER_GONE, FOCUS_GONE, CHANGED_LEVEL, TOO_FAR }

internal object BattleSceneCompletionPolicy {
    const val MAX_DISTANCE_SQUARED = 32.0 * 32.0

    fun reason(playerPresent: Boolean, focusPresent: Boolean, sameLevel: Boolean, distanceSquared: Double): BattleSceneCloseReason = when {
        !playerPresent -> BattleSceneCloseReason.PLAYER_GONE
        !focusPresent -> BattleSceneCloseReason.FOCUS_GONE
        !sameLevel -> BattleSceneCloseReason.CHANGED_LEVEL
        distanceSquared > MAX_DISTANCE_SQUARED -> BattleSceneCloseReason.TOO_FAR
        else -> BattleSceneCloseReason.NONE
    }
}

/** Server-owned completion callbacks for scenes whose speaker must stay until the player closes the last line. */
internal object BattleSceneCompletions {
    private data class Pending(val playerId: UUID, val focus: Entity, val finished: Runnable)
    private val pending = linkedMapOf<UUID, Pending>()

    fun await(player: ServerPlayer, focus: Entity, finished: Runnable): UUID = UUID.randomUUID().also { id ->
        pending[id] = Pending(player.uuid, focus, finished)
    }

    fun cancel(id: UUID) = complete(id, notifyClient = false)

    fun registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(BattleSceneFinishedPayload.TYPE) { payload, context ->
            context.server().execute {
                pending[payload.completionId]?.takeIf { it.playerId == context.player().uuid }?.let {
                    complete(payload.completionId, notifyClient = false)
                }
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            pending.filterValues { it.playerId == handler.player.uuid }.keys.toList().forEach {
                complete(it, notifyClient = false)
            }
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            pending.toMap().forEach { (id, scene) ->
                val player = server.playerList.getPlayer(scene.playerId)
                val focusPresent = !scene.focus.isRemoved && scene.focus.isAlive
                val sameLevel = player != null && scene.focus.level() === player.level()
                val distance = if (player != null && sameLevel) player.distanceToSqr(scene.focus) else 0.0
                val reason = BattleSceneCompletionPolicy.reason(player != null, focusPresent, sameLevel, distance)
                if (reason != BattleSceneCloseReason.NONE) complete(id, notifyClient = player != null)
            }
        }
        // Persisted NPC departure tags perform restart cleanup; callbacks must not mutate a stopping world.
        ServerLifecycleEvents.SERVER_STOPPING.register { pending.clear() }
    }

    private fun complete(id: UUID, notifyClient: Boolean) {
        val scene = pending.remove(id) ?: return
        if (notifyClient) {
            val player = scene.focus.level().server?.playerList?.getPlayer(scene.playerId)
            if (player != null && ServerPlayNetworking.canSend(player, BattleSceneCancelPayload.TYPE)) {
                ServerPlayNetworking.send(player, BattleSceneCancelPayload(id))
            }
        }
        try {
            scene.finished.run()
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.error("Battle scene completion {} failed", id, failure)
        }
    }
}
