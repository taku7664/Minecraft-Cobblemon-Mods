package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView

internal enum class NativeOpponentRosterIssueCode {
    UNSUPPORTED_SELECTION_RULE,
    PUBLIC_STATE_NOT_INITIAL,
    INITIAL_REVEAL_LAYOUT_INVALID,
    REVEALED_COUNT_EXCEEDS_SELECTION,
    REVEALED_ASSIGNMENT_UNAVAILABLE,
}

internal data class NativeOpponentRosterIssue(
    val code: NativeOpponentRosterIssueCode,
    val battlePokemonId: UUID? = null,
)

/** One selected roster and one public-identity mapping; no live hidden roster is read. */
internal data class NativeOpponentRosterHypothesis(
    val hypothesisId: String,
    val probability: Double,
    val selectedPreviewSlotIds: List<Int>,
    val revealedAssignments: Map<UUID, Int>,
) {
    init {
        require(hypothesisId.isNotBlank())
        require(probability.isFinite() && probability > 0.0 && probability <= 1.0)
        require(selectedPreviewSlotIds.isNotEmpty())
        require(selectedPreviewSlotIds == selectedPreviewSlotIds.sorted())
        require(selectedPreviewSlotIds.distinct().size == selectedPreviewSlotIds.size)
        require(revealedAssignments.values.all(selectedPreviewSlotIds::contains))
        require(revealedAssignments.values.distinct().size == revealedAssignments.size)
    }
}

internal data class NativeOpponentRosterCompilation(
    val hypotheses: List<NativeOpponentRosterHypothesis>,
    val issues: List<NativeOpponentRosterIssue>,
) {
    init {
        require(hypotheses.isEmpty() != issues.isEmpty()) {
            "Opponent roster compilation must return hypotheses or explicit issues, never both or neither"
        }
        if (hypotheses.isNotEmpty()) {
            require(kotlin.math.abs(hypotheses.sumOf(NativeOpponentRosterHypothesis::probability) - 1.0) < 1e-9)
        }
    }
}

/** Deterministically enumerates private selection worlds from an opaque public team preview. */
internal object NativeOpponentRosterHypothesisCompiler {
    fun compile(
        state: BattleStateView,
        preview: BattleOpponentTeamPreviewView,
        compatiblePreviewSlots: Map<UUID, Set<Int>> = emptyMap(),
    ): NativeOpponentRosterCompilation {
        val issues = linkedSetOf<NativeOpponentRosterIssue>()
        val revealed = state.pokemon.filter { it.side == BattleSide.OPPONENT }
            .sortedBy { it.battlePokemonId.toString() }
        val selectionRuleSupported = when (state.format) {
            BattleFormat.SINGLE -> true
            BattleFormat.DOUBLE ->
                preview.pokemon.size == BattleOpponentTeamPreviewView.MAX_PREVIEW_SIZE &&
                    preview.selectionSize in setOf(4, preview.pokemon.size)
        }
        val expectedActiveSlots = when (state.format) {
            BattleFormat.SINGLE -> setOf(0)
            BattleFormat.DOUBLE -> setOf(0, 1)
        }
        if (!selectionRuleSupported) {
            issues += NativeOpponentRosterIssue(NativeOpponentRosterIssueCode.UNSUPPORTED_SELECTION_RULE)
        }
        if (state.turn !in 0..1 ||
            !NativeOpeningStateRules.acceptsObservations(state) ||
            revealed.any(BattlePokemonStateView::fainted) ||
            state.remainingPokemonBySide.getValue(BattleSide.OPPONENT) != preview.selectionSize
        ) {
            issues += NativeOpponentRosterIssue(NativeOpponentRosterIssueCode.PUBLIC_STATE_NOT_INITIAL)
        }
        val revealedActiveSlots = revealed.mapNotNull(BattlePokemonStateView::activeSlot).toSet()
        if (revealed.isNotEmpty() &&
            (revealed.any { it.activeSlot == null } ||
                revealed.size != expectedActiveSlots.size ||
                revealedActiveSlots != expectedActiveSlots)
        ) {
            issues += NativeOpponentRosterIssue(NativeOpponentRosterIssueCode.INITIAL_REVEAL_LAYOUT_INVALID)
        }
        if (revealed.size > preview.selectionSize) {
            issues += NativeOpponentRosterIssue(NativeOpponentRosterIssueCode.REVEALED_COUNT_EXCEEDS_SELECTION)
        }

        val previewById = preview.pokemon.associateBy(BattleOpponentTeamPreviewPokemonView::previewSlotId)
        val candidates = revealed.associateWith { pokemon ->
            val supplied = compatiblePreviewSlots[pokemon.battlePokemonId]
            (supplied ?: preview.pokemon.filter { samePublicIdentity(pokemon, it) }
                .mapTo(linkedSetOf(), BattleOpponentTeamPreviewPokemonView::previewSlotId))
                .filterTo(linkedSetOf()) { it in previewById }
                .toList()
                .sorted()
        }
        candidates.filterValues(List<Int>::isEmpty).keys.forEach { pokemon ->
            issues += NativeOpponentRosterIssue(
                NativeOpponentRosterIssueCode.REVEALED_ASSIGNMENT_UNAVAILABLE,
                pokemon.battlePokemonId,
            )
        }
        if (issues.isNotEmpty()) return NativeOpponentRosterCompilation(emptyList(), issues.toList())

        val assignments = mutableListOf<Map<UUID, Int>>()
        enumerateAssignments(revealed, candidates, 0, linkedMapOf(), linkedSetOf(), assignments)
        if (assignments.isEmpty()) {
            return NativeOpponentRosterCompilation(
                emptyList(),
                revealed.map {
                    NativeOpponentRosterIssue(
                        NativeOpponentRosterIssueCode.REVEALED_ASSIGNMENT_UNAVAILABLE,
                        it.battlePokemonId,
                    )
                }.ifEmpty {
                    listOf(NativeOpponentRosterIssue(NativeOpponentRosterIssueCode.REVEALED_ASSIGNMENT_UNAVAILABLE))
                },
            )
        }

        val raw = mutableListOf<Pair<List<Int>, Map<UUID, Int>>>()
        assignments.forEach { assignment ->
            val fixed = assignment.values.toSet()
            val remaining = previewById.keys.filterNot(fixed::contains).sorted()
            val needed = preview.selectionSize - fixed.size
            combinations(remaining, needed).forEach { extra ->
                val selected = (fixed + extra).sorted()
                if (canProduceEveryPublicAppearance(selected, assignment, revealed, previewById)) {
                    raw += selected to assignment
                }
            }
        }
        val ordered = raw.distinctBy { (selected, assignment) -> selected to assignment }
            .sortedBy { (selected, assignment) -> hypothesisId(selected, assignment) }
        if (ordered.isEmpty()) {
            return NativeOpponentRosterCompilation(
                emptyList(),
                revealed.map {
                    NativeOpponentRosterIssue(
                        NativeOpponentRosterIssueCode.REVEALED_ASSIGNMENT_UNAVAILABLE,
                        it.battlePokemonId,
                    )
                }.ifEmpty {
                    listOf(NativeOpponentRosterIssue(NativeOpponentRosterIssueCode.REVEALED_ASSIGNMENT_UNAVAILABLE))
                },
            )
        }
        val probability = 1.0 / ordered.size.toDouble()
        return NativeOpponentRosterCompilation(
            hypotheses = ordered.map { (selected, assignment) ->
                NativeOpponentRosterHypothesis(
                    hypothesisId = hypothesisId(selected, assignment),
                    probability = probability,
                    selectedPreviewSlotIds = selected,
                    revealedAssignments = assignment.toSortedMap(compareBy { it.toString() }),
                )
            },
            issues = emptyList(),
        )
    }

    private fun enumerateAssignments(
        revealed: List<BattlePokemonStateView>,
        candidates: Map<BattlePokemonStateView, List<Int>>,
        index: Int,
        current: MutableMap<UUID, Int>,
        used: MutableSet<Int>,
        output: MutableList<Map<UUID, Int>>,
    ) {
        if (index == revealed.size) {
            output += LinkedHashMap(current)
            return
        }
        val pokemon = revealed[index]
        candidates.getValue(pokemon).forEach { slot ->
            if (!used.add(slot)) return@forEach
            current[pokemon.battlePokemonId] = slot
            enumerateAssignments(revealed, candidates, index + 1, current, used, output)
            current.remove(pokemon.battlePokemonId)
            used.remove(slot)
        }
    }

    private fun combinations(values: List<Int>, size: Int): List<List<Int>> {
        if (size == 0) return listOf(emptyList())
        if (size < 0 || size > values.size) return emptyList()
        val result = mutableListOf<List<Int>>()
        fun visit(start: Int, selected: MutableList<Int>) {
            if (selected.size == size) {
                result += selected.toList()
                return
            }
            for (index in start until values.size) {
                selected += values[index]
                visit(index + 1, selected)
                selected.removeAt(selected.lastIndex)
            }
        }
        visit(0, mutableListOf())
        return result
    }

    private fun samePublicIdentity(
        revealed: BattlePokemonStateView,
        preview: BattleOpponentTeamPreviewPokemonView,
    ): Boolean {
        val revealedForm = revealed.formId
        val previewForm = preview.formId
        return normalizedId(revealed.speciesId) == normalizedId(preview.speciesId) &&
            (revealedForm == null || previewForm == null ||
                normalizedId(revealedForm) == normalizedId(previewForm))
    }

    /**
     * A cross-identity assignment represents an Illusion-compatible world. At the opening, every
     * such public appearance must be producible by one common unassigned final team member; merely
     * selecting the hidden Illusion user is not enough.
     */
    private fun canProduceEveryPublicAppearance(
        selected: List<Int>,
        assignment: Map<UUID, Int>,
        revealed: List<BattlePokemonStateView>,
        previewById: Map<Int, BattleOpponentTeamPreviewPokemonView>,
    ): Boolean {
        val revealedById = revealed.associateBy(BattlePokemonStateView::battlePokemonId)
        val mismatchedAppearances = assignment.mapNotNull { (pokemonId, assignedSlot) ->
            val pokemon = revealedById.getValue(pokemonId)
            val assignedPreview = previewById.getValue(assignedSlot)
            pokemon.takeUnless { samePublicIdentity(it, assignedPreview) }
        }
        if (mismatchedAppearances.isEmpty()) return true
        val activeAssignments = assignment.values.toSet()
        return selected.asSequence().filterNot(activeAssignments::contains).any { candidateSlot ->
            val candidate = previewById.getValue(candidateSlot)
            mismatchedAppearances.all { samePublicIdentity(it, candidate) }
        }
    }

    private fun hypothesisId(selected: List<Int>, assignment: Map<UUID, Int>): String =
        "roster:${selected.joinToString(",")}|map:" + assignment.entries.sortedBy { it.key.toString() }
            .joinToString(",") { (pokemon, slot) -> "$pokemon=$slot" }

    private fun normalizedId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
