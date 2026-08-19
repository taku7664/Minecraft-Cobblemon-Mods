package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID

/**
 * Gives every generated MBC player battle copy the same temporary party-store ownership contract.
 * Tower snapshots, Factory rentals, and PvP snapshots must all pass through this boundary.
 */
internal object Cobblemon173ManagedPlayerBattleRoster {
    fun attach(playerId: UUID, team: List<BattlePokemon>): PlayerPartyStore = attach(
        playerId = playerId,
        team = team,
        createRoster = ::PlayerPartyStore,
        setMember = { roster, slot, battlePokemon ->
            roster.set(slot, battlePokemon.effectedPokemon)
        },
    )

    internal fun <M, R> attach(
        playerId: UUID,
        team: List<M>,
        createRoster: (UUID) -> R,
        setMember: (R, Int, M) -> Unit,
    ): R {
        require(team.size <= 6) { "A temporary player roster cannot contain more than six Pokemon" }
        val roster = createRoster(playerId)
        team.forEachIndexed { slot, member -> setMember(roster, slot, member) }
        return roster
    }
}
