package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * A status move that only lays a condition on the opponent's side (an entry hazard) and cannot do anything: no opponent is
 * left to come in, or the condition is already at the most layers it stacks. It fails like any publicly failing move.
 *
 * Read from the move data alone: the side condition it declares on the target's side and the layers that condition
 * stacks to (`effectState.layers >= n`, else one). No move or condition is named. Before this a hazard with only the
 * opponent's last Pokemon left scored zero and stayed a candidate, so a position where everything else scored below
 * zero picked it.
 */
internal object LocalSideConditionFutility {
    fun futile(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean {
        if (candidate.kind != BattleActionKind.USE_MOVE) return false
        val details = candidate.moveDetails ?: return false
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) return false
        val effects = details.effects?.effects.orEmpty()
        val state = context.state
        // A condition on its own side (a screen, Tailwind, Safeguard) already up at its most layers fails too: read only on
        // the target's side, a Grimmsnarl's Reflect with Reflect up kept the generic status value and failed three turns
        // running (league run, 2026-10-19).
        val own = effects.filter { it.kind == BattleMoveEffectKind.SIDE_CONDITION && it.target == BattleMoveEffectTarget.USER_SIDE }
        if (own.isNotEmpty() && own.size == effects.size) {
            // One whose last turn has run out is gone.
            val present = state.field.sideConditions[BattleSide.ALLY].orEmpty().filter { it.remainingTurns == null || it.remainingTurns > 0 }
            return own.all { condition ->
                val id = condition.valueId?.let(PublicIds::canonical) ?: return@all false
                val stacks = present.firstOrNull { PublicIds.canonical(it.effectId) == id }?.let { it.stacks ?: 1 } ?: 0
                stacks >= (condition.amountRange?.maximum ?: 1)
            }
        }
        val conditions = effects.filter { it.kind == BattleMoveEffectKind.SIDE_CONDITION && it.target == BattleMoveEffectTarget.TARGET_SIDE }
        // Only a move whose whole effect is such conditions: anything else it does may still be worth the turn.
        if (conditions.isEmpty() || conditions.size != effects.size) return false
        val remaining = state.remainingPokemonBySide[BattleSide.OPPONENT] ?: return false
        val active = state.pokemon.count { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        if (remaining - active <= 0) return true
        val present = state.field.sideConditions[BattleSide.OPPONENT].orEmpty()
        return conditions.all { condition ->
            val id = condition.valueId?.let(PublicIds::canonical) ?: return@all false
            val stacks = present.firstOrNull { PublicIds.canonical(it.effectId) == id }?.let { it.stacks ?: 1 } ?: 0
            stacks >= (condition.amountRange?.maximum ?: 1)
        }
    }
}
