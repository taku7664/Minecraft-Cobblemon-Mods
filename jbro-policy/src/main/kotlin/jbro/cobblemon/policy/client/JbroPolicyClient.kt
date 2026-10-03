package jbro.cobblemon.policy.client

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.summary.Summary
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.pokemon.OpenSummaryPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object JbroPolicyClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(OpenSummaryPayload.TYPE) { payload, context ->
            context.client().execute {
                if (context.client().screen != null) return@execute
                val slots = CobblemonClient.storage.party.slots
                val slot = slots.indexOfFirst { it?.uuid == payload.pokemonId }
                if (slot < 0) return@execute
                // Same call as Cobblemon's M key, so the summary can be edited the same way.
                try {
                    Summary.open(slots, true, slot)
                } catch (failure: Exception) {
                    JbroPolicy.LOGGER.debug("Failed to open the summary for a right-clicked Pokemon", failure)
                }
            }
        }
    }
}
