package jbro.cobblemon.mcc.betterai.evaluation

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * A recovery loop that is losing: the turns in a row, counting back from the last one, on which [failedStreak]'s
 * Pokemon used a self-heal and still ended the turn with less HP than it started with. The heal did not keep up
 * with the hits, so using it again only spends another turn behind; each such turn costs the next heal
 * [LocalDecisionTuning.recoveryLoopPenalty] more.
 */
internal object LocalRecoveryLoop {
    fun failedStreak(pokemonId: UUID, context: BattleDecisionContext): Int {
        val state = context.state
        val events = state.observedEvents
        val heals = context.publicActionCatalog.forPokemon(pokemonId).filter { option ->
            option.details.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER }
        }.map { PublicIds.canonical(it.moveId) }.toSet()
        if (heals.isEmpty()) return 0
        // Only the turns since it last came in: a switch breaks the loop.
        val entered = events.lastOrNull { it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId == pokemonId }?.turn ?: -1
        var streak = 0
        var turn = state.turn - 1
        while (turn > entered) {
            val own = events.filter { it.turn == turn && it.actorPokemonId == pokemonId }
            val healed = own.any { it.kind == BattleObservedEventKind.MOVE_USED && it.publicValueId?.let(PublicIds::canonical) in heals }
            val net = own.filter { it.kind == BattleObservedEventKind.HP_CHANGED }.sumOf { it.hpFractionDelta ?: 0.0 }
            if (!healed || net >= 0.0) break
            streak++
            turn--
        }
        return streak
    }
}
