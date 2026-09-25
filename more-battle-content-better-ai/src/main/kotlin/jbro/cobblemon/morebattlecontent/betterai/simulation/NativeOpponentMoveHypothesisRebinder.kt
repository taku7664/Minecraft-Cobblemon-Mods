package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView

internal data class NativeOpponentMoveRebindPlan(
    val definition: NativeBattleDefinition,
    val catalog: BattlePublicActionCatalogView,
    val rebindings: List<NativeMoveSetRebinding>,
)

/**
 * Revises only synthetic opponent hypotheses that public evidence has invalidated.
 *
 * Logical four-slot inference remains the authority. Native sets compact the concrete slots, so a
 * newly confirmed move can either replace one concrete slot or fill a former group-only gap.
 */
internal object NativeOpponentMoveHypothesisRebinder {
    fun plan(
        definition: NativeBattleDefinition,
        previousCatalog: BattlePublicActionCatalogView,
        currentCatalog: BattlePublicActionCatalogView,
        currentPublicPokemonIds: Set<UUID>,
        revealedMoveIdsByPokemon: Map<UUID, Set<String>>? = null,
    ): NativeOpponentMoveRebindPlan {
        val definitionIds = definition.p2Team.mapTo(linkedSetOf()) { UUID.fromString(it.uuid) }
        val scopedIds = currentPublicPokemonIds.intersect(definitionIds)
        val previousInferences = previousCatalog.opponentMoveInferences.associateBy { it.battlePokemonId }
        val currentInferences = currentCatalog.opponentMoveInferences.associateBy { it.battlePokemonId }
        val targetInferences = linkedMapOf<UUID, BattleOpponentMoveInferenceView>()
        val rebindings = mutableListOf<NativeMoveSetRebinding>()
        val replacementMoves = linkedMapOf<UUID, List<String>>()

        scopedIds.sortedBy(UUID::toString).forEach { pokemonId ->
            val previous = previousInferences[pokemonId] ?: return@forEach
            val current = currentInferences[pokemonId] ?: return@forEach
            requireComplete(previous)
            requireComplete(current)
            val target = if (revealedMoveIdsByPokemon == null) {
                current
            } else {
                publicRevealTarget(previous, current, revealedMoveIdsByPokemon[pokemonId].orEmpty())
            }
            val set = definition.p2Team.single { UUID.fromString(it.uuid) == pokemonId }
            val expected = concreteMoveIds(previous)
            val existing = set.moves.map(::nativeId)
            require(existing == expected) {
                "Native definition move set disagrees with its logical inference for $pokemonId"
            }
            val replacement = concreteMoveIds(target)
            require(replacement.isNotEmpty()) { "A native opponent hypothesis must retain an executable move" }
            targetInferences[pokemonId] = target
            if (replacement != existing) {
                rebindings += NativeMoveSetRebinding(pokemonId.toString(), existing, replacement)
                replacementMoves[pokemonId] = replacement
            }
        }

        val updatedDefinition = if (replacementMoves.isEmpty()) definition else definition.copy(
            p2Team = definition.p2Team.map { set ->
                val pokemonId = UUID.fromString(set.uuid)
                replacementMoves[pokemonId]?.let { set.copy(moves = it) } ?: set
            },
        )
        return NativeOpponentMoveRebindPlan(
            definition = updatedDefinition,
            catalog = mergeCatalog(
                previousCatalog,
                currentCatalog,
                scopedIds,
                targetInferences,
            ),
            rebindings = rebindings,
        )
    }

    private fun publicRevealTarget(
        previous: BattleOpponentMoveInferenceView,
        current: BattleOpponentMoveInferenceView,
        revealedMoveIds: Set<String>,
    ): BattleOpponentMoveInferenceView {
        val revealed = revealedMoveIds.mapTo(linkedSetOf(), ::nativeId)
        if (revealed.isEmpty()) return previous
        val currentBySlot = current.slots.associateBy(BattleOpponentMoveSlotView::slot)
        val slots = previous.slots.map { prior ->
            val candidate = currentBySlot.getValue(prior.slot)
            if (candidate.knowledge == BattleOpponentMoveKnowledge.CONFIRMED &&
                candidate.source == BattleOpponentMoveSource.PUBLIC_REVEAL &&
                candidate.moveId?.let(::nativeId) in revealed
            ) {
                candidate
            } else {
                prior
            }
        }
        val concreteIds = slots.mapNotNull(BattleOpponentMoveSlotView::moveId).map(::nativeId)
        require(concreteIds.distinct().size == concreteIds.size) {
            "A public reveal cannot create duplicate native move slots"
        }
        return BattleOpponentMoveInferenceView(previous.battlePokemonId, slots)
    }

    private fun concreteMoveIds(inference: BattleOpponentMoveInferenceView): List<String> =
        inference.slots.asSequence()
            .filter { it.knowledge != BattleOpponentMoveKnowledge.GUESS }
            .map { nativeId(requireNotNull(it.moveId)) }
            .toList()

    private fun requireComplete(inference: BattleOpponentMoveInferenceView) {
        require(inference.slots.size == BattleOpponentMoveSlotView.MAX_MOVE_SLOTS &&
            inference.slots.map(BattleOpponentMoveSlotView::slot) ==
            (0 until BattleOpponentMoveSlotView.MAX_MOVE_SLOTS).toList()) {
            "Native move-set rebinding requires a complete four-slot inference"
        }
    }

    private fun mergeCatalog(
        previous: BattlePublicActionCatalogView,
        current: BattlePublicActionCatalogView,
        scopedIds: Set<UUID>,
        targetInferences: Map<UUID, BattleOpponentMoveInferenceView>,
    ): BattlePublicActionCatalogView {
        fun <T> replaceScoped(
            prior: List<T>,
            latest: List<T>,
            id: (T) -> UUID,
        ): List<T> = prior.filterNot { id(it) in scopedIds } + latest.filter { id(it) in scopedIds }

        val mergedInferences = previous.opponentMoveInferences
            .filterNot { it.battlePokemonId in targetInferences }
            .plus(targetInferences.values)
        return BattlePublicActionCatalogView(
            entries = replaceScoped(previous.entries, current.entries) { it.battlePokemonId },
            originalEntries = replaceScoped(previous.originalEntries, current.originalEntries) { it.battlePokemonId },
            candidatePools = replaceScoped(previous.candidatePools, current.candidatePools) { it.battlePokemonId },
            opponentMoveInferences = mergedInferences,
        )
    }

    private fun nativeId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
        .also { require(it.isNotBlank()) { "Native Showdown move ID cannot be blank" } }
}
