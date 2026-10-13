package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Perish Song, in the root's units (singles): everyone on the field without a count faints three turns on unless it
 * leaves, so the song is worth whatever cannot leave.
 *
 * - The opponent in front cannot leave (no switch among its public actions: a trapping ability, a trapping move, a
 *   binding move, or no one left behind it, as [PublicFutureActionFactory] reads them) and the user can: a knockout
 *   ([LocalDecisionTuning.knockoutMaterialScore]), at the chance the user lives through the opponent's three hits before
 *   it switches out on the last count.
 * - The opponent can leave and the user can too: nothing is bought; it only hands the opponent a free switch.
 * - The user cannot leave: it faints with the opponent, a trade (nothing), or alone if the opponent leaves (a knockout
 *   against this side).
 * An opponent that already has a count is not given a new one, nor the user.
 *
 * It took the generic status value (20), so a Mega Gengar's Shadow Tag in front of a Chansey with no attack rated
 * Protect (36) over the song (23.9), and it switched out (2026-10-13, G-925). Perish Song declares nothing in the move
 * data (Showdown sets it in a callback), so the move is found by its own id, as the turn projector finds it.
 */
internal object LocalPerishTrapCredit {
    fun prices(candidate: BattleActionCandidate): Boolean =
        candidate.kind == BattleActionKind.USE_MOVE && candidate.moveId?.let(PublicIds::canonical) == PERISH_SONG

    /** The song's value for [candidate] read on [context]'s board, or null outside singles or with no one in front. */
    fun value(candidate: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores, tuning: LocalDecisionTuning): Double? {
        val state = context.state
        if (state.format != BattleFormat.SINGLE) return null
        val user = active(context, BattleSide.ALLY) ?: return null
        val foe = active(context, BattleSide.OPPONENT) ?: return null
        val foeCounts = !perishing(foe)
        val userCounts = !perishing(user)
        val foeLeaves = canSwitch(state, foe, context)
        val userLeaves = context.candidates.any { it.kind == BattleActionKind.SWITCH && it.actorSlot == candidate.actorSlot }
        val knockout = tuning.knockoutMaterialScore
        // The user stays in front for the opponent's hits on the song's turn and the two after it.
        val survives = scores.pokemon(user.battlePokemonId, foe.battlePokemonId)?.opponentMove?.survivalByUses?.getOrNull(STAY_HITS) ?: 1.0
        return when {
            !foeCounts -> 0.0
            userLeaves -> if (foeLeaves) 0.0 else survives * knockout
            !userCounts -> if (foeLeaves) 0.0 else survives * knockout
            foeLeaves -> -knockout
            else -> 0.0
        }
    }

    /**
     * Whether [foe] has a switch among its public actions. Those list only the bench seen so far, so an opponent with
     * Pokemon left that have not been seen is given one stand-in on its bench: what is read is the trap, not who comes.
     */
    private fun canSwitch(state: BattleStateView, foe: BattlePokemonStateView, context: BattleDecisionContext): Boolean {
        val left = state.remainingPokemonBySide.getValue(BattleSide.OPPONENT)
        val seenBench = state.pokemon.count { it.side == BattleSide.OPPONENT && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }
        val active = state.pokemon.count { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        if (left - active <= 0) return false
        val board = if (seenBench > 0) state else state.copyState(pokemon = state.pokemon + BattlePokemonStateView(
            UUID.nameUUIDFromBytes("perish-trap:unseen-bench".toByteArray()), BattleSide.OPPONENT, null, foe.speciesId, foe.formId,
            foe.level, 1.0, null, emptyMap(), emptySet(), null, null, false, foe.knownTypeIds))
        return PublicFutureActionFactory.slotActions(board, BattleSide.OPPONENT, context.publicActionCatalog)
            .any { it.kind == BattleActionKind.SWITCH && it.actorSlot == foe.activeSlot }
    }

    private fun perishing(pokemon: BattlePokemonStateView): Boolean =
        pokemon.knownVolatileEffectIds.any { it.substringBefore(':').lowercase() == PERISH_SONG }

    private fun active(context: BattleDecisionContext, side: BattleSide): BattlePokemonStateView? =
        context.state.pokemon.firstOrNull { it.side == side && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }

    private const val PERISH_SONG = "perishsong"
    /** The opponent's hits the user takes before it leaves on the last count. */
    private const val STAY_HITS = 3
}
