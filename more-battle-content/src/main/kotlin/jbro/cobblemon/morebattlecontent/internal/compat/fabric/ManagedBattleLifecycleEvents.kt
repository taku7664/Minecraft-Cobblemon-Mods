package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173ManagedBattleLifecycles
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents

/** Central lifecycle boundary for temporary entities owned by MBC-managed battles. */
internal object ManagedBattleLifecycleEvents {
    fun registerServer() {
        ServerTickEvents.END_SERVER_TICK.register {
            Cobblemon173ManagedBattleLifecycles.tick()
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            val playerId = handler.player.uuid
            dispatchToServerThread(server.isSameThread, { action -> server.execute(action) }) {
                Cobblemon173ManagedBattleLifecycles.disconnect(playerId)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            Cobblemon173ManagedBattleLifecycles.shutdown()
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            Cobblemon173ManagedBattleLifecycles.clear()
        }
    }
}
