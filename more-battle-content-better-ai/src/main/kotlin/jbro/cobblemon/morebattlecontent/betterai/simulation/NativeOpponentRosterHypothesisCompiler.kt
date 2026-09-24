package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
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

/** Deterministically enumerates private 3/4-selection worlds from an opaque public team preview. */
internal object NativeOpponentRosterHypothesisCompiler {
    fun compile(
        state: BattleStateView,
        preview: BattleOpponentTeamPreviewView,
        compatiblePreviewSlots: Map<UUID, Set<Int>> = emptyMap(),
    ): NativeOpponentRosterCompilation {
        val issues = linkedSetOf<NativeOpponentRosterIssue>()
        val revealed = state.pokemon.filter { it.side == BattleSide.OPPONENT }
            .sortedBy { it.battlePokemonId.toString() }
        val expectedSelectionSize = when (state.format) {
            BattleFormat.SINGLE -> 3
            BattleFormat.DOUBLE -> 4
        }
        val expectedActiveSlots = when (state.format) {
            BattleFormat.SINGLE -> setOf(0)
            BattleFormat.DOUBLE -> setOf(0, 1)
        }
        val activePokemonIds = state.pokemon.asSequence()
            .filter { it.activeSlot != null && !it.fainted }
            .map(BattlePokemonStateView::battlePokemonId)
            .toSet()
        val switchedActors = state.observedEvents.asSequence()
            .filter { it.kind == BattleObservedEventKind.SWITCHED }
            .mapNotNull { it.actorPokemonId }
            .toList()
        val invalidOpeningEvents = state.observedEvents.any { event ->
            event.turn != 0 || event.kind !in OPENING_EVENT_KINDS || when (event.kind) {
                BattleObservedEventKind.FIELD_EFFECT_CHANGED -> event.actorPokemonId != null
                else -> event.actorPokemonId !in activePokemonIds
            }
        } || switchedActors.size != switchedActors.distinct().size
        if (preview.pokemon.size != BattleOpponentTeamPreviewView.MAX_PREVIEW_SIZE ||
            preview.selectionSize != expectedSelectionSize
        ) {
            issues += NativeOpponentRosterIssue(NativeOpponentRosterIssueCode.UNSUPPORTED_SELECTION_RULE)
        }
        if (state.turn !in 0..1 ||
            invalidOpeningEvents ||
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
                raw += (fixed + extra).sorted() to assignment
            }
        }
        val ordered = raw.distinctBy { (selected, assignment) -> selected to assignment }
            .sortedBy { (selected, assignment) -> hypothesisId(selected, assignment) }
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

    private fun hypothesisId(selected: List<Int>, assignment: Map<UUID, Int>): String =
        "roster:${selected.joinToString(",")}|map:" + assignment.entries.sortedBy { it.key.toString() }
            .joinToString(",") { (pokemon, slot) -> "$pokemon=$slot" }

    private fun normalizedId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private val OPENING_EVENT_KINDS = setOf(
        BattleObservedEventKind.SWITCHED,
        BattleObservedEventKind.ABILITY_REVEALED,
        BattleObservedEventKind.HELD_ITEM_REVEALED,
        BattleObservedEventKind.FIELD_EFFECT_CHANGED,
    )
}
