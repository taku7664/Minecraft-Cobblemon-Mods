package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.BattleLocalOpponentStatSpreadView
import jbro.cobblemon.mcc.betterai.state.LocalOpponentBuildUsageEntry
import jbro.cobblemon.mcc.betterai.state.LocalOpponentBuildUsageLookup
import jbro.cobblemon.mcc.betterai.state.LocalOpponentSpreadUsage

internal enum class NativeOpponentBuildWorldIssueCode {
    SELECTED_PREVIEW_SLOT_MISSING,
    PUBLIC_BUILD_POOL_MISSING,
    BUILD_USAGE_MISSING,
    LEGAL_ABILITY_USAGE_MISSING,
    MATERIALIZABLE_BUILD_MISSING,
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
 * Compiles bounded complete build worlds from public legality and versioned usage marginals.
 *
 * The source statistics are independent marginals, so each product is an explicit prior
 * approximation rather than a claim that the source observed the fields together.
 */
internal object NativeOpponentPreviewBuildWorldCompiler {
    fun compile(
        preview: BattleOpponentTeamPreviewView,
        selectedPreviewSlotIds: List<Int>,
        tier: BattleTrainerTier,
        usage: LocalOpponentBuildUsageLookup,
        exactStatSpreadsBySlot: Map<Int, BattleLocalOpponentStatSpreadView> = emptyMap(),
        /** Abilities the opening already revealed (Intimidate, Drought): those slots hold only that ability. */
        revealedAbilitiesBySlot: Map<Int, String> = emptyMap(),
        /** Items already revealed (Leftovers, an eaten berry): those slots hold only that item. */
        revealedItemsBySlot: Map<Int, String> = emptyMap(),
    ): NativeOpponentBuildWorldCompilation {
        val selectedSlots = selectedPreviewSlotIds.distinct().sorted()
        val previewBySlot = preview.pokemon.associateBy(BattleOpponentTeamPreviewPokemonView::previewSlotId)
        val issues = mutableListOf<NativeOpponentBuildWorldIssue>()
        if (selectedSlots.size != selectedPreviewSlotIds.size || selectedSlots.size != preview.selectionSize) {
            return NativeOpponentBuildWorldCompilation(
                emptyList(),
                listOf(NativeOpponentBuildWorldIssue(NativeOpponentBuildWorldIssueCode.SELECTED_PREVIEW_SLOT_MISSING)),
            )
        }
        val cap = perPokemonCap(tier)
        val candidatesBySlot = linkedMapOf<Int, List<WeightedBuild>>()
        selectedSlots.forEach { slot ->
            val pokemon = previewBySlot[slot]
            if (pokemon == null) {
                issues += NativeOpponentBuildWorldIssue(
                    NativeOpponentBuildWorldIssueCode.SELECTED_PREVIEW_SLOT_MISSING,
                    slot,
                )
                return@forEach
            }
            val buildPool = pokemon.buildCandidatePool
            if (buildPool == null) {
                issues += NativeOpponentBuildWorldIssue(
                    NativeOpponentBuildWorldIssueCode.PUBLIC_BUILD_POOL_MISSING,
                    slot,
                )
                return@forEach
            }
            val observedUsage = usage.forPokemon(pokemon.speciesId, pokemon.formId)
            val usageSource = observedUsage ?: NativeMissingBuildUsageFallback.forPokemon(pokemon)
            if (usageSource == null) {
                issues += NativeOpponentBuildWorldIssue(NativeOpponentBuildWorldIssueCode.BUILD_USAGE_MISSING, slot)
                return@forEach
            }
            val source = usageSource.let { base ->
                revealedAbilitiesBySlot[slot]?.let { base.copy(abilityRates = mapOf(it to 1.0)) } ?: base
            }.let { base ->
                revealedItemsBySlot[slot]?.let { base.copy(itemRates = mapOf(it to 1.0), noItemRate = 0.0) }
                    ?: silentItemsOnly(base)
            }
            val legalAbilities = buildPool.abilities.mapTo(linkedSetOf()) {
                canonical(it.abilityId)
            }
            if (source.abilityRates.keys.none { canonical(it) in legalAbilities }) {
                issues += NativeOpponentBuildWorldIssue(
                    NativeOpponentBuildWorldIssueCode.LEGAL_ABILITY_USAGE_MISSING,
                    slot,
                )
                return@forEach
            }
            val candidates = compilePokemon(
                pokemon, source, cap, tier, exactStatSpreadsBySlot[slot],
                priorSource = if (observedUsage == null) "generic-public-prior" else "usage-snapshot",
            )
            if (candidates.isEmpty()) {
                issues += NativeOpponentBuildWorldIssue(
                    NativeOpponentBuildWorldIssueCode.MATERIALIZABLE_BUILD_MISSING,
                    slot,
                )
            } else {
                candidatesBySlot[slot] = candidates
            }
        }
        if (issues.isNotEmpty()) return NativeOpponentBuildWorldCompilation(emptyList(), issues)

        val teamCap = cap * selectedSlots.size
        var teams = listOf(WeightedTeam(emptyList(), 1.0, POLICY_ID))
        selectedSlots.forEach { slot ->
            teams = teams.asSequence().flatMap { team ->
                candidatesBySlot.getValue(slot).asSequence().map { candidate ->
                    // Trainer teams need not follow the item clause, and a revealed item may repeat one: a shared
                    // item makes a team unlikely, not impossible.
                    val item = candidate.build.itemId
                    val shared = item != null && team.builds.any { it.itemId == item }
                    WeightedTeam(
                        builds = team.builds + candidate.build,
                        weight = team.weight * candidate.weight * (if (shared) SHARED_ITEM_WEIGHT else 1.0),
                        id = "${team.id}|s$slot=${candidate.id}",
                    )
                }
            }.sortedWith(TEAM_ORDER).take(teamCap).toList()
        }
        if (teams.isEmpty()) {
            return NativeOpponentBuildWorldCompilation(
                emptyList(),
                listOf(NativeOpponentBuildWorldIssue(NativeOpponentBuildWorldIssueCode.TEAM_BUILD_WORLD_UNAVAILABLE)),
            )
        }
        val total = teams.sumOf(WeightedTeam::weight)
        if (!total.isFinite() || total <= 0.0) {
            return NativeOpponentBuildWorldCompilation(
                emptyList(),
                listOf(NativeOpponentBuildWorldIssue(NativeOpponentBuildWorldIssueCode.TEAM_BUILD_WORLD_UNAVAILABLE)),
            )
        }
        return NativeOpponentBuildWorldCompilation(
            worlds = teams.map { team ->
                NativeOpponentPreviewBuildWorld(team.id, team.weight / total, team.builds)
            },
            issues = emptyList(),
        )
    }

    private fun compilePokemon(
        pokemon: BattleOpponentTeamPreviewPokemonView,
        usage: LocalOpponentBuildUsageEntry,
        cap: Int,
        tier: BattleTrainerTier,
        exactStatSpread: BattleLocalOpponentStatSpreadView?,
        priorSource: String,
    ): List<WeightedBuild> {
        val pool = requireNotNull(pokemon.buildCandidatePool)
        val legalAbilities = pool.abilities.mapTo(linkedSetOf()) { canonical(it.abilityId) }
        val abilities = usage.abilityRates.entries.asSequence()
            .map { WeightedValue(canonical(it.key), it.value, canonical(it.key)) }
            .filter { it.value in legalAbilities && it.weight > 0.0 }
            .sortedWith(VALUE_ORDER)
            .toList()
        val items = buildList {
            usage.itemRates.forEach { (item, rate) ->
                if (rate > 0.0) add(WeightedValue(canonical(item), rate, canonical(item)))
            }
            if (usage.noItemRate > 0.0) add(WeightedValue<String?>(null, usage.noItemRate, "none"))
        }.sortedWith(VALUE_ORDER)
        val baseStats = pool.baseStats.takeIf { it.isNotEmpty() }
        val observedSpreads = usage.spreads.filter { it.rate > 0.0 }
        // A Boss knows the opponent's exact spread but not its nature; one offensive assumption stands for every
        // nature instead of branching the worlds on it (2026-10-10 user decision).
        val assumedSpread = if (tier == BattleTrainerTier.BOSS && exactStatSpread != null && baseStats != null) {
            listOf(LocalOpponentSpreadUsage(jbro.cobblemon.mcc.betterai.engine.sim.OFFENSIVE_ASSUMPTION_NATURE,
                LocalOpponentStatAssumption.evs(tier, baseStats, exactStatSpread), 1.0))
        } else null
        val spreads = assumedSpread ?: (if (baseStats == null) observedSpreads else observedSpreads
            .groupBy { canonical(it.natureId) }
            .map { (_, candidates) ->
                LocalOpponentSpreadUsage(
                    candidates.first().natureId,
                    LocalOpponentStatAssumption.evs(tier, baseStats, exactStatSpread),
                    candidates.sumOf { it.rate },
                )
            }).asSequence()
            .sortedWith(compareByDescending<LocalOpponentSpreadUsage> { it.rate }
                .thenBy(::spreadId))
            .toList()
        // Neither the gender nor a guess at the Tera type branches the worlds (2026-10-11 user decision). The gender
        // decides almost nothing, and a Tera type not yet shown is taken to be the species' own first type, which a
        // Cobblemon Pokemon keeps unless its trainer changed it. The two multiplied a Boss's 6 worlds into 96.
        val teraTypes = (pokemon.knownTypeIds.map(::canonical).firstOrNull(String::isNotBlank)
            ?: usage.teraTypeRates.entries.filter { it.value > 0.0 }.minWithOrNull(
                compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { canonical(it.key) })?.key?.let(::canonical))
            ?.let { listOf(WeightedValue(it, 1.0, it)) }.orEmpty()
        val genders = pool.genderRates.entries.filter { it.value > 0.0 }
            .minWithOrNull(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            ?.let { listOf(WeightedValue(it.key, 1.0, it.key)) }.orEmpty()
        if (abilities.isEmpty() || items.isEmpty() || spreads.isEmpty() ||
            teraTypes.isEmpty() || genders.isEmpty()
        ) return emptyList()

        var partials = abilities.asSequence().flatMap { ability ->
            items.asSequence().map { item ->
                PartialBuild(
                    ability = ability.value,
                    item = item.value,
                    spread = null,
                    teraType = null,
                    gender = null,
                    ivs = null,
                    weight = ability.weight * item.weight,
                    id = "a=${ability.id},i=${item.id}",
                )
            }
        }.let { bounded(it, cap) }
        partials = partials.asSequence().flatMap { partial ->
            spreads.asSequence().map { spread ->
                partial.copy(
                    spread = spread,
                    weight = partial.weight * spread.rate,
                    id = "${partial.id},s=${spreadId(spread)}",
                )
            }
        }.let { bounded(it, cap) }
        partials = partials.asSequence().flatMap { partial ->
            teraTypes.asSequence().map { teraType ->
                partial.copy(
                    teraType = teraType.value,
                    weight = partial.weight * teraType.weight,
                    id = "${partial.id},t=${teraType.id}",
                )
            }
        }.let { bounded(it, cap) }
        partials = partials.asSequence().flatMap { partial ->
            genders.asSequence().map { gender ->
                partial.copy(
                    gender = gender.value,
                    weight = partial.weight * gender.weight,
                    id = "${partial.id},g=${gender.id}",
                )
            }
        }.let { bounded(it, cap) }
        partials = partials.asSequence().flatMap { partial ->
            (if (baseStats == null) ivCandidates(requireNotNull(partial.spread)) else listOf(
                WeightedIvs(LocalOpponentStatAssumption.ivs(tier, exactStatSpread), 1.0,
                    if (exactStatSpread == null || tier == BattleTrainerTier.INTRODUCTORY ||
                        tier == BattleTrainerTier.STANDARD) "assumed31" else "known"),
            )).asSequence().map { ivs ->
                partial.copy(
                    ivs = ivs.values,
                    weight = partial.weight * ivs.weight,
                    id = "${partial.id},v=${ivs.id}",
                )
            }
        }.let { bounded(it, cap) }
        return partials.map { partial ->
            val spread = requireNotNull(partial.spread)
            WeightedBuild(
                build = NativeOpponentPreviewBuildHypothesis(
                    previewSlotId = pokemon.previewSlotId,
                    abilityId = partial.ability,
                    itemId = partial.item,
                    natureId = spread.natureId,
                    gender = requireNotNull(partial.gender),
                    teraTypeId = requireNotNull(partial.teraType),
                    evs = spread.evs,
                    ivs = requireNotNull(partial.ivs),
                ),
                weight = partial.weight,
                id = "$priorSource:${partial.id}",
            )
        }
    }

    /** Preserve high-weight Tera diversity and ability/item representatives before filling by weight. */
    private fun bounded(candidates: Sequence<PartialBuild>, cap: Int): List<PartialBuild> {
        val ranked = candidates.filter { it.weight.isFinite() && it.weight > 0.0 }
            .sortedWith(PARTIAL_ORDER)
            .toList()
        val kept = linkedMapOf<String, PartialBuild>()
        ranked.asSequence().filter { it.teraType != null }.distinctBy(PartialBuild::teraType).forEach { candidate ->
            if (kept.size < cap) kept[candidate.id] = candidate
        }
        ranked.forEach { candidate ->
            if (kept.size < cap && kept.values.none { it.ability == candidate.ability && it.item == candidate.item }) {
                kept[candidate.id] = candidate
            }
        }
        ranked.forEach { candidate ->
            if (kept.size < cap) kept.putIfAbsent(candidate.id, candidate)
        }
        return kept.values.sortedWith(PARTIAL_ORDER)
    }

    private fun ivCandidates(spread: LocalOpponentSpreadUsage): List<WeightedIvs> {
        val candidates = linkedMapOf<String, Map<String, Int>>()
        val perfect = STAT_IDS.associateWith { 31 }
        candidates["31"] = perfect
        if (spread.evs.getValue("atk") == 0) candidates["atk0"] = perfect + ("atk" to 0)
        if (spread.evs.getValue("spe") == 0) candidates["spe0"] = perfect + ("spe" to 0)
        if (spread.evs.getValue("atk") == 0 && spread.evs.getValue("spe") == 0) {
            candidates["atk0spe0"] = perfect + mapOf("atk" to 0, "spe" to 0)
        }
        val probability = 1.0 / candidates.size
        return candidates.map { (id, values) -> WeightedIvs(values, probability, id) }
    }

    /**
     * An unrevealed item is weighed only where it changes the next turn without announcing itself (2026-10-10 user
     * decision): the Choice items, Focus Sash and Assault Vest, plus the items that change a forme or enable a
     * mechanic (Mega Stones, Z-Crystals, forme items). Every other item shows itself once it acts (Life Orb,
     * Leftovers) and the worlds then take the revealed one, so until then it counts as no item. Folding them
     * together keeps the worlds on the hypotheses that change a decision.
     */
    private fun silentItemsOnly(usage: LocalOpponentBuildUsageEntry): LocalOpponentBuildUsageEntry {
        val kept = usage.itemRates.filterKeys { weighedUnrevealed(canonical(it)) }
        val folded = usage.itemRates.values.sum() - kept.values.sum()
        return usage.copy(itemRates = kept, noItemRate = usage.noItemRate + folded)
    }

    private fun weighedUnrevealed(item: String): Boolean {
        if (item in SILENT_ITEMS) return true
        val data = jbro.cobblemon.mcc.betterai.engine.dex.EngineDex.bundled().itemOrNull(item) ?: return false
        return listOf("megaStone", "zMove", "forcedForme", "itemUser").any { data.data(it) != null }
    }

    private val SILENT_ITEMS = setOf("choiceband", "choicespecs", "choicescarf", "focussash", "assaultvest")

    private fun perPokemonCap(tier: BattleTrainerTier): Int = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> 3
        BattleTrainerTier.STANDARD -> 6
        BattleTrainerTier.ADVANCED -> 10
        BattleTrainerTier.BOSS -> 16
    }

    private fun spreadId(spread: LocalOpponentSpreadUsage): String =
        "${spread.natureId}:${STAT_IDS.joinToString("/") { spread.evs.getValue(it).toString() }}"

    private fun canonical(value: String): String = PublicIds.canonical(value)

    private data class WeightedValue<T>(val value: T, val weight: Double, val id: String)
    private data class WeightedIvs(val values: Map<String, Int>, val weight: Double, val id: String)
    private data class PartialBuild(
        val ability: String,
        val item: String?,
        val spread: LocalOpponentSpreadUsage?,
        val teraType: String?,
        val gender: String?,
        val ivs: Map<String, Int>?,
        val weight: Double,
        val id: String,
    )
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
    private const val POLICY_ID = "public-build-prior-v1"
    private const val SHARED_ITEM_WEIGHT = 0.05
    private val PARTIAL_ORDER = compareByDescending<PartialBuild> { it.weight }.thenBy { it.id }
    private val TEAM_ORDER = compareByDescending<WeightedTeam> { it.weight }.thenBy { it.id }
    private val VALUE_ORDER = compareByDescending<WeightedValue<*>> { it.weight }.thenBy { it.id }
}
