package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.Collections
import java.util.UUID

internal sealed interface TowerBattleTeamMaterialization<out T> {
    class Created<T>(members: Collection<T>) : TowerBattleTeamMaterialization<T> {
        val members: List<T> = Collections.unmodifiableList(ArrayList(members))
    }

    data class MissingSource(val pokemonId: UUID) : TowerBattleTeamMaterialization<Nothing>
    data class DuplicateSource(val pokemonId: UUID) : TowerBattleTeamMaterialization<Nothing>
    data class SourceChanged(val pokemonId: UUID) : TowerBattleTeamMaterialization<Nothing>
    data class CloneFailed(val pokemonId: UUID, val cause: RuntimeException) : TowerBattleTeamMaterialization<Nothing>
}

internal class TowerBattleTeamMaterializer<S, T>(
    private val registrationOf: (S) -> TowerPokemonRegistration,
    private val cloneForBattle: (S, battleLevel: Int) -> T,
) {
    fun materialize(
        selection: TowerSelectedTeam,
        currentSources: Collection<S>,
    ): TowerBattleTeamMaterialization<T> = materialize(selection.members, currentSources)

    fun materialize(
        registrations: List<TowerPokemonRegistration>,
        currentSources: Collection<S>,
    ): TowerBattleTeamMaterialization<T> {
        val sourcesById = LinkedHashMap<UUID, S>()
        currentSources.forEach { source ->
            val id = registrationOf(source).pokemonId
            if (sourcesById.putIfAbsent(id, source) != null) {
                return TowerBattleTeamMaterialization.DuplicateSource(id)
            }
        }

        val orderedSources = ArrayList<S>(registrations.size)
        registrations.forEach { selected ->
            val source = sourcesById[selected.pokemonId]
                ?: return TowerBattleTeamMaterialization.MissingSource(selected.pokemonId)
            if (registrationOf(source) != selected) {
                return TowerBattleTeamMaterialization.SourceChanged(selected.pokemonId)
            }
            orderedSources += source
        }

        val clones = ArrayList<T>(orderedSources.size)
        orderedSources.forEachIndexed { index, source ->
            val selected = registrations[index]
            try {
                clones += cloneForBattle(source, selected.battleLevel)
            } catch (exception: RuntimeException) {
                return TowerBattleTeamMaterialization.CloneFailed(selected.pokemonId, exception)
            }
        }
        return TowerBattleTeamMaterialization.Created(clones)
    }
}
