package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalRootFlinchValue
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalSituationalEvaluator
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * A certain flinch that lands first (Fake Out on its first turn out) is a free turn: the opponent does nothing, the board
 * stays as it is but for the hit, and every other action is still there next turn. It is worth its own hit (its ranking
 * value: damage, and what the hit costs, Stamina's Defence or Rough Skin's recoil included) plus the best other action,
 * at the chance the turn is free: the move goes first, flinches, hits and does not knock out, and the opponent stays in.
 * Its denied-turn credit ([LocalRootFlinchValue]) is part of that free turn and comes off.
 *
 * Read as the hit and the hit it denied, Fake Out scored 92 against a switch's 117, though the switch was still there to
 * make after it (2026-10-13, decided with the user). Only a certain flinch: a partial one leaves the turn unfree as often
 * as not. Singles; read from move data, no move is named.
 */
internal object LocalFreeTurnCredit {
    fun adjustments(
        ranked: List<LocalBattleActionRank>,
        context: BattleDecisionContext,
        expectedSwitches: Map<UUID, Map<UUID, Double>>,
        excluded: Set<String>,
        tuning: LocalDecisionTuning,
    ): Map<String, Double> {
        val state = context.state
        if (state.format != BattleFormat.SINGLE) return emptyMap()
        val actor = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return emptyMap()
        val target = state.pokemon.firstOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return emptyMap()
        val stays = (1.0 - (expectedSwitches[target.battlePokemonId]?.values?.sum() ?: 0.0)).coerceIn(0.0, 1.0)
        val adjustments = linkedMapOf<String, Double>()
        for (rank in ranked) {
            val candidate = rank.outcome.candidate
            if (candidate.kind != BattleActionKind.USE_MOVE || candidate.actionId in excluded) continue
            if (!LocalRootFlinchValue.certain(candidate)) continue
            // Below one only for what may stop it on this target: an unrevealed Inner Focus, say.
            val flinch = LocalRootFlinchValue.chance(candidate, actor, target, state)
            if (flinch <= 0.0) continue
            // Once its first turn is spent it fails, and there is no turn to free.
            if (LocalTacticalSituationalEvaluator.expiredFirstActiveTurnPenalty(candidate, context) > 0.0) continue
            val facts = candidate.facts ?: continue
            val movesFirst = facts.actsFirstProbability ?: continue
            val accuracy = LocalPublicAccuracy.probability(candidate, context, BattleSide.ALLY)
            val survives = 1.0 - (facts.standardDamageRollKoProbabilityRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0)
            val free = movesFirst * flinch.coerceAtMost(1.0) * accuracy * survives.coerceIn(0.0, 1.0) * stays
            val denied = LocalRootFlinchValue.score(candidate, context, accuracy, tuning)
            val moveId = PublicIds.canonical(candidate.moveId.orEmpty())
            // The best thing to do instead, the same move with or without a mechanic left out.
            val best = ranked.asSequence()
                .filter { other -> other.outcome.candidate.actionId !in excluded &&
                    PublicIds.canonical(other.outcome.candidate.moveId.orEmpty()).let { it.isEmpty() || it != moveId } }
                .maxOfOrNull { it.comparisonValue } ?: continue
            val credit = free * best.coerceAtLeast(0.0) - denied
            if (credit != 0.0) adjustments[candidate.actionId] = credit
        }
        return adjustments
    }
}
