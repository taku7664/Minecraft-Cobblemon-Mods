package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Collections
import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveGroup
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

/**
 * The only opponent-move input admitted to a native Showdown hypothesis.
 *
 * Candidate pools and the Pokemon's backing set are deliberately absent from this compiler. The
 * compatibility layer has already spent the difficulty tier's hidden-information allowance when it
 * produced the normalized four-slot inference. Reading another source here would silently widen that
 * allowance and make two publicly identical battles produce different native worlds.
 */
internal object NativeMoveHypothesisCompiler {
    fun compile(
        pokemon: BattlePokemonStateView,
        catalog: BattlePublicActionCatalogView,
    ): NativeMoveHypothesisCompilation {
        require(pokemon.side == BattleSide.OPPONENT) {
            "Opponent move hypotheses can only be compiled for an opposing Pokemon"
        }
        val inference = catalog.inferredMovesForPokemon(pokemon.battlePokemonId)
            ?: return NativeMoveHypothesisCompilation(
                battlePokemonId = pokemon.battlePokemonId,
                slots = emptyList(),
                unavailableReason = "normalized_inference_missing",
            )
        val slots = inference.slots.map { slot ->
            NativeOpponentMoveSlotHypothesis(
                slot = slot.slot,
                moveId = slot.moveId?.let(::showdownId),
                group = slot.group,
                knowledge = slot.knowledge,
                source = slot.source,
            )
        }
        val concrete = slots.filter { it.knowledge != BattleOpponentMoveKnowledge.GUESS }
        require(concrete.map { it.moveId }.distinct().size == concrete.size) {
            "Normalized move slots collapse to duplicate Showdown move IDs"
        }
        val complete = slots.size == MAX_MOVE_SLOTS && slots.map { it.slot } == (0 until MAX_MOVE_SLOTS).toList()
        return NativeMoveHypothesisCompilation(
            battlePokemonId = pokemon.battlePokemonId,
            slots = slots,
            unavailableReason = when {
                !complete -> "normalized_inference_incomplete"
                concrete.isEmpty() -> "no_concrete_moves"
                else -> null
            },
        )
    }

    private fun showdownId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
        .also { require(it.isNotBlank()) { "Native Showdown move ID cannot be blank" } }

    private const val MAX_MOVE_SLOTS = 4
}

internal data class NativeMoveHypothesisCompilation(
    val battlePokemonId: UUID,
    val slots: List<NativeOpponentMoveSlotHypothesis>,
    val unavailableReason: String?,
) {
    val concreteMoves: List<NativeConcreteMoveHypothesis> get() = slots.mapNotNull { slot ->
        if (slot.knowledge == BattleOpponentMoveKnowledge.GUESS) null else NativeConcreteMoveHypothesis(
            slot.slot,
            requireNotNull(slot.moveId),
            slot.group,
            slot.knowledge,
            slot.source,
        )
    }
    val unresolvedSlots: List<NativeUnresolvedMoveSlot> get() = slots.mapNotNull { slot ->
        if (slot.knowledge == BattleOpponentMoveKnowledge.GUESS) {
            NativeUnresolvedMoveSlot(slot.slot, slot.group)
        } else {
            null
        }
    }
    val nativeMoveIds: List<String> get() = concreteMoves.map(NativeConcreteMoveHypothesis::moveId)
    val hasExecutableMove: Boolean get() = concreteMoves.isNotEmpty()
    val hasUnresolvedSlots: Boolean get() = unresolvedSlots.isNotEmpty()
    val isCompleteSet: Boolean get() = slots.size == MAX_MOVE_SLOTS &&
        slots.map(NativeOpponentMoveSlotHypothesis::slot) == (0 until MAX_MOVE_SLOTS).toList()

    fun completeSetOrNull(): NativeOpponentMoveSetHypothesis? = if (unavailableReason == null) {
        NativeOpponentMoveSetHypothesis(battlePokemonId, slots)
    } else {
        null
    }

    init {
        require(slots == slots.sortedBy(NativeOpponentMoveSlotHypothesis::slot)) {
            "Native move hypothesis slots must be sorted"
        }
        require((unavailableReason == null) == (isCompleteSet && hasExecutableMove)) {
            "An available native move set must have four logical slots and an executable move"
        }
        require(slots.map(NativeOpponentMoveSlotHypothesis::slot).distinct().size == slots.size) {
            "Native move hypothesis slots must be unique"
        }
    }

    private companion object {
        const val MAX_MOVE_SLOTS = 4
    }
}

internal class NativeOpponentMoveSetHypothesis(
    val battlePokemonId: UUID,
    slots: List<NativeOpponentMoveSlotHypothesis>,
) {
    val slots: List<NativeOpponentMoveSlotHypothesis> = Collections.unmodifiableList(slots.toList())
    val nativeMoveIds: List<String> = Collections.unmodifiableList(
        this.slots.mapNotNull(NativeOpponentMoveSlotHypothesis::moveId),
    )
    val fingerprint: String = this.slots.joinToString(",") { slot ->
        "${slot.slot}:${slot.knowledge.name.lowercase(Locale.ROOT)}:${slot.moveId ?: "?"}:${slot.group.name.lowercase(Locale.ROOT)}"
    }

    init {
        require(slots.size == MAX_MOVE_SLOTS)
        require(slots.map(NativeOpponentMoveSlotHypothesis::slot) == (0 until MAX_MOVE_SLOTS).toList())
        require(nativeMoveIds.isNotEmpty())
        require(nativeMoveIds.distinct().size == nativeMoveIds.size)
    }

    override fun equals(other: Any?): Boolean = other is NativeOpponentMoveSetHypothesis &&
        battlePokemonId == other.battlePokemonId && slots == other.slots

    override fun hashCode(): Int = 31 * battlePokemonId.hashCode() + slots.hashCode()

    override fun toString(): String =
        "NativeOpponentMoveSetHypothesis(battlePokemonId=$battlePokemonId, slots=$slots)"

    private companion object {
        const val MAX_MOVE_SLOTS = 4
    }
}

internal data class NativeOpponentMoveSlotHypothesis(
    val slot: Int,
    val moveId: String?,
    val group: BattleOpponentMoveGroup,
    val knowledge: BattleOpponentMoveKnowledge,
    val source: BattleOpponentMoveSource,
) {
    init {
        require(slot in 0 until 4)
        when (knowledge) {
            BattleOpponentMoveKnowledge.GUESS -> {
                require(moveId == null)
                require(source == BattleOpponentMoveSource.GROUP_GUESS)
            }
            BattleOpponentMoveKnowledge.EXPECTED -> {
                require(!moveId.isNullOrBlank())
                require(source == BattleOpponentMoveSource.LEARNSET_EXPECTATION)
            }
            BattleOpponentMoveKnowledge.CONFIRMED -> {
                require(!moveId.isNullOrBlank())
                require(source == BattleOpponentMoveSource.PUBLIC_REVEAL ||
                    source == BattleOpponentMoveSource.DIFFICULTY_SET_READ)
            }
        }
    }
}

internal data class NativeConcreteMoveHypothesis(
    val slot: Int,
    val moveId: String,
    val group: BattleOpponentMoveGroup,
    val knowledge: BattleOpponentMoveKnowledge,
    val source: BattleOpponentMoveSource,
) {
    init {
        require(slot in 0 until 4)
        require(moveId.isNotBlank())
        require(knowledge != BattleOpponentMoveKnowledge.GUESS) {
            "A guessed move slot cannot become a concrete native move"
        }
    }
}

internal data class NativeUnresolvedMoveSlot(
    val slot: Int,
    val group: BattleOpponentMoveGroup,
) {
    init {
        require(slot in 0 until 4)
    }
}
