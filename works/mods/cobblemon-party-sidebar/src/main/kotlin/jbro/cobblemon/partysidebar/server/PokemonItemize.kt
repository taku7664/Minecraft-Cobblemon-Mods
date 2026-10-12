package jbro.cobblemon.partysidebar.server

import jbro.cobblemon.toitem.api.PokemonTransfers
import jbro.cobblemon.partysidebar.network.ItemizePayload
import jbro.cobblemon.partysidebar.network.RestorePayload
import net.minecraft.server.level.ServerPlayer

/** The sidebar selects the origin/destination; Cobblemon To Item owns all transfer rules. */
object PokemonItemize {
    fun itemize(player: ServerPlayer, payload: ItemizePayload) {
        if (payload.toCursor) PokemonTransfers.pickup(player, payload.slot, payload.pokemonId)
        else PokemonTransfers.itemize(player, payload.slot, payload.pokemonId, dropOverflow = false)
    }
    fun restore(player: ServerPlayer, payload: RestorePayload) {
        PokemonTransfers.restoreCursor(player, payload.slot, payload.pokemonId, payload.targetId)
    }
}
