package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleObservedEventView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

internal enum class NativeObservedTurnActionIssueCode {
    UNKNOWN_OBSERVED_ACTOR,
    OBSERVED_ACTION_SLOT_MISSING,
    NO_MATCHING_NATIVE_ACTION,
}

internal data class NativeObservedTurnActionIssue(
    val code: NativeObservedTurnActionIssueCode,
    val eventSequence: Long? = null,
)

internal data class NativeObservedTurnActionMatch(
    val actions: List<BattleActionCandidate>,
    val issues: List<NativeObservedTurnActionIssue>,
) {
    init {
        require(actions.isEmpty() == issues.isNotEmpty()) {
            "Observed native actions are either available or unavailable with explicit issues"
        }
    }
}

/**
 * Conditions native request actions on public command evidence without inventing an unseen command.
 *
 * [events] is the complete contiguous public window for these submitted commands. Consequently,
 * the absence of a Tera reveal is negative evidence: Showdown queues Tera before ordinary moves,
 * even when that Pokemon later faints before its move executes.
 *
 * A move event owns its active slot even if a pivot or forced replacement later emits SWITCHED for
 * the same slot. A completely unobserved slot remains a wildcard: the Pokemon may have fainted or
 * otherwise failed before revealing the command it had selected.
 */
internal object NativeObservedTurnActionMatcher {
    fun match(
        format: BattleFormat,
        side: BattleSide,
        frame: NativeBattleFrame,
        nativeActions: List<BattleActionCandidate>,
        events: List<BattleObservedEventView>,
    ): NativeObservedTurnActionMatch {
        require(nativeActions.map(BattleActionCandidate::actionId).distinct().size == nativeActions.size)
        val sideTeam = team(side, frame)
        val sideIds = sideTeam.mapTo(linkedSetOf()) { UUID.fromString(it.uuid) }
        val allIds = (frame.p1Team + frame.p2Team).mapTo(linkedSetOf()) { UUID.fromString(it.uuid) }
        val relevant = events.asSequence()
            .filter { it.kind in ACTION_EVIDENCE_KINDS }
            .sortedBy(BattleObservedEventView::sequence)
            .toList()
        relevant.firstOrNull { it.actorPokemonId != null && it.actorPokemonId !in allIds }?.let { unknown ->
            return failure(NativeObservedTurnActionIssueCode.UNKNOWN_OBSERVED_ACTOR, unknown.sequence)
        }
        val sideEvents = relevant.filter { it.actorPokemonId in sideIds }
        sideEvents.firstOrNull { it.actorSlot == null }?.let { missing ->
            return failure(NativeObservedTurnActionIssueCode.OBSERVED_ACTION_SLOT_MISSING, missing.sequence)
        }
        val evidenceBySlot = sideEvents.groupBy { requireNotNull(it.actorSlot) }.mapValues { (_, slotEvents) ->
            SlotEvidence(
                moveIds = slotEvents.asSequence()
                    .filter { it.kind == BattleObservedEventKind.MOVE_USED }
                    .mapNotNull(BattleObservedEventView::publicValueId)
                    .map(::nativeId)
                    .toSet(),
                targetPokemonIds = slotEvents.asSequence()
                    .filter { it.kind == BattleObservedEventKind.MOVE_USED }
                    .flatMap { it.targetPokemonIds.asSequence() }
                    .toSet(),
                incomingPokemonIds = slotEvents.asSequence()
                    .filter { it.kind == BattleObservedEventKind.SWITCHED }
                    .mapNotNull(BattleObservedEventView::actorPokemonId)
                    .toSet(),
                teraRevealed = slotEvents.any { it.kind == BattleObservedEventKind.TERA_TYPE_REVEALED },
            )
        }
        val revealedTeraSlots = sideEvents.asSequence()
            .filter { it.kind == BattleObservedEventKind.TERA_TYPE_REVEALED }
            .map { requireNotNull(it.actorSlot) }
            .toSet()
        val matching = nativeActions.filter { action ->
            val components = components(action)
            val bySlot = components.mapNotNull { component -> component.actorSlot?.let { it to component } }.toMap()
            val selectedTeraSlots = components.asSequence()
                .filter { it.mechanic?.mechanicId?.let(::canonicalMechanic) == "tera" }
                .mapNotNull(BattleActionCandidate::actorSlot)
                .toSet()
            selectedTeraSlots == revealedTeraSlots && evidenceBySlot.all { (slot, evidence) ->
                val component = bySlot[slot] ?: return@all false
                matches(format, frame, component, evidence)
            }
        }
        return if (matching.isEmpty()) {
            failure(NativeObservedTurnActionIssueCode.NO_MATCHING_NATIVE_ACTION)
        } else {
            NativeObservedTurnActionMatch(matching, emptyList())
        }
    }

    private fun matches(
        @Suppress("UNUSED_PARAMETER") format: BattleFormat,
        frame: NativeBattleFrame,
        action: BattleActionCandidate,
        evidence: SlotEvidence,
    ): Boolean {
        val mechanic = action.mechanic?.mechanicId?.let(::canonicalMechanic)
        if (evidence.teraRevealed != (mechanic == "tera")) return false
        if (evidence.moveIds.isNotEmpty()) {
            if (action.kind != BattleActionKind.USE_MOVE ||
                action.moveId?.let(::nativeId) !in evidence.moveIds
            ) {
                return false
            }
            if (action.targets.isNotEmpty() && evidence.targetPokemonIds.isNotEmpty()) {
                return action.targets.mapTo(linkedSetOf()) { target ->
                    val active = when (target.side) {
                        BattleSide.ALLY -> frame.p1Active
                        BattleSide.OPPONENT -> frame.p2Active
                    }
                    UUID.fromString(requireNotNull(active.singleOrNull { it.activeSlot == target.slot }).uuid)
                } == evidence.targetPokemonIds
            }
            return true
        }
        if (evidence.incomingPokemonIds.isNotEmpty()) {
            return action.kind == BattleActionKind.SWITCH && action.switchPokemonId in evidence.incomingPokemonIds
        }
        return true
    }

    private fun components(action: BattleActionCandidate): List<BattleActionCandidate> =
        if (action.kind == BattleActionKind.COMPOSITE) action.componentActions else listOf(action)

    private fun team(side: BattleSide, frame: NativeBattleFrame): List<NativePokemonFrame> = when (side) {
        BattleSide.ALLY -> frame.p1Team
        BattleSide.OPPONENT -> frame.p2Team
    }

    private fun canonicalMechanic(value: String): String = when (nativeId(value)) {
        "tera", "terastallize", "terastallization" -> "tera"
        "mega", "megaevolution" -> "mega"
        "dynamax", "dmax" -> "dynamax"
        else -> nativeId(value)
    }

    private fun nativeId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun failure(code: NativeObservedTurnActionIssueCode, sequence: Long? = null) =
        NativeObservedTurnActionMatch(emptyList(), listOf(NativeObservedTurnActionIssue(code, sequence)))

    private data class SlotEvidence(
        val moveIds: Set<String>,
        val targetPokemonIds: Set<UUID>,
        val incomingPokemonIds: Set<UUID>,
        val teraRevealed: Boolean,
    )

    private val ACTION_EVIDENCE_KINDS = setOf(
        BattleObservedEventKind.MOVE_USED,
        BattleObservedEventKind.SWITCHED,
        BattleObservedEventKind.TERA_TYPE_REVEALED,
    )
}
