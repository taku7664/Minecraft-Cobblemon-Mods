package jbro.cobblemon.morebattlecontent.betterai.state

import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveGroup
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentMoveSource
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategories
import jbro.cobblemon.morebattlecontent.api.ai.BattleStatusMoveCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier

/**
 * Names the opponent status moves a tier believes in, so they can actually be simulated.
 *
 * A guessed status slot has no move, and a slot without a move never becomes a branch - so before
 * this, no tier ever imagined an opponent using Protect, Spore or Recover until it had seen one.
 * The name comes from public format usage within the public learnset, never from the hidden set:
 *
 * - Introductory: no status moves are imagined.
 * - Standard: one signature move only, when usage makes it near certain (Amoonguss' Spore).
 * - Advanced: the actual status count is known; each slot takes the most used plausible move.
 * - Boss: the actual category of each slot is known; the move is the most used one of that category.
 *
 * A bound move is only EXPECTED, so it keeps the reduced trust every expected reply gets, and a
 * revealed move later replaces it in the same logical slot.
 */
internal object LocalStatusMoveBinder {
    /** Standard imagines a status move only when it is this common for the species. */
    const val STANDARD_SIGNATURE_RATE = 0.70

    /** Advanced and Boss fill an uncategorized slot only with a move at least this common. */
    const val PLAUSIBLE_RATE = 0.20

    fun bindCatalog(
        state: BattleStateView,
        catalog: BattlePublicActionCatalogView,
        tier: BattleTrainerTier,
        usage: LocalMoveUsageLookup?,
    ): BattlePublicActionCatalogView {
        if (tier == BattleTrainerTier.INTRODUCTORY || usage == null || catalog.opponentMoveInferences.isEmpty()) {
            return catalog
        }
        var changed = false
        val bound = catalog.opponentMoveInferences.map { inference ->
            val pokemon = state.pokemon.firstOrNull { it.battlePokemonId == inference.battlePokemonId }
                ?: return@map inference
            val pool = catalog.candidatePools.singleOrNull {
                it.battlePokemonId == pokemon.battlePokemonId && it.speciesId == pokemon.speciesId &&
                    it.formId == pokemon.formId
            } ?: return@map inference
            bind(inference, pokemon.speciesId, pokemon.formId, pool.moveDetails, tier, usage)
                .also { if (it !== inference) changed = true }
        }
        return if (changed) catalog.withOpponentMoveInferences(bound) else catalog
    }

    fun bind(
        inference: BattleOpponentMoveInferenceView,
        speciesId: String,
        formId: String?,
        learnset: Map<String, BattleMoveCandidateView>,
        tier: BattleTrainerTier,
        usage: LocalMoveUsageLookup?,
    ): BattleOpponentMoveInferenceView {
        if (tier == BattleTrainerTier.INTRODUCTORY || usage == null) return inference
        val guesses = inference.slots.filter {
            it.knowledge == BattleOpponentMoveKnowledge.GUESS && it.group == BattleOpponentMoveGroup.STATUS_OTHER
        }
        if (guesses.isEmpty()) return inference

        val taken = inference.slots.mapNotNullTo(hashSetOf()) { slot -> slot.moveId?.let(::canonical) }
        val candidates = learnset.entries.mapNotNull { (moveId, details) ->
            if (details.currentPp <= 0 || canonical(moveId) in taken) return@mapNotNull null
            val category = BattleStatusMoveCategories.classify(moveId, details) ?: return@mapNotNull null
            val rate = usage.rate(speciesId, formId, moveId)?.takeIf { it > 0.0 } ?: return@mapNotNull null
            Candidate(moveId, details, category, rate)
        }.sortedWith(compareByDescending<Candidate> { it.rate }.thenBy { canonical(it.moveId) })
        if (candidates.isEmpty()) return inference

        val used = hashSetOf<String>()
        var standardBound = false
        val slots = inference.slots.map { slot ->
            if (slot !in guesses) return@map slot
            val choice = candidates.firstOrNull { candidate ->
                canonical(candidate.moveId) !in used && when {
                    slot.statusCategory != null -> candidate.category == slot.statusCategory
                    tier == BattleTrainerTier.STANDARD -> !standardBound && candidate.rate >= STANDARD_SIGNATURE_RATE
                    else -> candidate.rate >= PLAUSIBLE_RATE
                }
            } ?: return@map slot
            used += canonical(choice.moveId)
            if (tier == BattleTrainerTier.STANDARD) standardBound = true
            BattleOpponentMoveSlotView(
                slot = slot.slot,
                moveId = choice.moveId,
                group = BattleOpponentMoveGroup.STATUS_OTHER,
                knowledge = BattleOpponentMoveKnowledge.EXPECTED,
                source = BattleOpponentMoveSource.LEARNSET_EXPECTATION,
                details = choice.details,
                statusCategory = choice.category,
            )
        }
        return if (used.isEmpty()) inference else BattleOpponentMoveInferenceView(inference.battlePokemonId, slots)
    }

    private data class Candidate(
        val moveId: String,
        val details: BattleMoveCandidateView,
        val category: BattleStatusMoveCategory,
        val rate: Double,
    )

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}
