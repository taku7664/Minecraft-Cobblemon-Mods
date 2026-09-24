package jbro.cobblemon.morebattlecontent.api.ai

import java.util.Collections
import java.util.UUID

/** What an inferred opponent move slot is expected to do. */
enum class BattleOpponentMoveGroup {
    STAB_ATTACK,
    COVERAGE_ATTACK,
    PURE_SETUP,
    STATUS_OTHER,
    OTHER,
}

/** How much evidence backs one normalized opponent move slot. */
enum class BattleOpponentMoveKnowledge {
    /** Only a broad role is assumed. It never becomes a recursive move branch. */
    GUESS,
    /** A concrete legal learnset move chosen by the inference policy. */
    EXPECTED,
    /** A revealed move or a move admitted by the tier's hidden-information budget. */
    CONFIRMED,
}

/** Kept separate from knowledge so diagnostics can distinguish why a slot is trusted. */
enum class BattleOpponentMoveSource {
    PUBLIC_REVEAL,
    DIFFICULTY_SET_READ,
    LEARNSET_EXPECTATION,
    GROUP_GUESS,
}

data class BattleOpponentMoveSlotView(
    val slot: Int,
    val moveId: String?,
    val group: BattleOpponentMoveGroup,
    val knowledge: BattleOpponentMoveKnowledge,
    val source: BattleOpponentMoveSource,
    val details: BattleMoveCandidateView? = null,
) {
    init {
        require(slot in 0 until MAX_MOVE_SLOTS)
        require(moveId == null || moveId.isNotBlank())
        when (knowledge) {
            BattleOpponentMoveKnowledge.GUESS -> {
                require(moveId == null && details == null) { "A guessed slot cannot pretend to be a concrete move" }
                require(source == BattleOpponentMoveSource.GROUP_GUESS)
            }
            BattleOpponentMoveKnowledge.EXPECTED -> {
                require(moveId != null && details != null) { "An expected slot requires a concrete move template" }
                require(source == BattleOpponentMoveSource.LEARNSET_EXPECTATION)
            }
            BattleOpponentMoveKnowledge.CONFIRMED -> {
                require(moveId != null && details != null) { "A confirmed slot requires a concrete move template" }
                require(source == BattleOpponentMoveSource.PUBLIC_REVEAL ||
                    source == BattleOpponentMoveSource.DIFFICULTY_SET_READ)
            }
        }
    }

    companion object {
        const val MAX_MOVE_SLOTS = 4
    }
}

class BattleOpponentMoveInferenceView(
    val battlePokemonId: UUID,
    slots: List<BattleOpponentMoveSlotView>,
) {
    val slots: List<BattleOpponentMoveSlotView> = Collections.unmodifiableList(slots.sortedBy { it.slot })

    init {
        require(this.slots.size <= BattleOpponentMoveSlotView.MAX_MOVE_SLOTS)
        require(this.slots.map { it.slot }.distinct().size == this.slots.size)
        require(this.slots.mapNotNull { it.moveId }.distinct().size == this.slots.mapNotNull { it.moveId }.size)
    }
}
