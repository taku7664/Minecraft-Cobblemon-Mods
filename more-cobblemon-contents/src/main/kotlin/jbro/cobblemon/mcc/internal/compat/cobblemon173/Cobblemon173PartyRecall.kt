package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.server.level.ServerPlayer

/**
 * Takes back the party Pokemon a player has out in the world before an MCC battle. MCC battles fight with copies
 * of the party, so a Pokemon left out would stand beside its own copy for the whole battle.
 */
object Cobblemon173PartyRecall {
    fun recallSentOut(player: ServerPlayer) {
        try {
            Cobblemon.storage.getParty(player).forEach { pokemon ->
                if (pokemon.entity != null) pokemon.recall()
            }
        } catch (failure: RuntimeException) {
            // A Pokemon left out only looks doubled; it must not stop the battle from starting.
            MoreCobblemonContents.LOGGER.warn("Could not recall {}'s Pokemon before an MCC battle", player.uuid, failure)
        }
    }
}
