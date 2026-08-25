package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.tower.TowerPokemonRegistration
import jbro.cobblemon.morebattlecontent.internal.tower.TowerLegendaryClassPolicy
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredBattleTeamResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredTeam
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredTeamSnapshotResult
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredTeamSnapshots
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRegisteredTeamSnapshotStore
import jbro.cobblemon.morebattlecontent.internal.tower.TowerSelectedTeam
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer

internal class Cobblemon173TowerRegisteredTeamSnapshotStore(
    playerResolver: (UUID) -> ServerPlayer?,
) : TowerRegisteredTeamSnapshots {
    private val delegate = TowerRegisteredTeamSnapshotStore(
        sourcesFor = { playerId ->
            playerResolver(playerId)?.let { player ->
                Cobblemon.storage.getParty(player).toList().map { pokemon -> Source(player, pokemon) }
            }
        },
        registrationOf = { source -> registration(source.pokemon) },
        snapshotOf = { source, battleLevel -> snapshot(source, battleLevel) },
        battleCopyOf = ::battleCopy,
    )

    override fun snapshot(playerId: UUID, team: TowerRegisteredTeam): TowerRegisteredTeamSnapshotResult =
        delegate.snapshot(playerId, team)

    fun materialize(
        playerId: UUID,
        selection: TowerSelectedTeam,
    ): TowerRegisteredBattleTeamResult<BattlePokemon> = delegate.materialize(playerId, selection)

    override fun discard(playerId: UUID) = delegate.discard(playerId)

    private data class Source(val player: ServerPlayer, val pokemon: Pokemon)

    private companion object {
        fun registration(pokemon: Pokemon): TowerPokemonRegistration {
            return pokemon.toTowerPokemonRegistration()
        }

        fun snapshot(source: Source, battleLevel: Int): Pokemon =
            source.pokemon.clone(false, source.player.registryAccess()).also { clone ->
                check(clone !== source.pokemon) { "Cobblemon returned the original Pokemon as a registered snapshot" }
                check(clone.uuid == source.pokemon.uuid) { "Registered snapshot changed the Pokemon UUID" }
                clone.level = battleLevel
                clone.heal()
            }

        fun battleCopy(snapshot: Pokemon): BattlePokemon = BattlePokemon.Companion.safeCopyOf(snapshot).also { copy ->
            check(copy.effectedPokemon !== snapshot) { "Cobblemon returned the registered snapshot as a battle copy" }
            check(copy.effectedPokemon.isBattleClone()) { "Cobblemon battle clone marker is missing" }
            copy.effectedPokemon.heal()
        }
    }
}

internal fun Pokemon.toTowerPokemonRegistration(): TowerPokemonRegistration {
    val heldItem = heldItem()
    val speciesId = species.resourceIdentifier.toString()
    return TowerPokemonRegistration(
        pokemonId = uuid,
        speciesId = speciesId,
        heldItemId = if (heldItem.isEmpty) null else BuiltInRegistries.ITEM.getKey(heldItem.item).toString(),
        level = level,
        legendaryClass = TowerLegendaryClassPolicy.isLegendaryClass(speciesId, species.labels),
    )
}
