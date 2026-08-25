package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import java.util.Collections
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerOpponentKind
import jbro.cobblemon.morebattlecontent.internal.tower.TowerLegendaryClassPolicy
import jbro.cobblemon.morebattlecontent.internal.tower.TowerStreakStage
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
        stage: TowerStreakStage,
        format: TowerBattleFormat,
        opponentKind: TowerOpponentKind,
        mechanic: MajorBattleMechanic,
        excludedProfileIds: Set<String> = emptySet(),
        excludedSpeciesIds: Set<String> = emptySet(),
        legendaryClassAllowed: Boolean = false,
    ): TowerOpponentSelectionResult {
        val eligible = catalog.profilesFor(stage, format, opponentKind, mechanic)
        if (eligible.isEmpty()) return TowerOpponentSelectionResult.NoEligibleProfile
        val fresh = eligible.filterNot { it.profileId in excludedProfileIds }
        val profiles = fresh.ifEmpty { eligible }

        val teamSize = format.selectionSize
        val isEligibleSet: (TowerPokemonSet) -> Boolean = { set ->
            legendaryClassAllowed || isNormal(set)
        }
        val profilesWithFreshTeams = profiles.filter { profile ->
            TowerLegalTeamSearch.exists(
                catalog.setsFor(profile).filter(isEligibleSet).filterNot { it.speciesId in excludedSpeciesIds },
                teamSize,
            )
        }
        val selectableProfiles = profilesWithFreshTeams.ifEmpty { profiles }

        val profile = selectWeighted(selectableProfiles)
        val completePool = catalog.setsFor(profile).filter(isEligibleSet)
        val freshPool = completePool.filterNot { it.speciesId in excludedSpeciesIds }
        val selectedPool = freshPool.takeIf {
            TowerLegalTeamSearch.exists(it, teamSize)
        } ?: completePool
        val randomizedPool = selectedPool.toMutableList()
        shuffle(randomizedPool)
        val team = selectStyledTeam(profile, randomizedPool, teamSize)
            ?: return TowerOpponentSelectionResult.NoLegalTeam(profile.profileId)
        return TowerOpponentSelectionResult.Selected(
            profile,
            Collections.unmodifiableList(ArrayList(team)),
        )
    }

    private fun isNormal(set: TowerPokemonSet): Boolean =
        !TowerLegendaryClassPolicy.isLegendaryClass(set.speciesId)

    private fun selectStyledTeam(
        profile: TowerOpponentProfile,
        pool: List<TowerPokemonSet>,
        teamSize: Int,
    ): List<TowerPokemonSet>? {
        val speciesAnchors = if (profile.signatureSpeciesIds.isEmpty()) {
            listOf<TowerPokemonSet?>(null)
        } else {
            pool.filter { it.speciesId in profile.signatureSpeciesIds }.toMutableList().also(::shuffle)
        }
        val styleAnchors = if (profile.teamStyle == TowerTrainerStyle.BALANCED) {
            listOf<TowerPokemonSet?>(null)
        } else {
            pool.filter(profile.teamStyle::matches).toMutableList().also(::shuffle)
        }
        speciesAnchors.forEach { speciesAnchor ->
            styleAnchors.forEach { styleAnchor ->
                val anchors = listOfNotNull(speciesAnchor, styleAnchor).distinctBy(TowerPokemonSet::setId)
                val anchorIds = anchors.map(TowerPokemonSet::setId).toSet()
                val ordered = anchors + pool.filterNot { it.setId in anchorIds }
                val team = TowerLegalTeamSearch.select(ordered, teamSize) ?: return@forEach
                val hasSignatureSpecies = profile.signatureSpeciesIds.isEmpty() ||
                    team.any { it.speciesId in profile.signatureSpeciesIds }
                val hasStyleSignature = profile.teamStyle == TowerTrainerStyle.BALANCED || team.any(profile.teamStyle::matches)
                if (hasSignatureSpecies && hasStyleSignature) return team
            }
        }
        return null
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
