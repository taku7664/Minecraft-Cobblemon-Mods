package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID

internal data class Cobblemon173ManagedPlayerBattleParticipant<R, A>(
    val roster: R,
    val actor: A,
)

/**
 * Single construction boundary for every MBC-controlled player side.
 * The temporary roster is complete before Cobblemon captures the actor's world position.
 */
internal object Cobblemon173ManagedPlayerBattleParticipants {
    fun prepare(
        playerId: UUID,
        team: List<BattlePokemon>,
    ): Cobblemon173ManagedPlayerBattleParticipant<PlayerPartyStore, PlayerBattleActor> = prepare(
        playerId = playerId,
        team = team,
        createRoster = ::PlayerPartyStore,
        setMember = { roster, slot, battlePokemon -> roster.set(slot, battlePokemon.effectedPokemon) },
        createActor = ::PlayerBattleActor,
    )

    internal fun <M, R, A> prepare(
        playerId: UUID,
        team: List<M>,
        createRoster: (UUID) -> R,
        setMember: (R, Int, M) -> Unit,
        createActor: (UUID, List<M>) -> A,
    ): Cobblemon173ManagedPlayerBattleParticipant<R, A> {
        val roster = Cobblemon173ManagedPlayerBattleRoster.attach(playerId, team, createRoster, setMember)
        return Cobblemon173ManagedPlayerBattleParticipant(roster, createActor(playerId, team))
    }

    fun attachBattleStores(
        battle: PokemonBattle,
        participants: Iterable<Cobblemon173ManagedPlayerBattleParticipant<PlayerPartyStore, PlayerBattleActor>>,
    ) {
        participants.forEach { participant -> battle.battlePartyStores += participant.roster }
    }
}
