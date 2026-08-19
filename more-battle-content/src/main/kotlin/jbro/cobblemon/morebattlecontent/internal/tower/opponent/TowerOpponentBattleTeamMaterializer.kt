package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import java.util.Collections

internal sealed interface TowerOpponentBattleTeamMaterialization<out T> {
    class Created<T>(members: Collection<T>) : TowerOpponentBattleTeamMaterialization<T> {
        val members: List<T> = Collections.unmodifiableList(ArrayList(members))
    }

    data class CreationFailed(
        val setId: String,
        val cause: RuntimeException,
    ) : TowerOpponentBattleTeamMaterialization<Nothing>
}

internal class TowerOpponentBattleTeamMaterializer<T>(
    private val createMember: (TowerPokemonSet) -> T,
) {
    fun materialize(sets: List<TowerPokemonSet>): TowerOpponentBattleTeamMaterialization<T> {
        val members = ArrayList<T>(sets.size)
        for (set in sets) {
            val member = try {
                createMember(set)
            } catch (cause: RuntimeException) {
                return TowerOpponentBattleTeamMaterialization.CreationFailed(set.setId, cause)
            }
            members.add(member)
        }
        return TowerOpponentBattleTeamMaterialization.Created(
            Collections.unmodifiableList(members),
        )
    }
}
