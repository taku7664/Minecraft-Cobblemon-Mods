package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * The chance [BattleActionCandidate]'s user gets its move off this turn: every foe that moves before it (priority, its
 * user's ability such as Prankster, Speed, Quick Claw) may knock it out first. Each foe's likeliest knockout of the user
 * from the move table, at the chance the foe moves at all and aims at the user (one in as many of this side's Pokemon in
 * front), two hits together counted ([LocalDynamaxGuard.knockout]).
 *
 * A status move's whole worth is in being used: hazards (261), a screen (225), Glare (162) and Trick Room (61) were
 * chosen by Pokemon a faster foe knocked out first (league run, 2026-10-19).
 */
internal object LocalActingChance {
    fun of(candidate: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores): Double {
        val state = context.state
        val user = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return 1.0
        val allies = state.pokemon.count { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            .coerceAtLeast(1)
        val hits = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            .mapNotNull { foe ->
                val hit = scores.moves(foe.battlePokemonId, user.battlePokemonId).maxByOrNull { it.knockoutChanceWithin(1) }
                    ?: return@mapNotNull null
                val first = LocalPublicTurnOrder.actsFirstProbability(state, BattleSide.ALLY, user.activeSlot, candidate, hit.priority) ?: 0.5
                // A spread hit lands on the user whatever it aims at; a single one at one of this side's Pokemon in front.
                val pattern = (context.publicActionCatalog.forPokemon(foe.battlePokemonId).firstOrNull { PublicIds.canonical(it.moveId) == hit.moveId }?.details
                    ?: context.publicActionCatalog.inferredMovesForPokemon(foe.battlePokemonId)?.slots.orEmpty()
                        .firstOrNull { it.moveId?.let(PublicIds::canonical) == hit.moveId }?.details)?.targetPattern
                val aim = if (pattern == BattleMoveTargetPattern.ALL_OPPONENTS || pattern == BattleMoveTargetPattern.ALL_ADJACENT) 1.0 else 1.0 / allies
                LocalDynamaxGuard.IncomingHit(hit, LocalKnockoutWorth.actsThisTurn(foe) * (1.0 - first), aim)
            }
        if (hits.isEmpty()) return 1.0
        return 1.0 - LocalDynamaxGuard.knockout(hits, user.hpFraction).coerceIn(0.0, 1.0)
    }
}
