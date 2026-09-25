package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveGroup
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import jbro.cobblemon.morebattlecontent.betterai.state.LocalMoveUsageLookup

internal enum class NativeOpponentPreviewMoveCatalogIssueCode {
    SELECTED_PREVIEW_SLOT_MISSING,
    PUBLIC_MOVE_POOL_MISSING,
    NORMALIZED_MOVESET_INCOMPLETE,
    EXECUTABLE_MOVE_UNAVAILABLE,
    TEAM_MOVE_WORLD_UNAVAILABLE,
}

internal data class NativeOpponentPreviewMoveCatalogIssue(
    val code: NativeOpponentPreviewMoveCatalogIssueCode,
    val battlePokemonId: UUID,
    val previewSlotId: Int,
)

internal data class NativeOpponentPreviewMoveCatalogWorld(
    val hypothesisId: String,
    val probability: Double,
    val catalog: BattlePublicActionCatalogView,
) {
    init {
        require(hypothesisId.isNotBlank())
        require(probability.isFinite() && probability > 0.0 && probability <= 1.0)
    }
}

internal data class NativeOpponentPreviewMoveCatalogMaterialization(
    val worlds: List<NativeOpponentPreviewMoveCatalogWorld>,
    val issues: List<NativeOpponentPreviewMoveCatalogIssue>,
) {
    init {
        require(worlds.isEmpty() == issues.isNotEmpty()) {
            "Preview move materialization must return normalized worlds or explicit issues"
        }
        require(worlds.isEmpty() || kotlin.math.abs(worlds.sumOf { it.probability } - 1.0) <= 1e-9)
        require(worlds.map { it.hypothesisId }.distinct().size == worlds.size)
    }
}

/** Maps public preview learnsets onto selected synthetic IDs without consulting a live hidden set. */
internal object NativeOpponentPreviewMoveCatalogMaterializer {
    fun materialize(
        roster: NativeMaterializedOpponentRoster,
        preview: BattleOpponentTeamPreviewView,
        sourceCatalog: BattlePublicActionCatalogView,
        tier: BattleTrainerTier,
        usage: LocalMoveUsageLookup?,
    ): NativeOpponentPreviewMoveCatalogMaterialization {
        val previewBySlot = preview.pokemon.associateBy(BattleOpponentTeamPreviewPokemonView::previewSlotId)
        val issues = mutableListOf<NativeOpponentPreviewMoveCatalogIssue>()
        val candidatesByPokemon = linkedMapOf<UUID, List<WeightedInference>>()
        roster.state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT }
            .sortedBy { roster.opponentPreviewSlotByPokemonId.getValue(it.battlePokemonId) }
            .forEach { pokemon ->
                val slot = roster.opponentPreviewSlotByPokemonId.getValue(pokemon.battlePokemonId)
                val previewPokemon = previewBySlot[slot]
                if (previewPokemon == null) {
                    issues += issue(
                        NativeOpponentPreviewMoveCatalogIssueCode.SELECTED_PREVIEW_SLOT_MISSING,
                        pokemon,
                        slot,
                    )
                    return@forEach
                }
                sourceCatalog.inferredMovesForPokemon(pokemon.battlePokemonId)?.let { existing ->
                    val compiled = NativeMoveHypothesisCompiler.compile(pokemon, sourceCatalog)
                    when {
                        !compiled.isCompleteSet -> issues += issue(
                            NativeOpponentPreviewMoveCatalogIssueCode.NORMALIZED_MOVESET_INCOMPLETE,
                            pokemon,
                            slot,
                        )
                        !compiled.hasExecutableMove -> issues += issue(
                            NativeOpponentPreviewMoveCatalogIssueCode.EXECUTABLE_MOVE_UNAVAILABLE,
                            pokemon,
                            slot,
                        )
                        else -> candidatesByPokemon[pokemon.battlePokemonId] = listOf(
                            WeightedInference(existing, 1.0, "fixed:${inferenceId(existing)}"),
                        )
                    }
                    return@forEach
                }
                if (previewPokemon.moveCandidatePool == null) {
                    issues += issue(
                        NativeOpponentPreviewMoveCatalogIssueCode.PUBLIC_MOVE_POOL_MISSING,
                        pokemon,
                        slot,
                    )
                    return@forEach
                }
                val candidates = inferenceCandidates(
                    pokemon,
                    previewPokemon,
                    roster.state.format,
                    tier,
                    usage,
                )
                if (candidates.isEmpty()) {
                    issues += issue(
                        NativeOpponentPreviewMoveCatalogIssueCode.EXECUTABLE_MOVE_UNAVAILABLE,
                        pokemon,
                        slot,
                    )
                } else {
                    candidatesByPokemon[pokemon.battlePokemonId] = candidates
                }
            }
        if (issues.isNotEmpty()) {
            return NativeOpponentPreviewMoveCatalogMaterialization(emptyList(), issues)
        }

        var teams = listOf(WeightedInferenceTeam(emptyList(), 1.0, MOVE_POLICY_ID))
        val teamCap = teamWorldCap(tier, candidatesByPokemon.size)
        candidatesByPokemon.forEach { (pokemonId, candidates) ->
            teams = teams.asSequence().flatMap { team ->
                candidates.asSequence().map { candidate ->
                    WeightedInferenceTeam(
                        inferences = team.inferences + candidate.inference,
                        weight = team.weight * candidate.weight,
                        id = "${team.id}|$pokemonId=${candidate.id}",
                    )
                }
            }.filter { it.weight.isFinite() && it.weight > 0.0 }
                .sortedWith(TEAM_ORDER)
                .take(teamCap)
                .toList()
        }
        val total = teams.sumOf(WeightedInferenceTeam::weight)
        if (teams.isEmpty() || !total.isFinite() || total <= 0.0) {
            val first = roster.state.pokemon.first { it.side == BattleSide.OPPONENT }
            return NativeOpponentPreviewMoveCatalogMaterialization(
                emptyList(),
                listOf(issue(
                    NativeOpponentPreviewMoveCatalogIssueCode.TEAM_MOVE_WORLD_UNAVAILABLE,
                    first,
                    roster.opponentPreviewSlotByPokemonId.getValue(first.battlePokemonId),
                )),
            )
        }
        return NativeOpponentPreviewMoveCatalogMaterialization(
            worlds = teams.map { team ->
                NativeOpponentPreviewMoveCatalogWorld(
                    hypothesisId = team.id,
                    probability = team.weight / total,
                    catalog = sourceCatalog.withOpponentMoveInferences(team.inferences),
                )
            },
            issues = emptyList(),
        )
    }

    internal fun infer(
        pokemon: BattlePokemonStateView,
        preview: BattleOpponentTeamPreviewPokemonView,
        format: BattleFormat,
        tier: BattleTrainerTier,
        usage: LocalMoveUsageLookup?,
    ): BattleOpponentMoveInferenceView {
        require(pokemon.side == BattleSide.OPPONENT)
        requireNotNull(preview.moveCandidatePool) {
            "A public preview move pool is required before inference"
        }
        val policy = policy(tier)
        val ranked = rankedMoves(preview, format, usage)
        val stab = ranked.filter { (_, details) -> group(preview, details) == BattleOpponentMoveGroup.STAB_ATTACK }
            .distinctBy { (_, details) -> canonical(details.typeId) }
        val coverage = ranked.filter { (_, details) ->
            group(preview, details) == BattleOpponentMoveGroup.COVERAGE_ATTACK
        }
        val slots = mutableListOf<BattleOpponentMoveSlotView>()
        addExpected(slots, stab.take(policy.stabSlots), BattleOpponentMoveGroup.STAB_ATTACK)
        addExpected(slots, coverage.take(policy.coverageSlots), BattleOpponentMoveGroup.COVERAGE_ATTACK)
        if (slots.isEmpty()) {
            ranked.firstOrNull { (_, details) -> details.damageCategory != BattleMoveDamageCategory.STATUS }
                ?.let { candidate -> addExpected(slots, listOf(candidate), group(preview, candidate.value)) }
        }
        repeat(minOf(policy.statusGuessSlots, MAX_MOVE_SLOTS - slots.size)) {
            slots += guessed(slots.size, BattleOpponentMoveGroup.STATUS_OTHER)
        }
        while (slots.size < MAX_MOVE_SLOTS) slots += guessed(slots.size, BattleOpponentMoveGroup.OTHER)
        return BattleOpponentMoveInferenceView(pokemon.battlePokemonId, slots)
    }

    private fun inferenceCandidates(
        pokemon: BattlePokemonStateView,
        preview: BattleOpponentTeamPreviewPokemonView,
        format: BattleFormat,
        tier: BattleTrainerTier,
        usage: LocalMoveUsageLookup?,
    ): List<WeightedInference> {
        val policy = policy(tier)
        if (!policy.variableStabShapes) {
            val inference = infer(pokemon, preview, format, tier, usage)
            return if (inference.slots.any { it.knowledge != BattleOpponentMoveKnowledge.GUESS }) {
                listOf(WeightedInference(inference, 1.0, inferenceId(inference)))
            } else {
                emptyList()
            }
        }

        val ranked = rankedMoves(preview, format, usage)
        val stab = ranked.filter { (_, details) -> group(preview, details) == BattleOpponentMoveGroup.STAB_ATTACK }
            .distinctBy { (_, details) -> canonical(details.typeId) }
            .take(policy.stabSlots)
        val coverage = ranked.filter { (_, details) ->
            group(preview, details) == BattleOpponentMoveGroup.COVERAGE_ATTACK
        }
        val subsets = subsets(stab)
        val candidates = linkedMapOf<String, WeightedInference>()
        subsets.forEach { selectedStab ->
            val slots = mutableListOf<BattleOpponentMoveSlotView>()
            addExpected(slots, selectedStab, BattleOpponentMoveGroup.STAB_ATTACK)
            val remainingAttacks = (policy.attackSlots - selectedStab.size).coerceAtLeast(0)
            addExpected(slots, coverage.take(remainingAttacks), BattleOpponentMoveGroup.COVERAGE_ATTACK)
            if (slots.isEmpty()) return@forEach
            repeat(minOf(policy.statusGuessSlots, MAX_MOVE_SLOTS - slots.size)) {
                slots += guessed(slots.size, BattleOpponentMoveGroup.STATUS_OTHER)
            }
            while (slots.size < MAX_MOVE_SLOTS) slots += guessed(slots.size, BattleOpponentMoveGroup.OTHER)
            val inference = BattleOpponentMoveInferenceView(pokemon.battlePokemonId, slots)
            val selectedIds = selectedStab.mapTo(hashSetOf()) { (moveId, _) -> canonical(moveId) }
            val weight = stab.fold(1.0) { product, (moveId, _) ->
                val rate = (usage?.rate(preview.speciesId, preview.formId, moveId) ?: DEFAULT_PRESENCE_RATE)
                    .coerceIn(MIN_PRESENCE_RATE, MAX_PRESENCE_RATE)
                product * if (canonical(moveId) in selectedIds) rate else 1.0 - rate
            }
            candidates.putIfAbsent(inferenceId(inference), WeightedInference(inference, weight, inferenceId(inference)))
        }
        return candidates.values.sortedWith(INFERENCE_ORDER)
    }

    private fun rankedMoves(
        preview: BattleOpponentTeamPreviewPokemonView,
        format: BattleFormat,
        usage: LocalMoveUsageLookup?,
    ): List<Map.Entry<String, BattleMoveCandidateView>> {
        val pool = requireNotNull(preview.moveCandidatePool)
        return pool.moveDetails.entries
            .filter { (moveId, details) -> moveId in pool.moveIds && details.currentPp > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<String, BattleMoveCandidateView>> { (moveId, _) ->
                    usage?.rate(preview.speciesId, preview.formId, moveId) ?: -1.0
                }.thenByDescending { (_, details) -> attackScore(details, format) }
                    .thenBy { (moveId, _) -> canonical(moveId) },
            )
    }

    private fun subsets(
        candidates: List<Map.Entry<String, BattleMoveCandidateView>>,
    ): List<List<Map.Entry<String, BattleMoveCandidateView>>> {
        val output = mutableListOf<List<Map.Entry<String, BattleMoveCandidateView>>>()
        fun visit(index: Int, selected: MutableList<Map.Entry<String, BattleMoveCandidateView>>) {
            if (index == candidates.size) {
                output += selected.toList()
                return
            }
            visit(index + 1, selected)
            selected += candidates[index]
            visit(index + 1, selected)
            selected.removeAt(selected.lastIndex)
        }
        visit(0, mutableListOf())
        return output.sortedWith(compareBy<List<Map.Entry<String, BattleMoveCandidateView>>> { it.size }
            .thenBy { subset -> subset.joinToString(",") { canonical(it.key) } })
    }

    private fun addExpected(
        slots: MutableList<BattleOpponentMoveSlotView>,
        candidates: List<Map.Entry<String, BattleMoveCandidateView>>,
        group: BattleOpponentMoveGroup,
    ) {
        candidates.asSequence().take(MAX_MOVE_SLOTS - slots.size).forEach { (moveId, details) ->
            slots += BattleOpponentMoveSlotView(
                slot = slots.size,
                moveId = moveId,
                group = group,
                knowledge = BattleOpponentMoveKnowledge.EXPECTED,
                source = BattleOpponentMoveSource.LEARNSET_EXPECTATION,
                details = details,
            )
        }
    }

    private fun guessed(slot: Int, group: BattleOpponentMoveGroup) = BattleOpponentMoveSlotView(
        slot = slot,
        moveId = null,
        group = group,
        knowledge = BattleOpponentMoveKnowledge.GUESS,
        source = BattleOpponentMoveSource.GROUP_GUESS,
    )

    private fun group(
        preview: BattleOpponentTeamPreviewPokemonView,
        details: BattleMoveCandidateView,
    ): BattleOpponentMoveGroup = when {
        details.damageCategory != BattleMoveDamageCategory.STATUS -> {
            if (preview.knownTypeIds.any { sameId(it, details.typeId) }) {
                BattleOpponentMoveGroup.STAB_ATTACK
            } else {
                BattleOpponentMoveGroup.COVERAGE_ATTACK
            }
        }
        isPureSetup(details) -> BattleOpponentMoveGroup.PURE_SETUP
        else -> BattleOpponentMoveGroup.STATUS_OTHER
    }

    private fun isPureSetup(details: BattleMoveCandidateView): Boolean {
        val effects = details.effects?.effects.orEmpty()
        return effects.isNotEmpty() && effects.all { effect ->
            effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.target == BattleMoveEffectTarget.USER &&
                effect.statStages.isNotEmpty()
        } && effects.any { effect -> effect.statStages.values.any { it > 0 } }
    }

    private fun attackScore(details: BattleMoveCandidateView, format: BattleFormat): Double {
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return 0.0
        val spreadValue = if (
            format == BattleFormat.DOUBLE && details.targetPattern == BattleMoveTargetPattern.ALL_OPPONENTS
        ) 1.5 else 1.0
        return details.power * (details.accuracy / 100.0) * spreadValue + details.priority.coerceAtLeast(0) * 12.0
    }

    private fun policy(tier: BattleTrainerTier): PublicPreviewPolicy = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> PublicPreviewPolicy(1, 0, 0, false)
        BattleTrainerTier.STANDARD -> PublicPreviewPolicy(1, 1, 1, false)
        BattleTrainerTier.ADVANCED -> PublicPreviewPolicy(1, 2, 1, true)
        BattleTrainerTier.BOSS -> PublicPreviewPolicy(2, 1, 1, true)
    }

    private fun teamWorldCap(tier: BattleTrainerTier, pokemonCount: Int): Int = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> 3
        BattleTrainerTier.STANDARD -> 6
        BattleTrainerTier.ADVANCED -> 10
        BattleTrainerTier.BOSS -> 16
    } * pokemonCount.coerceAtLeast(1)

    private fun inferenceId(inference: BattleOpponentMoveInferenceView): String =
        inference.slots.joinToString(",") { slot ->
            "${slot.slot}:${slot.moveId?.let(::canonical).orEmpty()}:${slot.group.name}:${slot.knowledge.name}"
        }

    private fun issue(
        code: NativeOpponentPreviewMoveCatalogIssueCode,
        pokemon: BattlePokemonStateView,
        previewSlotId: Int,
    ) = NativeOpponentPreviewMoveCatalogIssue(code, pokemon.battlePokemonId, previewSlotId)

    private fun sameId(left: String, right: String): Boolean = canonical(left) == canonical(right)
    private fun canonical(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private data class PublicPreviewPolicy(
        val stabSlots: Int,
        val coverageSlots: Int,
        val statusGuessSlots: Int,
        val variableStabShapes: Boolean,
    ) {
        val attackSlots: Int = stabSlots + coverageSlots
    }

    private data class WeightedInference(
        val inference: BattleOpponentMoveInferenceView,
        val weight: Double,
        val id: String,
    )

    private data class WeightedInferenceTeam(
        val inferences: List<BattleOpponentMoveInferenceView>,
        val weight: Double,
        val id: String,
    )

    private const val MAX_MOVE_SLOTS = 4
    private const val DEFAULT_PRESENCE_RATE = 0.5
    private const val MIN_PRESENCE_RATE = 1e-6
    private const val MAX_PRESENCE_RATE = 1.0 - MIN_PRESENCE_RATE
    private const val MOVE_POLICY_ID = "public-move-shape-prior-v1"
    private val INFERENCE_ORDER = compareByDescending<WeightedInference> { it.weight }.thenBy { it.id }
    private val TEAM_ORDER = compareByDescending<WeightedInferenceTeam> { it.weight }.thenBy { it.id }
}
