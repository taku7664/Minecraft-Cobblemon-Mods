package jbro.cobblemon.morebattlecontent.betterai.outcome

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext

/**
 * Removes actions that public battle mechanics prove cannot affect the opponent.
 *
 * This is deliberately not a tactical scorer. Both decision owners receive the same
 * mechanical boundary, while the remaining choice still belongs to that brain.
 */
internal object PublicMechanicalActionGate {
    fun removeNullifiedWhenPossible(context: BattleDecisionContext): BattleDecisionContext {
        val viable = context.candidates.filterNot { candidate ->
            candidate.kind in setOf(BattleActionKind.USE_MOVE, BattleActionKind.COMPOSITE) &&
                PublicActionOutcomeProjector.project(candidate, context).publiclyNullified
        }
        if (viable.isEmpty() || viable.size == context.candidates.size) return context
        return BattleDecisionContext(
            requestId = context.requestId,
            state = context.state,
            candidates = viable,
            deadlineEpochMillis = context.deadlineEpochMillis,
            memory = context.memory,
            publicActionCatalog = context.publicActionCatalog,
        )
    }
}
