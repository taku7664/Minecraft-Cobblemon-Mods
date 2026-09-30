package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.LocalStallingProtectionRules
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * What a Protect is worth in doubles: the hits it turns away this turn, in the root's units.
 *
 * The root heuristic prices Protect as a generic status move (a flat 20), so an attack with a knockout chance always
 * outranked it, even with both opponents about to knock the user out; the search saw the difference and could not
 * make it up. Here each opposing Pokemon's predicted actions ([LocalOpponentIntentPredictor], read from the same
 * matchup table) say how likely it aims at the user and with what: the knockout chance they add up to, at
 * [LocalDecisionTuning.knockoutMaterialScore], and the HP they are expected to take, times the chance the Protect
 * works after the ones before it.
 */
internal object LocalProtectCredit {
    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        scores: MatchupScores,
        intents: List<OpponentIntent>,
        tuning: LocalDecisionTuning,
    ): Map<String, Double> {
        if (tuning.doublesProtectCredit <= 0.0 || context.state.format != BattleFormat.DOUBLE || intents.isEmpty()) return emptyMap()
        val state = context.state
        val out = linkedMapOf<String, Double>()
        val bySlot = HashMap<Int, Double>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            val credit = parts.filter { part ->
                part.kind == BattleActionKind.USE_MOVE && part.moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.PROTECT_USER }
            }.sumOf { part ->
                val slot = part.actorSlot ?: return@sumOf 0.0
                bySlot.getOrPut(slot) {
                    val user = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == slot && !it.fainted && it.hpFraction > 0.0 }
                        ?: return@getOrPut 0.0
                    var standing = 1.0
                    var taken = 0.0
                    for (intent in intents) {
                        var knockout = 0.0
                        for (option in intent.options) {
                            if (option.kind != IntentKind.ATTACK || option.moveId == null) continue
                            // Aimed at the user, or a spread move that hits it.
                            if (option.targetId != null && option.targetId != user.battlePokemonId) continue
                            val hit = scores.moves(intent.pokemonId, user.battlePokemonId).firstOrNull { it.moveId == option.moveId } ?: continue
                            knockout += option.probability * hit.knockoutChanceWithin(1)
                            taken += option.probability * hit.accuracy * (hit.minimumDamageFraction + hit.maximumDamageFraction) / 2.0
                        }
                        standing *= 1.0 - knockout.coerceIn(0.0, 1.0)
                    }
                    val success = LocalStallingProtectionRules.nextSuccessProbability(
                        LocalStallingProtectionRules.consecutiveSuccessfulUses(state, BattleSide.ALLY, slot))
                    val threat = 1.0 - standing
                    success * tuning.doublesProtectCredit *
                        (threat * tuning.knockoutMaterialScore + tuning.board(minOf(user.hpFraction, taken)))
                }
            }
            if (credit > 0.0) out[candidate.actionId] = credit
        }
        return out
    }
}
