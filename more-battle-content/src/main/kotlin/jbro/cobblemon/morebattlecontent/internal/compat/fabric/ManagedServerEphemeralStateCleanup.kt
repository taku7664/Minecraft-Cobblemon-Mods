package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.ai.BattleTacticalRunMemoryStore
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173ManagedTrainerPokemonOwners
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.runManagedCleanupActionsSafely
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubNetworking
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

/** Final backstop for process-wide references that must never cross integrated-server lifetimes. */
internal object ManagedServerEphemeralStateCleanup {
    fun registerServer() {
        ServerLifecycleEvents.SERVER_STOPPED.register {
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreBattleContent.LOGGER.error("Final managed server-state cleanup failed", failure)
                },
                BattleHubNetworking::clear,
                Cobblemon173BattleRuleHooks::clear,
                Cobblemon173ManagedTrainerPokemonOwners::clear,
                BattleTacticalRunMemoryStore::clear,
            )
        }
    }
}
