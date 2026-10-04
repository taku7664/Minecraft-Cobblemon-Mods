package jbro.cobblemon.mcc.internal.compat.fabric

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ManagedBattleLifecycles
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.resources.ResourceLocation

/**
 * Central lifecycle boundary for temporary entities owned by MCC-managed battles.
 *
 * Contents settle their results in the default phase of these events. The entity backstop runs in a
 * later phase, so it still comes last when contents register their handlers from their own mods.
 */
internal object ManagedBattleLifecycleEvents {
    val BACKSTOP_PHASE: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "managed_battle_backstop")

    fun registerServer() {
        ServerTickEvents.END_SERVER_TICK.register {
            Cobblemon173ManagedBattleLifecycles.tick()
        }
        ServerPlayConnectionEvents.DISCONNECT.addPhaseOrdering(Event.DEFAULT_PHASE, BACKSTOP_PHASE)
        ServerPlayConnectionEvents.DISCONNECT.register(BACKSTOP_PHASE) { handler, server ->
            val playerId = handler.player.uuid
            dispatchToServerThread(server.isSameThread, { action -> server.execute(action) }) {
                Cobblemon173ManagedBattleLifecycles.disconnect(playerId)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(Event.DEFAULT_PHASE, BACKSTOP_PHASE)
        ServerLifecycleEvents.SERVER_STOPPING.register(BACKSTOP_PHASE) {
            Cobblemon173ManagedBattleLifecycles.shutdown()
        }
        ServerLifecycleEvents.SERVER_STOPPED.addPhaseOrdering(Event.DEFAULT_PHASE, BACKSTOP_PHASE)
        ServerLifecycleEvents.SERVER_STOPPED.register(BACKSTOP_PHASE) {
            Cobblemon173ManagedBattleLifecycles.clear()
        }
    }
}
