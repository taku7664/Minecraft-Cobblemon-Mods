package jbro.cobblemon.mcc.internal.compat.fabric

import java.util.concurrent.CopyOnWriteArrayList
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.ai.BattleTacticalRunMemoryStore
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ManagedTrainerPokemonOwners
import jbro.cobblemon.mcc.internal.compat.cobblemon173.runManagedCleanupActionsSafely
import jbro.cobblemon.mcc.internal.hub.BattleHubNetworking
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

/** Final backstop for process-wide references that must never cross integrated-server lifetimes. */
object ManagedServerEphemeralStateCleanup {
    private val contentActions = CopyOnWriteArrayList<() -> Unit>()

    /** Contents add their own process-wide state (catalog stores and the like) to the same backstop. */
    fun register(action: () -> Unit): AutoCloseable {
        contentActions += action
        return AutoCloseable { contentActions.remove(action) }
    }

    fun registerServer() {
        ServerLifecycleEvents.SERVER_STOPPED.register {
            val coreActions = listOf<() -> Unit>(
                BattleHubNetworking::clear,
                Cobblemon173BattleRuleHooks::clear,
                Cobblemon173ManagedTrainerPokemonOwners::clear,
                BattleTacticalRunMemoryStore::clear,
                BattlePointShopCatalogResources.store::clear,
            )
            runManagedCleanupActionsSafely(
                { failure ->
                    MoreCobblemonContents.LOGGER.error("Final managed server-state cleanup failed", failure)
                },
                *(coreActions + contentActions).toTypedArray(),
            )
        }
    }
}
