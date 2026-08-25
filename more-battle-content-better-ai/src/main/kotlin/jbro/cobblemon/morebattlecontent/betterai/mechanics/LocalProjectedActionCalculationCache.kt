package jbro.cobblemon.morebattlecontent.betterai.mechanics

import java.util.IdentityHashMap
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot

/** Reuses an exact projected state's public tactical calculation within one search. */
internal class LocalProjectedActionCalculationCache {
    private val byState = IdentityHashMap<BattleStateView, MutableMap<ActionKey, BattleDecisionContext>>()

    var calculationsPerformed: Int = 0
        private set

    fun getOrCalculate(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
        calculation: () -> BattleDecisionContext,
    ): BattleDecisionContext {
        val key = ActionKey(
            side = side,
            actionId = action.actionId,
            kind = action.kind,
            actorSlot = action.actorSlot,
            moveSlot = action.moveSlot,
            moveId = action.moveId,
            targets = action.targets,
            switchPokemonId = action.switchPokemonId,
            mechanicId = action.mechanic?.mechanicId,
        )
        val stateEntries = byState.getOrPut(state) { HashMap() }
        return stateEntries.getOrPut(key) {
            calculationsPerformed++
            calculation()
        }
    }

    private data class ActionKey(
        val side: BattleSide,
        val actionId: String,
        val kind: BattleActionKind,
        val actorSlot: Int?,
        val moveSlot: Int?,
        val moveId: String?,
        val targets: List<BattleTargetSlot>,
        val switchPokemonId: UUID?,
        val mechanicId: String?,
    )
}
