package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * A Pokemon on its last Perish Song count faints at the end of this turn unless it leaves the field. Every action that
 * keeps it in costs it, as a knockout against this side ([LocalDecisionTuning.knockoutMaterialScore]); a switch and a
 * move that switches its user out (U-turn's kind, read from the move data) do not. Nothing is charged when it cannot
 * leave (no switch on offer: trapped, or the last one standing), since every action then ends the same.
 *
 * The count was read nowhere in the ranking: a Gengar on its last count ranked Perish Song and Substitute (20) above
 * switching out (12.3), and only the quick decider's no-damage switch got it out (2026-10-13, G-924).
 */
internal object LocalPerishCount {
    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning,
    ): Map<String, Double> {
        val doomed = context.state.pokemon.filter {
            it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 && lastCount(it)
        }.mapNotNull { it.activeSlot }.toSet()
        if (doomed.isEmpty()) return emptyMap()
        // A slot with no switch on offer cannot be saved.
        val savable = doomed.filterTo(hashSetOf()) { slot ->
            candidates.any { candidate -> parts(candidate).any { it.kind == BattleActionKind.SWITCH && it.actorSlot == slot } }
        }
        if (savable.isEmpty()) return emptyMap()
        return candidates.mapNotNull { candidate ->
            val stays = parts(candidate).count { it.actorSlot in savable && keepsUserIn(it) }
            if (stays == 0) null else candidate.actionId to -stays * tuning.knockoutMaterialScore
        }.toMap()
    }

    /**
     * Whether [pokemon]'s Perish Song count ends this turn: the game's observer keeps it as `perishsong:<count>`
     * (Showdown's `-start|perish1`).
     */
    fun lastCount(pokemon: BattlePokemonStateView): Boolean = pokemon.knownVolatileEffectIds.any {
        it.substringBefore(':').lowercase() == PERISH_SONG && it.substringAfter(':', "") == "1"
    }

    private fun keepsUserIn(part: BattleActionCandidate): Boolean = part.kind == BattleActionKind.USE_MOVE &&
        part.moveDetails?.effects?.effects.orEmpty().none { it.kind == BattleMoveEffectKind.SWITCH_USER }

    private fun parts(candidate: BattleActionCandidate): List<BattleActionCandidate> =
        if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)

    private const val PERISH_SONG = "perishsong"
}
