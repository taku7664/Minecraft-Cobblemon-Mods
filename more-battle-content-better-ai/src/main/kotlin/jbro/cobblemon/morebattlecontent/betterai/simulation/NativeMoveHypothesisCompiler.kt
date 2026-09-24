package jbro.cobblemon.morebattlecontent.betterai.simulation

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
                concreteMoves = emptyList(),
                unresolvedSlots = emptyList(),
                unavailableReason = "normalized_inference_missing",
            )
        val concrete = inference.slots.mapNotNull { slot ->
            if (slot.knowledge == BattleOpponentMoveKnowledge.GUESS) return@mapNotNull null
            NativeConcreteMoveHypothesis(
                slot = slot.slot,
                moveId = showdownId(requireNotNull(slot.moveId)),
                group = slot.group,
                knowledge = slot.knowledge,
                source = slot.source,
            )
        }
        require(concrete.map { it.moveId }.distinct().size == concrete.size) {
            "Normalized move slots collapse to duplicate Showdown move IDs"
        }
        val unresolved = inference.slots.mapNotNull { slot ->
            slot.takeIf { it.knowledge == BattleOpponentMoveKnowledge.GUESS }?.let {
                NativeUnresolvedMoveSlot(it.slot, it.group)
            }
        }
        return NativeMoveHypothesisCompilation(
            battlePokemonId = pokemon.battlePokemonId,
            concreteMoves = concrete,
            unresolvedSlots = unresolved,
            unavailableReason = "no_concrete_moves".takeIf { concrete.isEmpty() },
        )
    }

    private fun showdownId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
        .also { require(it.isNotBlank()) { "Native Showdown move ID cannot be blank" } }
}

internal data class NativeMoveHypothesisCompilation(
    val battlePokemonId: UUID,
    val concreteMoves: List<NativeConcreteMoveHypothesis>,
    val unresolvedSlots: List<NativeUnresolvedMoveSlot>,
    val unavailableReason: String?,
) {
    val nativeMoveIds: List<String> get() = concreteMoves.map(NativeConcreteMoveHypothesis::moveId)
    val hasExecutableMove: Boolean get() = concreteMoves.isNotEmpty()
    val hasUnresolvedSlots: Boolean get() = unresolvedSlots.isNotEmpty()

    init {
        require((unavailableReason == null) == concreteMoves.isNotEmpty()) {
            "An executable native move hypothesis must not carry an unavailable reason"
        }
        require((concreteMoves.map { it.slot } + unresolvedSlots.map { it.slot }).distinct().size ==
            concreteMoves.size + unresolvedSlots.size) {
            "Native move hypothesis slots must be unique"
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
