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
 * What a Protect is worth in doubles, in the root's units (user decision 2026-10-19: a formula, no turn played in the
 * engine).
 *
 * Return: the hits it turns away this turn, and a turn off every timed effect and Dynamax in front. Risk: the slot's
 * own action, which the ranking prices by the joint actions that attack instead; a Protect that fails after the ones
 * before it.
 *
 * ```
 * value = success x ( (1 - prod over foes (1 - acts x knockout aimed at the user)) x worth(user)
 *                     + HP the hits aimed at it take x acts x blocked share
 *                     + timed field turns + the Dynamax turns of the Pokemon in front )
 * ```
 * Each opposing Pokemon's predicted actions ([LocalOpponentIntentPredictor], read from the same matchup table) say how
 * likely it aims at the user and with what; acts is its chance to act this turn at all (asleep, frozen, paralysed);
 * worth is the user's [jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth]. A Max Move goes through a Protect for
 * a quarter of its damage (not through Max Guard), so only three quarters are blocked and its knockout only when the
 * quarter does not finish the user. A move or ability that ignores Protect (Feint, Unseen Fist) is not read.
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
        val cache = jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()
        val shouldContinue = { System.currentTimeMillis() < context.deadlineEpochMillis }
        val fieldTurns by lazy {
            jbro.cobblemon.mcc.betterai.evaluation.LocalSinglesProtectReturn.fieldTurns(context, tuning, cache, shouldContinue)
        }
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
                    val maxGuard = jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(user)
                    var standing = 1.0
                    var taken = 0.0
                    for (intent in intents) {
                        val foe = state.pokemon.firstOrNull { it.battlePokemonId == intent.pokemonId } ?: continue
                        val acts = LocalKnockoutWorth.actsThisTurn(foe)
                        // A Max Move breaks through a plain Protect for a quarter of its damage.
                        val through = if (!maxGuard && jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(foe)) MAX_MOVE_THROUGH else 0.0
                        var knockout = 0.0
                        for (option in intent.options) {
                            if (option.kind != IntentKind.ATTACK || option.moveId == null) continue
                            // Aimed at the user, or a spread move that hits it.
                            if (option.targetId != null && option.targetId != user.battlePokemonId) continue
                            val hit = scores.moves(intent.pokemonId, user.battlePokemonId).firstOrNull { it.moveId == option.moveId } ?: continue
                            val damage = hit.accuracy * (hit.minimumDamageFraction + hit.maximumDamageFraction) / 2.0
                            // Its knockout is turned away unless the quarter that gets through still finishes the user.
                            val stillFinishes = through > 0.0 && hit.minimumDamageFraction * through >= user.hpFraction
                            if (!stillFinishes) knockout += option.probability * hit.knockoutChanceWithin(1)
                            taken += acts * option.probability * damage * (1.0 - through)
                        }
                        standing *= 1.0 - (acts * knockout).coerceIn(0.0, 1.0)
                    }
                    val success = LocalStallingProtectionRules.nextSuccessProbability(
                        LocalStallingProtectionRules.consecutiveSuccessfulUses(state, BattleSide.ALLY, slot))
                    val threat = 1.0 - standing
                    // A turn off the Dynamax of each Pokemon in front, the user's own included.
                    val foes = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
                    val dynamaxTurns = foes.sumOf { foe ->
                        listOf(user, foe).filter(jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax::active).sumOf { dynamaxed ->
                            jbro.cobblemon.mcc.betterai.evaluation.LocalSinglesProtectReturn.dynamaxTurn(context, user, foe, dynamaxed, cache) /
                                (if (dynamaxed === user) foes.size else 1)
                        }
                    }
                    success * (threat * jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth.knockoutScore(user.battlePokemonId, tuning) +
                        tuning.board(minOf(user.hpFraction, taken) + fieldTurns + dynamaxTurns))
                }
            }
            if (credit > 0.0) out[candidate.actionId] = credit
        }
        return out
    }

    private const val MAX_MOVE_THROUGH = 0.25
}
