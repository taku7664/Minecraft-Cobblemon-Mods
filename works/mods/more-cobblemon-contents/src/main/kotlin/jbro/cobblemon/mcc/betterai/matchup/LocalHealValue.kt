package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * What a self-heal is worth, in score points (one HP bar is 100, a Pokemon 200), singles:
 *
 * `(standing healed - standing now) * 100 + (knockout now - knockout healed) * heals first * standing healed * 100`
 *
 * - the standing ([LocalKnockoutWorth.standings]): what the user still does against every opposing Pokemon left, its
 *   fights' share at its HP and its next hit; read again on the board with the HP the heal restores. A heal raises the
 *   user's matchups whether it was losing or not: the healthier user is a pivot point for the rest of the battle.
 * - this turn: the foe's hit that would knock the user out at its HP now and not at the healed one, at the chance the heal
 *   comes first (after the foe's hit, the heal changes nothing this turn).
 *
 * What the turn gives up (the attack) is the attack's own value, compared in the ranking. Read as the HP restored alone
 * (a 50% heal worth 50 against a knockout's 200), a +4 Defence Corviknight at 23% Brave Birded instead of Roosting and
 * fell to the recoil and the next foe (dev server, 2026-10-20); the knockout's floor of a quarter that made up for it had
 * no reading behind it (user, 2026-10-21).
 */
internal object LocalHealValue {
    /** The user's pure self-heals (status moves restoring a share of its HP), by their value; empty where none applies. */
    fun values(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        scores: MatchupScores,
    ): Map<String, Double> {
        val state = context.state
        val heals = candidates.filter { candidate ->
            candidate.kind == BattleActionKind.USE_MOVE && candidate.mechanic == null &&
                candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS &&
                candidate.facts?.selfHealingFractionRange != null &&
                jbro.cobblemon.mcc.betterai.mechanics.LocalMoveCode.userSleepTurns(candidate.moveId) == null
        }
        if (heals.isEmpty()) return emptyMap()
        val foe = state.pokemon.singleOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return emptyMap()
        val now = LocalKnockoutWorth.standings(state, scores)
        return heals.mapNotNull { heal ->
            val user = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == heal.actorSlot && !it.fainted } ?: return@mapNotNull null
            val range = heal.facts?.selfHealingFractionRange ?: return@mapNotNull null
            val healedHp = minOf(1.0, user.hpFraction + (range.minimum + range.maximum) / 2.0)
            if (healedHp <= user.hpFraction) return@mapNotNull heal.actionId to 0.0
            val board = state.derive(pokemon = state.pokemon.map { if (it.battlePokemonId == user.battlePokemonId) it.copyState(hpFraction = healedHp) else it })
            val healedScores = LocalMatchupScoreCalculator.calculate(context.copy(state = board))
            val healed = LocalKnockoutWorth.standings(board, healedScores)
            val standingNow = now[user.battlePokemonId] ?: return@mapNotNull null
            val standingHealed = healed[user.battlePokemonId] ?: return@mapNotNull null
            val pairNow = scores.pokemon(user.battlePokemonId, foe.battlePokemonId)
            val pairHealed = healedScores.pokemon(user.battlePokemonId, foe.battlePokemonId)
            val knockoutNow = pairNow?.opponentMove?.knockoutChanceWithin(1) ?: 0.0
            val knockoutHealed = pairHealed?.opponentMove?.knockoutChanceWithin(1) ?: 0.0
            val healsFirst = LocalPublicTurnOrder.actsFirstProbability(state, BattleSide.ALLY, heal.actorSlot, heal,
                pairNow?.opponentMove?.priority ?: 0) ?: 0.5
            jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record("heal:${heal.actionId}", mapOf("hp" to user.hpFraction, "healedHp" to healedHp, "standingNow" to standingNow, "standingHealed" to standingHealed, "knockoutNow" to knockoutNow, "knockoutHealed" to knockoutHealed, "healsFirst" to healsFirst))
            heal.actionId to ((standingHealed - standingNow) * BOARD_TO_SCORE +
                (knockoutNow - knockoutHealed).coerceAtLeast(0.0) * healsFirst * standingHealed * BOARD_TO_SCORE)
        }.toMap()
    }

    /** One board unit in ranking points ([jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.boardToScore]). */
    private val BOARD_TO_SCORE get() = jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.CURRENT.boardToScore
}
