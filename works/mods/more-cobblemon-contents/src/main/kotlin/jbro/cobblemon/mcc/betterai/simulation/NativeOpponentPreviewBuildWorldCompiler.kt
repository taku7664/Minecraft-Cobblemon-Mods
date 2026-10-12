package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.BattleLocalOpponentStatSpreadView

internal enum class NativeOpponentBuildWorldIssueCode {
    SELECTED_PREVIEW_SLOT_MISSING,
    PUBLIC_BUILD_POOL_MISSING,
    /** The preview carries no base stats, so the assumed spread cannot be built. */
    BASE_STATS_MISSING,
    LEGAL_ABILITY_MISSING,
    TEAM_BUILD_WORLD_UNAVAILABLE,
}

internal data class NativeOpponentBuildWorldIssue(
    val code: NativeOpponentBuildWorldIssueCode,
    val previewSlotId: Int? = null,
)

internal data class NativeOpponentBuildWorldCompilation(
    val worlds: List<NativeOpponentPreviewBuildWorld>,
    val issues: List<NativeOpponentBuildWorldIssue>,
) {
    init {
        require(worlds.isEmpty() == issues.isNotEmpty()) {
            "Opponent build compilation must return normalized worlds or explicit issues"
        }
        require(worlds.isEmpty() || kotlin.math.abs(worlds.sumOf { it.probability } - 1.0) <= 1e-9)
    }
}

/**
 * Compiles the opponent's complete build worlds from public facts and fixed assumptions only (2026-10-12 user
 * decision): no usage statistics.
 *
 * - Ability: the revealed one, else every legal ability with equal weight; the only field the worlds branch on.
 * - Item: the revealed one, else the one the previewed forme requires, else none.
 * - Nature: [jbro.cobblemon.mcc.betterai.engine.sim.OFFENSIVE_ASSUMPTION_NATURE], Attack, Special Attack and Speed
 *   all raised a tenth and nothing lowered.
 * - EVs and IVs: [LocalOpponentStatAssumption] (the tier's reading).
 * - Tera type: the forme's forced one, else the species' own first type, which a Cobblemon Pokemon keeps unless its
 *   trainer changed it (2026-10-11). It is not guessed.
 * - Gender: the likeliest public one; it decides almost nothing.
 */
internal object NativeOpponentPreviewBuildWorldCompiler {
    fun compile(
        preview: BattleOpponentTeamPreviewView,
        selectedPreviewSlotIds: List<Int>,
        tier: BattleTrainerTier,
        exactStatSpreadsBySlot: Map<Int, BattleLocalOpponentStatSpreadView> = emptyMap(),
        /** Abilities the opening already revealed (Intimidate, Drought): those slots hold only that ability. */
        revealedAbilitiesBySlot: Map<Int, String> = emptyMap(),
        /** Items already revealed (Leftovers, an eaten berry): those slots hold only that item. */
        revealedItemsBySlot: Map<Int, String> = emptyMap(),
        /** Abilities a slot cannot have: its entry would have announced them (LocalEntryAbilityCheck). */
        ruledOutAbilitiesBySlot: Map<Int, Set<String>> = emptyMap(),
    ): NativeOpponentBuildWorldCompilation {
        val selectedSlots = selectedPreviewSlotIds.distinct().sorted()
        val previewBySlot = preview.pokemon.associateBy(BattleOpponentTeamPreviewPokemonView::previewSlotId)
        if (selectedSlots.size != selectedPreviewSlotIds.size || selectedSlots.size != preview.selectionSize) {
            return failure(NativeOpponentBuildWorldIssue(NativeOpponentBuildWorldIssueCode.SELECTED_PREVIEW_SLOT_MISSING))
        }
        val issues = mutableListOf<NativeOpponentBuildWorldIssue>()
        val candidatesBySlot = linkedMapOf<Int, List<WeightedBuild>>()
        selectedSlots.forEach { slot ->
            val pokemon = previewBySlot[slot]
            val pool = pokemon?.buildCandidatePool
            val code = when {
                pokemon == null -> NativeOpponentBuildWorldIssueCode.SELECTED_PREVIEW_SLOT_MISSING
                pool == null -> NativeOpponentBuildWorldIssueCode.PUBLIC_BUILD_POOL_MISSING
                baseStats(pokemon, pool).isEmpty() -> NativeOpponentBuildWorldIssueCode.BASE_STATS_MISSING
                else -> null
            }
            if (code != null) {
                issues += NativeOpponentBuildWorldIssue(code, slot)
                return@forEach
            }
            val legal = requireNotNull(pool).abilities.map { canonical(it.abilityId) }.filter(String::isNotBlank).distinct()
                .let { all -> all.filterNot { it in ruledOutAbilitiesBySlot[slot].orEmpty() }.ifEmpty { all } }
            val abilities = revealedAbilitiesBySlot[slot]?.let(::canonical)?.let(::listOf) ?: legal.sorted()
            if (abilities.isEmpty() || (revealedAbilitiesBySlot[slot] == null && legal.isEmpty())) {
                issues += NativeOpponentBuildWorldIssue(NativeOpponentBuildWorldIssueCode.LEGAL_ABILITY_MISSING, slot)
                return@forEach
            }
            candidatesBySlot[slot] = compilePokemon(requireNotNull(pokemon), abilities, revealedItemsBySlot[slot],
                tier, exactStatSpreadsBySlot[slot])
        }
        if (issues.isNotEmpty()) return NativeOpponentBuildWorldCompilation(emptyList(), issues)

        var teams = listOf(WeightedTeam(emptyList(), 1.0, POLICY_ID))
        selectedSlots.forEach { slot ->
            teams = teams.flatMap { team ->
                candidatesBySlot.getValue(slot).map { candidate ->
                    WeightedTeam(team.builds + candidate.build, team.weight * candidate.weight, "${team.id}|s$slot=${candidate.id}")
                }
            }.sortedWith(TEAM_ORDER).take(TEAM_WORLD_CAP)
        }
        val total = teams.sumOf(WeightedTeam::weight)
        if (teams.isEmpty() || !total.isFinite() || total <= 0.0) {
            return failure(NativeOpponentBuildWorldIssue(NativeOpponentBuildWorldIssueCode.TEAM_BUILD_WORLD_UNAVAILABLE))
        }
        return NativeOpponentBuildWorldCompilation(
            worlds = teams.map { team -> NativeOpponentPreviewBuildWorld(team.id, team.weight / total, team.builds) },
            issues = emptyList(),
        )
    }

    private fun compilePokemon(
        pokemon: BattleOpponentTeamPreviewPokemonView,
        abilities: List<String>,
        revealedItem: String?,
        tier: BattleTrainerTier,
        exactStatSpread: BattleLocalOpponentStatSpreadView?,
    ): List<WeightedBuild> {
        val pool = requireNotNull(pokemon.buildCandidatePool)
        val species = dexSpecies(pokemon)
        val item = revealedItem?.let(::canonical)?.takeIf(String::isNotBlank) ?: species?.requiredItem?.let(::canonical)
        val teraType = species?.forceTeraType?.let(::canonical)
            ?: pokemon.knownTypeIds.map(::canonical).firstOrNull(String::isNotBlank)
            ?: species?.types?.firstOrNull()?.let(::canonical)
            ?: NORMAL
        val gender = pool.genderRates.entries.filter { it.value > 0.0 }
            .minWithOrNull(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })?.key ?: "N"
        val evs = LocalOpponentStatAssumption.evs(tier, baseStats(pokemon, pool), exactStatSpread)
        val ivs = LocalOpponentStatAssumption.ivs(tier, exactStatSpread)
        val weight = 1.0 / abilities.size
        return abilities.map { ability ->
            WeightedBuild(
                build = NativeOpponentPreviewBuildHypothesis(
                    previewSlotId = pokemon.previewSlotId,
                    abilityId = ability,
                    itemId = item,
                    natureId = jbro.cobblemon.mcc.betterai.engine.sim.OFFENSIVE_ASSUMPTION_NATURE,
                    gender = gender,
                    teraTypeId = teraType,
                    evs = evs,
                    ivs = ivs,
                ),
                weight = weight,
                id = "a=$ability,i=${item ?: "none"},t=$teraType",
            )
        }
    }

    /** The preview's base stats, else the bundled dex's for the same species and forme. */
    private fun baseStats(
        pokemon: BattleOpponentTeamPreviewPokemonView,
        pool: jbro.cobblemon.mcc.internal.ai.BattleOpponentPreviewBuildPoolView,
    ): Map<String, Int> = pool.baseStats.takeIf { it.size == STAT_IDS.size }
        ?: dexSpecies(pokemon)?.baseStats?.takeIf { it.keys == STAT_IDS.toSet() }.orEmpty()

    /** The bundled dex's entry: the preview's Showdown id, without Cobblemon's "normal" form suffix, else the species. */
    private fun dexSpecies(pokemon: BattleOpponentTeamPreviewPokemonView) = jbro.cobblemon.mcc.betterai.engine.dex.EngineDex.bundled().let { dex ->
        listOfNotNull(pokemon.showdownSpeciesId, pokemon.showdownSpeciesId?.removeSuffix("normal"), pokemon.speciesId.substringAfter(':'))
            .firstNotNullOfOrNull { dex.species(it) }
    }

    private fun failure(issue: NativeOpponentBuildWorldIssue) = NativeOpponentBuildWorldCompilation(emptyList(), listOf(issue))

    private fun canonical(value: String): String = PublicIds.canonical(value)

    private data class WeightedBuild(
        val build: NativeOpponentPreviewBuildHypothesis,
        val weight: Double,
        val id: String,
    )
    private data class WeightedTeam(
        val builds: List<NativeOpponentPreviewBuildHypothesis>,
        val weight: Double,
        val id: String,
    )

    private val STAT_IDS = listOf("hp", "atk", "def", "spa", "spd", "spe")
    private const val POLICY_ID = "public-build-assumption-v2"
    private const val NORMAL = "normal"
    /** Equal-weight ability worlds multiply across the team; past this many team worlds only the first are kept. */
    private const val TEAM_WORLD_CAP = 64
    private val TEAM_ORDER = compareByDescending<WeightedTeam> { it.weight }.thenBy { it.id }
}
