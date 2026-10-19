package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveRequirementKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * A move that works only while its target has an attack still to come (Sucker Punch, Thunderclap): the chance it does.
 *
 * Return: the move's own result when the target attacks. Risk: nothing at all when it uses a status move (Swords Dance,
 * King's Shield), switches or has already moved.
 *
 * ```
 * attacks = min(predicted, observed)
 * predicted = the share of attacks in the target's predicted actions ([LocalOpponentIntentPredictor])
 * observed  = (attacks used + 1) / (moves used + 2), over the target's moves since it came in (none used: 1)
 * ```
 * The observed share is what the target has done in front of this side: a Swords Dance seen twice reads a quarter,
 * where the prediction alone still read an attack and a Bisharp Sucker Punched into three of them (dev server,
 * 2026-10-20). Each lowers the chance, so the lower one is kept.
 */
internal object LocalPendingAttackChance {
    fun requiresPendingAttack(candidate: BattleActionCandidate): Boolean =
        candidate.moveDetails?.effects?.requirements.orEmpty().any { it.kind == BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE }

    /** The target [candidate] is aimed at: its chosen one, else the one opponent in front. */
    fun target(candidate: BattleActionCandidate, context: BattleDecisionContext): BattlePokemonStateView? {
        candidate.targets.singleOrNull()?.let { slot ->
            return context.state.pokemon.firstOrNull { it.side == slot.side && it.activeSlot == slot.slot && !it.fainted }
        }
        return context.state.pokemon.singleOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
    }

    fun attacks(target: BattlePokemonStateView, context: BattleDecisionContext, intents: List<OpponentIntent>): Double {
        val predicted = intents.firstOrNull { it.pokemonId == target.battlePokemonId }?.options
            ?.takeIf { it.isNotEmpty() }
            ?.let { options -> options.filter { it.kind == IntentKind.ATTACK }.sumOf { it.probability } / options.sumOf { it.probability }.coerceAtLeast(1e-9) }
            ?: 1.0
        val events = context.state.observedEvents
        val entered = events.filter { it.kind == BattleObservedEventKind.SWITCHED && it.actorPokemonId == target.battlePokemonId }
            .maxOfOrNull { it.sequence } ?: Long.MIN_VALUE
        val known = context.publicActionCatalog.forPokemon(target.battlePokemonId).associateBy { PublicIds.canonical(it.moveId) }
        val used = events.filter { it.kind == BattleObservedEventKind.MOVE_USED && it.actorPokemonId == target.battlePokemonId && it.sequence > entered }
            .mapNotNull { event -> event.publicValueId?.let(PublicIds::canonical) }
        if (used.isEmpty()) return predicted.coerceIn(0.0, 1.0)
        val attacked = used.count { id -> known[id]?.details?.damageCategory?.let { it != BattleMoveDamageCategory.STATUS } ?: true }
        val observed = (attacked + 1.0) / (used.size + 2.0)
        return minOf(predicted, observed).coerceIn(0.0, 1.0)
    }
}
