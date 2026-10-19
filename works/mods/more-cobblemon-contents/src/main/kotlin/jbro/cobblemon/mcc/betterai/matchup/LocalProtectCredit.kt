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
 * falls  = 1 - prod over foes (1 - acts x knockout aimed at the user)
 * value = success x ( falls x (1 - falls x (1 - next success)) x worth(user)
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
        val foes = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
        val dynamax = jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
        // A turn off each opposing Dynamax: the field's, once for the joint action however many slots Protect.
        val foeDynamaxTurns by lazy {
            foes.filter(dynamax::active).sumOf { foe ->
                state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted }.map { user ->
                    jbro.cobblemon.mcc.betterai.evaluation.LocalSinglesProtectReturn.dynamaxTurn(context, user, foe, foe, cache)
                }.average().takeIf { !it.isNaN() } ?: 0.0
            }
        }
        val bySlot = HashMap<Pair<Int, Boolean>, SlotReading>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            val protects = parts.filter { part ->
                part.kind == BattleActionKind.USE_MOVE && part.moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.PROTECT_USER }
            }
            if (protects.isEmpty()) continue
            val readings = protects.mapNotNull { part ->
                val slot = part.actorSlot ?: return@mapNotNull null
                val user = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == slot && !it.fainted && it.hpFraction > 0.0 }
                    ?: return@mapNotNull null
                // Max Guard: already Dynamaxed, or Dynamaxing with this very move.
                val maxGuard = dynamax.active(user) || dynamax.isMechanic(part.mechanic?.mechanicId)
                bySlot.getOrPut(slot to maxGuard) { slotReading(context, scores, intents, tuning, user, slot, maxGuard, foes, cache) }
            }
            if (readings.isEmpty()) continue
            val shared = readings.maxOf { it.success } * tuning.board(fieldTurns + foeDynamaxTurns)
            // Negative too: a Protect that wastes the side's own Tailwind or Dynamax turns costs that.
            out[candidate.actionId] = readings.sumOf { it.value } + shared
        }
        return out
    }

    private class SlotReading(val value: Double, val success: Double)

    /** One protecting slot: the hits it turns away and its own Dynamax turn, at the chance it works. */
    private fun slotReading(
        context: BattleDecisionContext,
        scores: MatchupScores,
        intents: List<OpponentIntent>,
        tuning: LocalDecisionTuning,
        user: jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView,
        slot: Int,
        maxGuard: Boolean,
        foes: List<jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView>,
        cache: jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache,
    ): SlotReading {
        val state = context.state
        val hp = user.hpFraction
        var standing = 1.0
        var full = 0.0
        var through = 0.0
        for (intent in intents) {
            val foe = state.pokemon.firstOrNull { it.battlePokemonId == intent.pokemonId } ?: continue
            val acts = LocalKnockoutWorth.actsThisTurn(foe, state)
            // A Max Move breaks through a plain Protect for a quarter of its damage.
            val share = if (!maxGuard && jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(foe)) MAX_MOVE_THROUGH else 0.0
            var knockout = 0.0
            for (option in intent.options) {
                if (option.kind != IntentKind.ATTACK || option.moveId == null) continue
                // Aimed at the user, or a spread move that hits it.
                if (option.targetId != null && option.targetId != user.battlePokemonId) continue
                val hit = scores.moves(intent.pokemonId, user.battlePokemonId).firstOrNull { it.moveId == option.moveId } ?: continue
                val damage = hit.accuracy * (hit.minimumDamageFraction + hit.maximumDamageFraction) / 2.0
                // Its knockout is turned away except where the quarter that gets through still finishes the user: the
                // share of its rolls (read evenly between the least and the greatest) whose quarter reaches the HP left.
                val finishes = if (share <= 0.0) 0.0 else {
                    val low = hit.minimumDamageFraction * share
                    val high = hit.maximumDamageFraction * share
                    if (high < hp) 0.0 else if (low >= hp || high <= low) 1.0 else (high - hp) / (high - low)
                }
                knockout += option.probability * hit.knockoutChanceWithin(1) * (1.0 - finishes)
                full += acts * option.probability * damage
                through += acts * option.probability * damage * share
            }
            standing *= 1.0 - (acts * knockout).coerceIn(0.0, 1.0)
        }
        val streak = LocalStallingProtectionRules.consecutiveSuccessfulUses(state, BattleSide.ALLY, slot)
        // At the chance the user acts at all: a sleeping Tyranitar's Protect took 87 (hand-played Raihan, 2026-10-20).
        val success = LocalStallingProtectionRules.nextSuccessProbability(streak) * LocalKnockoutWorth.actsThisTurn(user, state)
        // The knockout turned away is only put off while the same hits wait next turn, when the next Protect works at
        // a third of this one's chance: it counts as saved for the share it does not fall then. Counted whole, a
        // full-HP Raichu Protected twice running at 1/3 for 108 against a Thunderbolt at 137 and fell (2026-10-19).
        val fallsNextTurn = (1.0 - standing) * (1.0 - LocalStallingProtectionRules.nextSuccessProbability(streak + 1))
        val saved = (1.0 - standing) * (1.0 - fallsNextTurn)
        // The HP kept: what the hits would leave less what the quarters that get through leave.
        val kept = minOf(hp, full) - minOf(hp, through)
        // A turn off the user's own Dynamax, read against each Pokemon in front.
        val ownDynamax = if (!jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(user)) 0.0 else foes.map { foe ->
            jbro.cobblemon.mcc.betterai.evaluation.LocalSinglesProtectReturn.dynamaxTurn(context, user, foe, user, cache)
        }.average().takeIf { !it.isNaN() } ?: 0.0
        val value = success * (saved * jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth.knockoutScore(user.battlePokemonId, tuning) +
            tuning.board(kept + ownDynamax))
        return SlotReading(value, success)
    }

    private const val MAX_MOVE_THROUGH = 0.25
}
