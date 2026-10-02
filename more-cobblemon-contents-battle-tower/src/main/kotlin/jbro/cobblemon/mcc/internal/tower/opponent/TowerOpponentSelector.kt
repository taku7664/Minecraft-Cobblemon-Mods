package jbro.cobblemon.mcc.internal.tower.opponent

import java.util.Collections
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.TowerOpponentKind
import jbro.cobblemon.mcc.internal.tower.TowerLegendaryClassPolicy
import jbro.cobblemon.mcc.internal.tower.TowerLegendaryCount
import jbro.cobblemon.mcc.internal.tower.TowerLegendaryGroup
import jbro.cobblemon.mcc.internal.tower.TowerStreakStage
import kotlin.math.ln
import kotlin.random.Random

internal interface TowerOpponentRandom {
    fun nextLong(bound: Long): Long
    fun nextInt(bound: Int): Int
}

private object DefaultTowerOpponentRandom : TowerOpponentRandom {
    override fun nextLong(bound: Long): Long = Random.Default.nextLong(bound)
    override fun nextInt(bound: Int): Int = Random.Default.nextInt(bound)
}

/**
 * How a trainer reads the challenger's team preview when it picks who to bring: [score] is a set's edge over the
 * previewed team (null when unknown) and [temperature] how closely its draw follows the scores, lower for stronger
 * AI tiers.
 */
internal class TowerPreviewScore(val temperature: Double, val score: (TowerPokemonSet) -> Double?) {
    init {
        require(temperature.isFinite() && temperature > 0.0) { "Preview temperature must be positive" }
    }
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
    /** The trainer's reading of the challenger's preview; without one it picks at random. */
    private val preview: TowerPreviewScore? = null,
) {
    fun select(
        stage: TowerStreakStage,
        format: TowerBattleFormat,
        opponentKind: TowerOpponentKind,
        mechanic: MajorBattleMechanic,
        excludedProfileIds: Set<String> = emptySet(),
        excludedSpeciesIds: Set<String> = emptySet(),
        legendaryClassAllowed: Boolean = false,
        /** For a boss: true picks among the rostered Champions, false among the other bosses, null among all. */
        championBoss: Boolean? = null,
        /**
         * The challenger's legendary-class Pokemon by group. A Champion brings as many legendaries as the challenger
         * in all; any other trainer as many of each group, drawn from its pool.
         */
        challengerLegendaries: TowerLegendaryCount = TowerLegendaryCount.NONE,
    ): TowerOpponentSelectionResult {
        val eligible = catalog.profilesFor(stage, format, opponentKind, mechanic)
            .filter { championBoss == null || it.fixedRoster == championBoss }
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
        if (profile.fixedRoster) {
            val legendaries = if (legendaryClassAllowed) challengerLegendaries.total else 0
            val team = rosterTeam(profile, catalog.setsFor(profile), teamSize, legendaries, mechanic)
                ?: return TowerOpponentSelectionResult.NoLegalTeam(profile.profileId)
            return TowerOpponentSelectionResult.Selected(profile, Collections.unmodifiableList(ArrayList(team)))
        }
        // The trainer answers the challenger's legendaries group for group; the rest of the team is regular.
        val legendaries = if (legendaryClassAllowed) drawLegendaries(catalog.setsFor(profile), challengerLegendaries) else emptyList()
        val completePool = catalog.setsFor(profile).filter(::isNormal)
        val freshPool = completePool.filterNot { it.speciesId in excludedSpeciesIds }
        // Species freshness is only a preference. Dropping recently faced species can strip a trainer of every
        // signature or style anchor, so fall back to the complete pool instead of reporting no legal team.
        val team = selectStyledTeamFrom(profile, freshPool, teamSize, legendaries)
            ?: selectStyledTeamFrom(profile, completePool, teamSize, legendaries)
            ?: selectStyledTeamFrom(profile, completePool, teamSize)
            ?: return TowerOpponentSelectionResult.NoLegalTeam(profile.profileId)
        return TowerOpponentSelectionResult.Selected(
            profile,
            Collections.unmodifiableList(ArrayList(team)),
        )
    }

    /**
     * A team from a fixed roster such as a Champion's, which may hold several sets per member:
     * - as many legendaries as the challenger brings (no more than the team has room for beside the ace), the main
     *   line first and the rest drawn from the sub lines, each in place of the member it replaces;
     * - the ace (the signature species) always;
     * - in Mega battles one member drawn from those with a Mega Stone set holds its stone, everyone else a battle item;
     * - the other members drawn at random, and every member one of its sets at random.
     * Recently faced species do not apply; a roster is who the trainer is.
     */
    private fun rosterTeam(
        profile: TowerOpponentProfile,
        roster: List<TowerPokemonSet>,
        teamSize: Int,
        challengerLegendaryCount: Int,
        mechanic: MajorBattleMechanic,
    ): List<TowerPokemonSet>? {
        val ace = profile.signatureSpeciesIds.firstOrNull()
        val lines = profile.legendLines
        val count = challengerLegendaryCount.coerceIn(0, minOf(lines.size, teamSize - (if (ace == null) 0 else 1)))
        repeat(ROSTER_ATTEMPTS) {
            val chosenLines = if (count == 0) emptyList() else {
                lines.filter(TowerLegendLine::main) + lines.filterNot(TowerLegendLine::main).toMutableList().also(::shuffleAny).take(count - 1)
            }
            val legendSpecies = lines.map(TowerLegendLine::speciesId).toSet()
            val replaced = chosenLines.map(TowerLegendLine::replaces).toSet()
            val members = roster.filter { it.speciesId !in legendSpecies && it.speciesId !in replaced }.groupBy(TowerPokemonSet::speciesId)
            val required = (listOfNotNull(ace) + chosenLines.map(TowerLegendLine::speciesId)).toMutableList()
            val megaHolder = if (mechanic == MajorBattleMechanic.MEGA) {
                // With every seat spoken for, the stone goes to a member already on the team.
                val candidates = members.values.flatten().filter { it.heldItemId.isMegaStone() }
                    .filter { required.size < teamSize || it.speciesId in required }
                candidates.takeIf { it.isNotEmpty() }?.toMutableList()?.also(::consider)?.first()
            } else null
            megaHolder?.speciesId?.takeIf { it !in required }?.let(required::add)
            val others = members.keys.filterNot(required::contains).toMutableList()
                .also { species -> considerSpecies(species, members) }
            val species = (required + others).take(teamSize)
            val team = species.map { id ->
                if (megaHolder != null && id == megaHolder.speciesId) return@map megaHolder
                val sets = (members[id] ?: roster.filter { it.speciesId == id })
                    .filter { mechanic != MajorBattleMechanic.MEGA || !it.heldItemId.isMegaStone() }
                    .ifEmpty { return@repeat }
                sets[random.nextInt(sets.size)]
            }
            val items = team.mapNotNull(TowerPokemonSet::heldItemId)
            if (team.size == teamSize && items.distinct().size == items.size) return team
        }
        return null
    }

    private fun String?.isMegaStone(): Boolean = this != null && MEGA_STONE.matches(this)

    private fun <T> shuffleAny(values: MutableList<T>) {
        for (index in values.lastIndex downTo 1) Collections.swap(values, index, random.nextInt(index + 1))
    }

    /** One random set of each group the challenger brought, of different species and held items. */
    private fun drawLegendaries(pool: List<TowerPokemonSet>, count: TowerLegendaryCount): List<TowerPokemonSet> {
        val drawn = ArrayList<TowerPokemonSet>()
        listOf(TowerLegendaryGroup.LEGENDARY to count.legendary, TowerLegendaryGroup.OTHER to count.other).forEach { (group, wanted) ->
            pool.filter { TowerLegendaryClassPolicy.group(it.speciesId) == group }.toMutableList().also(::shuffle)
                .filter { set -> drawn.none { it.speciesId == set.speciesId || (set.heldItemId != null && it.heldItemId == set.heldItemId) } }
                .distinctBy(TowerPokemonSet::speciesId).take(wanted).let(drawn::addAll)
        }
        return drawn
    }

    private fun selectStyledTeamFrom(
        profile: TowerOpponentProfile,
        pool: List<TowerPokemonSet>,
        teamSize: Int,
        forced: List<TowerPokemonSet> = emptyList(),
    ): List<TowerPokemonSet>? {
        if (pool.size + forced.size < teamSize) return null
        val consideredPool = pool.toMutableList()
        consider(consideredPool)
        return selectStyledTeam(profile, consideredPool, teamSize, forced)
    }

    private fun isNormal(set: TowerPokemonSet): Boolean =
        !TowerLegendaryClassPolicy.isLegendaryClass(set.speciesId)

    private fun selectStyledTeam(
        profile: TowerOpponentProfile,
        pool: List<TowerPokemonSet>,
        teamSize: Int,
        forced: List<TowerPokemonSet> = emptyList(),
    ): List<TowerPokemonSet>? {
        val speciesAnchors = if (profile.signatureSpeciesIds.isEmpty()) {
            listOf<TowerPokemonSet?>(null)
        } else {
            pool.filter { it.speciesId in profile.signatureSpeciesIds }.toMutableList().also(::consider)
        }
        val styleAnchors = if (profile.teamStyle == TowerTrainerStyle.BALANCED) {
            listOf<TowerPokemonSet?>(null)
        } else {
            pool.filter(profile.teamStyle::matches).toMutableList().also(::consider)
        }
        speciesAnchors.forEach { speciesAnchor ->
            styleAnchors.forEach { styleAnchor ->
                val anchors = forced + listOfNotNull(speciesAnchor, styleAnchor).distinctBy(TowerPokemonSet::setId)
                val anchorIds = anchors.map(TowerPokemonSet::setId).toSet()
                val ordered = anchors + pool.filterNot { it.setId in anchorIds }
                val team = TowerLegalTeamSearch.select(ordered, teamSize) ?: return@forEach
                if (!team.containsAll(forced)) return@forEach
                val hasSignatureSpecies = profile.signatureSpeciesIds.isEmpty() ||
                    team.any { it.speciesId in profile.signatureSpeciesIds }
                // Legendaries the trainer must answer with can leave no seat for a style anchor; the signature stays.
                val hasStyleSignature = profile.teamStyle == TowerTrainerStyle.BALANCED || forced.isNotEmpty() ||
                    team.any(profile.teamStyle::matches)
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

    /**
     * Puts [values] in the order the trainer considers them: drawn by preview score without replacement (a Gumbel
     * draw, so a stronger tier's lower temperature keeps it closer to the best), or shuffled without a preview.
     */
    private fun consider(values: MutableList<TowerPokemonSet>) {
        val reading = preview ?: return shuffle(values)
        val keyed = values.map { set -> set to drawKey(reading.score(set), reading) }
        values.clear()
        keyed.sortedByDescending { it.second }.mapTo(values) { it.first }
    }

    /** As [consider] for a roster's species, each by the best of its sets. */
    private fun considerSpecies(species: MutableList<String>, sets: Map<String, List<TowerPokemonSet>>) {
        val reading = preview ?: return shuffleAny(species)
        val keyed = species.map { id -> id to drawKey(sets[id].orEmpty().mapNotNull(reading.score).maxOrNull(), reading) }
        species.clear()
        keyed.sortedByDescending { it.second }.mapTo(species) { it.first }
    }

    private fun drawKey(score: Double?, reading: TowerPreviewScore): Double {
        val uniform = (random.nextLong(1L shl 53) + 0.5) / (1L shl 53).toDouble()
        return (score ?: 0.0) / reading.temperature - ln(-ln(uniform))
    }

    private fun shuffle(values: MutableList<TowerPokemonSet>) {
        for (index in values.lastIndex downTo 1) {
            val replacement = random.nextInt(index + 1)
            Collections.swap(values, index, replacement)
        }
    }
}

/** Mega Stones end in -ite (Garchompite, Charizardite X, Lucarionite Z); a Griseous Core is no Mega Stone. */
private val MEGA_STONE = Regex("^mega_showdown:[a-z_]+ite(_[xyz])?$")
private const val ROSTER_ATTEMPTS = 32
