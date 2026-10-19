package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.storage.party.PartyPosition
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID
import net.minecraft.server.level.ServerPlayer

/**
 * The temporary party a managed battle puts its player-side copies in. Cobblemon treats a Pokemon placed in a store
 * its player observes as gained by that player (Pokedex catch, TM unlocks from its moves, stats), so while a copy is
 * placed here no player observes the store: Factory rentals and other battle copies are never gained. The client
 * never knew this store, so it drops the party packet anyway.
 */
internal class Cobblemon173ManagedBattlePartyStore(playerId: UUID) : PlayerPartyStore(playerId) {
    private var placing = false

    override fun getObservingPlayers(): List<ServerPlayer> = if (placing) emptyList() else super.getObservingPlayers()

    override fun set(position: PartyPosition, pokemon: Pokemon) {
        placing = true
        try {
            super.set(position, pokemon)
        } finally {
            placing = false
        }
    }
}
