package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * A replacement asked for mid-turn (a pivot's, an Eject Button's) while the partner's move of this turn is still to come:
 * a spread move of the partner's hits whoever comes in, so each replacement pays what that move takes from it. The move
 * is the partner's part of the turn's chosen pair, kept by the brain's session; it counts only while the partner has not
 * used a move this turn. Read without it, a Flygon's U-turn brought in a Duraludon that its partner Garchomp's
 * Earthquake knocked out in the same turn, an immune Dragonite on the bench (hand-played Raihan, doubles, 2026-10-20).
 */
internal object LocalPendingPartnerHit {
    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        /** The turn's chosen pair, if it was chosen this turn. */
        chosen: BattleActionCandidate?,
        tuning: LocalDecisionTuning,
    ): Map<String, Double> {
        chosen ?: return emptyMap()
        val state = context.state
        val out = linkedMapOf<String, Double>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            val entry = parts.singleOrNull { it.kind == BattleActionKind.SWITCH && it.switchPokemonId != null } ?: continue
            if (parts.any { it.kind == BattleActionKind.USE_MOVE }) continue
            val pending = chosen.componentActions.firstOrNull {
                it.kind == BattleActionKind.USE_MOVE && it.actorSlot != entry.actorSlot && it.mechanic == null &&
                    it.moveDetails?.damageCategory.let { category -> category != null && category != BattleMoveDamageCategory.STATUS } &&
                    // Only a move that lands on its user's partners too (Earthquake, Surf).
                    it.moveDetails?.targetPattern in ALLY_HITTING
            } ?: continue
            val partner = state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.activeSlot == pending.actorSlot && !it.fainted && it.hpFraction > 0.0
            } ?: continue
            // Moved already this turn: nothing is still to come.
            if (state.observedEvents.any { it.turn == state.turn && it.kind == BattleObservedEventKind.MOVE_USED && it.actorPokemonId == partner.battlePokemonId }) continue
            val projected = LocalSwitchStateProjector.project(state, BattleSide.ALLY, entry)
            val incoming = projected.pokemon.firstOrNull { it.battlePokemonId == entry.switchPokemonId } ?: continue
            // The partner's move as the ranking charges its collateral, on the Pokemon coming in.
            val cost = jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer.publicAllyCollateral(
                pending, context.copy(state = projected), tuning, targetsOverride = listOf(incoming))
            if (cost > 0.0) out[candidate.actionId] = -cost
        }
        return out
    }

    private val ALLY_HITTING = setOf(
        jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern.ALL_ADJACENT,
        jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern.ALL_ACTIVE,
    )
}
