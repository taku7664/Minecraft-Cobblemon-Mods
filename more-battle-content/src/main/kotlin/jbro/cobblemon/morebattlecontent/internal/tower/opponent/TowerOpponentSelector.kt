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

        val normalTeamSize = format.selectionSize - if (legendaryClassAllowed) 1 else 0
        val profilesWithFreshTeams = profiles.filter { profile ->
            TowerLegalTeamSearch.exists(
                catalog.setsFor(profile).filter(::isNormal).filterNot { it.speciesId in excludedSpeciesIds },
                normalTeamSize,
            )
        }
        val selectableProfiles = profilesWithFreshTeams.ifEmpty { profiles }

        val profile = selectWeighted(selectableProfiles)
        val completePool = catalog.setsFor(profile).filter(::isNormal)
        val freshPool = completePool.filterNot { it.speciesId in excludedSpeciesIds }
        val selectedPool = freshPool.takeIf {
            TowerLegalTeamSearch.exists(it, normalTeamSize)
        } ?: completePool
        val special = if (legendaryClassAllowed) {
            selectLegendaryClassSet(stage, mechanic, selectedPool, normalTeamSize, excludedSpeciesIds)
                ?: selectLegendaryClassSet(stage, mechanic, selectedPool, normalTeamSize, emptySet())
                ?: return TowerOpponentSelectionResult.NoLegalTeam(profile.profileId)
        } else {
            null
        }
        val legalNormalPool = if (special == null) selectedPool else selectedPool.filter { candidate ->
            candidate.speciesId != special.speciesId &&
                (candidate.heldItemId == null || candidate.heldItemId != special.heldItemId)
        }
        val randomizedPool = legalNormalPool.toMutableList()
        shuffle(randomizedPool)
        val normalTeam = TowerLegalTeamSearch.select(randomizedPool, normalTeamSize)
            ?: return TowerOpponentSelectionResult.NoLegalTeam(profile.profileId)
        val team = buildList {
            addAll(normalTeam)
            special?.let(::add)
        }.toMutableList()
        shuffle(team)
        return TowerOpponentSelectionResult.Selected(
            profile,
            Collections.unmodifiableList(ArrayList(team)),
        )
    }

    private fun selectLegendaryClassSet(
        stage: TowerStreakStage,
        mechanic: MajorBattleMechanic,
        normalPool: List<TowerPokemonSet>,
        normalTeamSize: Int,
        excludedSpeciesIds: Set<String>,
    ): TowerPokemonSet? {
        val options = catalog.allSets()
            .asSequence()
            .filter { it.mechanic == mechanic }
            .filter { it.speciesId !in excludedSpeciesIds }
            .mapNotNull { set -> TowerLegendaryClassPolicy.entryFor(set.speciesId)?.let { Triple(set, it, set.speciesId) } }
            .groupBy { it.third }
            .mapNotNull { (_, variants) ->
                val preferredTier = if (stage == TowerStreakStage.INTRODUCTORY || stage == TowerStreakStage.PRACTICAL) {
                    variants.minOf { it.first.setTier }
                } else {
                    variants.maxOf { it.first.setTier }
                }
                val chosen = variants.first { it.first.setTier == preferredTier }
                val special = chosen.first
                val compatibleNormalPool = normalPool.filter { candidate ->
                    candidate.speciesId != special.speciesId &&
                        (candidate.heldItemId == null || candidate.heldItemId != special.heldItemId)
                }
                chosen.takeIf { TowerLegalTeamSearch.exists(compatibleNormalPool, normalTeamSize) }
            }
        if (options.isEmpty()) return null
        val totalWeight = options.sumOf { TowerLegendaryClassPolicy.selectionWeight(stage, it.second) }
        val ticket = random.nextLong(totalWeight)
        var upperBound = 0L
        options.forEach { option ->
            upperBound += TowerLegendaryClassPolicy.selectionWeight(stage, option.second)
            if (ticket < upperBound) return option.first
        }
        error("Legendary-class weighted selection exceeded its validated total")
    }

    private fun isNormal(set: TowerPokemonSet): Boolean =
        !TowerLegendaryClassPolicy.isLegendaryClass(set.speciesId)

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
