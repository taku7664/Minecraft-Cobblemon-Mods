package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import java.util.Collections
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerOpponentKind
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRank
import kotlin.random.Random

internal interface TowerOpponentRandom {
    fun nextLong(bound: Long): Long
    fun nextInt(bound: Int): Int
}

private object DefaultTowerOpponentRandom : TowerOpponentRandom {
    override fun nextLong(bound: Long): Long = Random.Default.nextLong(bound)
    override fun nextInt(bound: Int): Int = Random.Default.nextInt(bound)
}

internal sealed interface TowerOpponentSelectionResult {
    data class Selected(
        val profile: TowerOpponentProfile,
        val team: List<TowerPokemonSet>,
    ) : TowerOpponentSelectionResult

    data object NoEligibleProfile : TowerOpponentSelectionResult
    data class NoLegalTeam(val profileId: String) : TowerOpponentSelectionResult
}

internal class TowerOpponentSelector(
    private val catalog: TowerOpponentCatalog,
    private val random: TowerOpponentRandom = DefaultTowerOpponentRandom,
) {
    fun select(
        rank: TowerRank,
        format: TowerBattleFormat,
        opponentKind: TowerOpponentKind,
        mechanic: MajorBattleMechanic,
        excludedProfileIds: Set<String> = emptySet(),
    ): TowerOpponentSelectionResult {
        val eligible = catalog.profilesFor(rank, format, opponentKind, mechanic)
        if (eligible.isEmpty()) return TowerOpponentSelectionResult.NoEligibleProfile
        val fresh = eligible.filterNot { it.profileId in excludedProfileIds }
        val profiles = fresh.ifEmpty { eligible }

        val profile = selectWeighted(profiles)
        val randomizedPool = catalog.setsFor(profile).toMutableList()
        shuffle(randomizedPool)
        val team = TowerLegalTeamSearch.select(randomizedPool, format.selectionSize)
            ?: return TowerOpponentSelectionResult.NoLegalTeam(profile.profileId)
        return TowerOpponentSelectionResult.Selected(
            profile,
            Collections.unmodifiableList(ArrayList(team)),
        )
    }

    private fun selectWeighted(profiles: List<TowerOpponentProfile>): TowerOpponentProfile {
        val totalWeight = profiles.sumOf { it.weight.toLong() }
        val ticket = random.nextLong(totalWeight)
        var upperBound = 0L
        for (profile in profiles) {
            upperBound += profile.weight
            if (ticket < upperBound) return profile
        }
        error("Weighted profile selection exceeded its validated total")
    }

    private fun shuffle(values: MutableList<TowerPokemonSet>) {
        for (index in values.lastIndex downTo 1) {
            val replacement = random.nextInt(index + 1)
            Collections.swap(values, index, replacement)
        }
    }
}
