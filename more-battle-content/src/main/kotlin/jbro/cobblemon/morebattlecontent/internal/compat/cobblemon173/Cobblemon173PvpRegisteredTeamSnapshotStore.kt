package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpPokemonRegistration
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRegisteredBattleTeamResult
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRegisteredTeam
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRegisteredTeamSnapshotResult
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRegisteredTeamSnapshotStore
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpSelectedTeam
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleTeamMaterializer
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpSessionSnapshots
import net.minecraft.server.level.ServerPlayer

internal class Cobblemon173PvpRegisteredTeamSnapshotStore(
    playerResolver: (UUID) -> ServerPlayer?,
) : PvpSessionSnapshots<BattlePokemon>, PvpBattleTeamMaterializer<BattlePokemon> {
    private val delegate = PvpRegisteredTeamSnapshotStore(
        sourcesFor = { playerId ->
            playerResolver(playerId)?.let { player ->
                Cobblemon.storage.getParty(player).toList().map { pokemon -> Source(player, pokemon) }
            }
        },
        registrationOf = { source -> Cobblemon173PvpTeamFactory.registration(source.pokemon) },
        snapshotOf = { source, battleLevel -> snapshot(source, battleLevel) },
        battleCopyOf = ::battleCopy,
    )

    override fun snapshot(playerId: UUID, team: PvpRegisteredTeam): PvpRegisteredTeamSnapshotResult =
        delegate.snapshot(playerId, team)

    override fun materialize(playerId: UUID, selection: PvpSelectedTeam): PvpRegisteredBattleTeamResult<BattlePokemon> =
        delegate.materialize(playerId, selection)

    override fun discard(playerId: UUID) = delegate.discard(playerId)

    private data class Source(val player: ServerPlayer, val pokemon: Pokemon)

    private companion object {
        fun snapshot(source: Source, battleLevel: Int): Pokemon =
            source.pokemon.clone(false, source.player.registryAccess()).also { clone ->
                check(clone !== source.pokemon) { "Cobblemon returned the original Pokemon as a PvP snapshot" }
                check(clone.uuid == source.pokemon.uuid) { "PvP snapshot changed the Pokemon UUID" }
                clone.level = battleLevel
                clone.heal()
            }

        fun battleCopy(snapshot: Pokemon): BattlePokemon = BattlePokemon.Companion.safeCopyOf(snapshot).also { copy ->
            check(copy.effectedPokemon !== snapshot) { "Cobblemon returned the PvP snapshot as a battle copy" }
            check(copy.effectedPokemon.isBattleClone()) { "Cobblemon PvP battle clone marker is missing" }
            copy.effectedPokemon.heal()
        }
    }
}
