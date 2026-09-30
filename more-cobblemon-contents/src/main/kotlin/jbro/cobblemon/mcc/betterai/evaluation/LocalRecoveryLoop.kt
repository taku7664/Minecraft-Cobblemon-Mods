package jbro.cobblemon.mcc.betterai.evaluation

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * A recovery loop that is losing: the turns in a row, counting back from the last one, on which [failedStreak]'s
 * Pokemon used a self-heal and still ended the turn with less HP than it started with. The heal did not keep up
 * with the hits, so using it again only spends another turn behind; each such turn costs the next heal
 * [LocalDecisionTuning.recoveryLoopPenalty] more.
 *
 * The root heuristic charges the heal on offer; the search charges every heal of its own lines the same way, the
 * count carried along the line ([next]), so a line of heals that keep losing costs more with each one.
 */
internal object LocalRecoveryLoop {
    /** The allies [action] has use a self-heal, as they stand in [state]. */
    fun healers(action: BattleActionCandidate, state: BattleStateView): List<UUID> {
        val parts = if (action.kind == BattleActionKind.COMPOSITE) action.componentActions else listOf(action)
        return parts.filter { part ->
            part.kind == BattleActionKind.USE_MOVE && part.moveDetails?.effects?.effects.orEmpty().any {
                it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER
            }
        }.mapNotNull { part ->
            state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && !it.fainted }?.battlePokemonId
        }
    }

    /** The streak of [pokemonId] on a line: the line's own count once it has one, the battle's before. */
    fun streak(pokemonId: UUID, lineStreaks: Map<UUID, Int>, context: BattleDecisionContext): Int =
        lineStreaks[pokemonId] ?: failedStreak(pokemonId, context)

    /**
     * The line's streaks after a turn: each ally that healed goes up by one when it still ended lower and back to
     * zero when it did not; an ally that did anything else, or left the field, starts over.
     */
    fun next(
        lineStreaks: Map<UUID, Int>,
        healers: List<UUID>,
        before: BattleStateView,
        after: BattleStateView,
        context: BattleDecisionContext,
    ): Map<UUID, Int> {
        val allies = before.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot != null }
        return allies.associate { ally ->
            val now = after.pokemon.firstOrNull { it.battlePokemonId == ally.battlePokemonId }
            val stayed = now != null && now.activeSlot != null && !now.fainted
            val lost = now != null && now.hpFraction < ally.hpFraction
            ally.battlePokemonId to when {
                !stayed || ally.battlePokemonId !in healers -> 0
                lost -> streak(ally.battlePokemonId, lineStreaks, context) + 1
                else -> 0
            }
        }
    }

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
