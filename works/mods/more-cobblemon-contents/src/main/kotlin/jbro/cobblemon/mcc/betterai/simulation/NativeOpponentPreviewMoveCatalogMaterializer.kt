package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveGroup
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveSource
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.state.LocalMoveThreatScore

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
    ): NativeOpponentPreviewMoveCatalogMaterialization {
        val previewBySlot = preview.pokemon.associateBy(BattleOpponentTeamPreviewPokemonView::previewSlotId)
        val team = threatenedTeam(roster.state)
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
                // An inference that names no move at all (a forme the catalog has no learnset for, such as
                // Mimikyu-Busted) says nothing: the preview's learnset fills the slots as before any reveal.
                sourceCatalog.inferredMovesForPokemon(pokemon.battlePokemonId)?.takeIf { inference ->
                    inference.slots.any { it.knowledge != BattleOpponentMoveKnowledge.GUESS }
                }?.let { existing ->
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
                    team,
                )
                if (candidates.isEmpty()) {
                    issues += issue(
                        NativeOpponentPreviewMoveCatalogIssueCode.EXECUTABLE_MOVE_UNAVAILABLE,
                        pokemon,
                        slot,
                    )
                } else {
                    // A Pokemon revealed earlier and back on the bench has no live inference; its revealed moves
                    // still bind the slots it is known to hold.
                    candidatesByPokemon[pokemon.battlePokemonId] = candidates
                        .map { it.withRevealedMoves(pokemon, previewPokemon) }
                        .distinctBy(WeightedInference::id)
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
        /** The types of the AI's living Pokemon, which break ties between equally strong coverage attacks. */
        team: List<Set<String>> = emptyList(),
    ): BattleOpponentMoveInferenceView {
        require(pokemon.side == BattleSide.OPPONENT)
        requireNotNull(preview.moveCandidatePool) {
            "A public preview move pool is required before inference"
        }
        val policy = policy(tier)
        val ranked = rankedMoves(preview, format, team)
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
        if (slots.isEmpty()) wholePool(ranked)?.forEach { addExpected(slots, listOf(it), group(preview, it.value)) }
        repeat(minOf(policy.statusGuessSlots, MAX_MOVE_SLOTS - slots.size)) {
            slots += guessed(slots.size, BattleOpponentMoveGroup.STATUS_OTHER)
        }
        while (slots.size < MAX_MOVE_SLOTS) slots += guessed(slots.size, BattleOpponentMoveGroup.OTHER)
        return BattleOpponentMoveInferenceView(pokemon.battlePokemonId, slots)
    }

    /** The types of the AI's living Pokemon in [state]. */
    private fun threatenedTeam(state: jbro.cobblemon.mcc.internal.ai.BattleStateView): List<Set<String>> =
        state.pokemon.filter { it.side == BattleSide.ALLY && !it.fainted && it.hpFraction > 0.0 }
            .map { pokemon -> pokemon.knownTypeIds.mapTo(linkedSetOf(), ::canonical) }

    private fun inferenceCandidates(
        pokemon: BattlePokemonStateView,
        preview: BattleOpponentTeamPreviewPokemonView,
        format: BattleFormat,
        tier: BattleTrainerTier,
        team: List<Set<String>>,
    ): List<WeightedInference> {
        val policy = policy(tier)
        if (!policy.variableStabShapes) {
            val inference = infer(pokemon, preview, format, tier, team)
            return if (inference.slots.any { it.knowledge != BattleOpponentMoveKnowledge.GUESS }) {
                listOf(WeightedInference(inference, 1.0, inferenceId(inference)))
            } else {
                emptyList()
            }
        }

        val ranked = rankedMoves(preview, format, team)
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
            // Without usage statistics every STAB move is as likely carried as not.
            val weight = stab.fold(1.0) { product, (moveId, _) ->
                product * if (canonical(moveId) in selectedIds) PRESENCE_RATE else 1.0 - PRESENCE_RATE
            }
            candidates.putIfAbsent(inferenceId(inference), WeightedInference(inference, weight, inferenceId(inference)))
        }
        if (candidates.isEmpty()) wholePool(ranked)?.let { pool ->
            val slots = mutableListOf<BattleOpponentMoveSlotView>()
            pool.forEach { addExpected(slots, listOf(it), group(preview, it.value)) }
            while (slots.size < MAX_MOVE_SLOTS) slots += guessed(slots.size, BattleOpponentMoveGroup.OTHER)
            val inference = BattleOpponentMoveInferenceView(pokemon.battlePokemonId, slots)
            candidates[inferenceId(inference)] = WeightedInference(inference, 1.0, inferenceId(inference))
        }
        return candidates.values.sortedWith(INFERENCE_ORDER)
    }

    /**
     * The preview's attacks, strongest first: by [LocalMoveThreatScore] (power x accuracy x secondary effect), a
     * doubles spread move counting half again; a tie goes to the type more threatening to the AI's [team].
     */
    private fun rankedMoves(
        preview: BattleOpponentTeamPreviewPokemonView,
        format: BattleFormat,
        team: List<Set<String>>,
    ): List<Map.Entry<String, BattleMoveCandidateView>> {
        val pool = requireNotNull(preview.moveCandidatePool)
        return pool.moveDetails.entries
            .filter { (moveId, details) -> moveId in pool.moveIds && details.currentPp > 0 }
            // Never assume an attack that fails unless its condition holds (Focus Punch, Steel Roller).
            .filter { (moveId, details) -> details.damageCategory == BattleMoveDamageCategory.STATUS ||
                jbro.cobblemon.mcc.internal.ai.BattleMoveSetExpectation.factor(moveId, details) > 0.0 }
            .sortedWith(
                compareByDescending<Map.Entry<String, BattleMoveCandidateView>> { (moveId, details) ->
                    // No ability: a team preview shows none, so no recoil exemption is public yet.
                    LocalMoveThreatScore.of(moveId, details) * spreadValue(details, format)
                }.thenByDescending { (_, details) -> LocalMoveThreatScore.typeThreat(canonical(details.typeId), team) }
                    .thenByDescending { (_, details) -> attackScore(details, format) }
                    .thenBy { (moveId, _) -> canonical(moveId) },
            )
    }

    /**
     * A species that learns no attack holds what it learns, when that fits in one set: Ditto's only move is Transform.
     * Nothing is guessed then, so it is not the status guess the slots otherwise leave out (2026-10-12); without it
     * the worlds could not be planned at all with such a Pokemon on the other side. Null when the pool has an attack or
     * more moves than a set holds.
     */
    private fun wholePool(ranked: List<Map.Entry<String, BattleMoveCandidateView>>): List<Map.Entry<String, BattleMoveCandidateView>>? =
        ranked.takeIf { pool -> pool.isNotEmpty() && pool.size <= MAX_MOVE_SLOTS &&
            pool.all { (_, details) -> details.damageCategory == BattleMoveDamageCategory.STATUS } }

    private fun spreadValue(details: BattleMoveCandidateView, format: BattleFormat): Double =
        if (format == BattleFormat.DOUBLE && details.targetPattern == BattleMoveTargetPattern.ALL_OPPONENTS) 1.5 else 1.0

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
        // Every type the Pokemon attacks with has its STAB attack in every world, ahead of the coverage (user rule
        // 2026-10-18: "추론은 무조건 자속기는 다 넣어야해"). One STAB slot, and worlds that left STAB out, read an
        // ADVANCED Lucario without its Fighting STAB. Coverage takes what is left: the strongest two or three non-STAB
        // attacks (2026-10-12); no status slot is guessed by name.
        BattleTrainerTier.INTRODUCTORY -> PublicPreviewPolicy(ALL_STAB_TYPES, 0, 0, false)
        BattleTrainerTier.STANDARD -> PublicPreviewPolicy(ALL_STAB_TYPES, 3, 0, false)
        BattleTrainerTier.ADVANCED -> PublicPreviewPolicy(ALL_STAB_TYPES, 3, 0, false)
        BattleTrainerTier.BOSS -> PublicPreviewPolicy(ALL_STAB_TYPES, 2, 0, false)
    }

    private fun teamWorldCap(tier: BattleTrainerTier, pokemonCount: Int): Int = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> 3
        BattleTrainerTier.STANDARD -> 6
        BattleTrainerTier.ADVANCED -> 10
        BattleTrainerTier.BOSS -> 16
    } * pokemonCount.coerceAtLeast(1)

    private fun WeightedInference.withRevealedMoves(
        pokemon: BattlePokemonStateView,
        preview: BattleOpponentTeamPreviewPokemonView,
    ): WeightedInference {
        val details = preview.moveCandidatePool?.moveDetails.orEmpty().entries.associate { canonical(it.key) to it }
        val revealed = pokemon.knownMoveIds.map(::canonical).distinct()
            .mapNotNull { id -> details[id] }.take(MAX_MOVE_SLOTS)
        if (revealed.isEmpty()) return this
        val revealedIds = revealed.mapTo(hashSetOf()) { canonical(it.key) }
        val kept = inference.slots.filterNot { slot -> slot.moveId?.let(::canonical) in revealedIds }
        val slots = revealed.map { (moveId, move) ->
            BattleOpponentMoveSlotView(0, moveId, group(preview, move), BattleOpponentMoveKnowledge.CONFIRMED,
                BattleOpponentMoveSource.PUBLIC_REVEAL, move)
        }.plus(kept).take(MAX_MOVE_SLOTS).mapIndexed { index, slot -> slot.copy(slot = index) }
        val bound = BattleOpponentMoveInferenceView(inference.battlePokemonId, slots)
        return WeightedInference(bound, weight, inferenceId(bound))
    }

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
    private fun canonical(value: String): String = PublicIds.canonical(value)

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
    /** A STAB attack for each of the Pokemon's types, which never exceed the four slots. */
    private const val ALL_STAB_TYPES = MAX_MOVE_SLOTS
    private const val PRESENCE_RATE = 0.5
    private const val MOVE_POLICY_ID = "public-move-shape-prior-v1"
    private val INFERENCE_ORDER = compareByDescending<WeightedInference> { it.weight }.thenBy { it.id }
    private val TEAM_ORDER = compareByDescending<WeightedInferenceTeam> { it.weight }.thenBy { it.id }
}
