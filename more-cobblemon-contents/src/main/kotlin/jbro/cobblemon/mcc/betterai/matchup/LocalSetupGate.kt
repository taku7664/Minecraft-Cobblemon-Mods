package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStatusMoveCategories

/**
 * The path a stat-raising status move has to pass before the AI may pick it.
 *
 * All of these, read from [MatchupScores]:
 * - the user is an ace: its [AceScore] reaches [ACE_PASS], and through the setup, not in spite of it;
 * - after setting up it wins against every opponent on the field, the setup turns paid for ([DUEL_PASS]);
 * - the opponents on the field do not knock it out this turn ([SURVIVAL_PASS]);
 * - no opponent it has seen can stop it once boosted ([STOPPER_PASS]): Encore, Haze, a forced switch,
 *   Unaware and the rest of [AntiAceToolKind], except simply beating it, which the ace and duel checks own.
 */
internal object LocalSetupGate {
    data class Verdict(val passes: Boolean, val failures: List<String>)

    /** Whether [candidate] passes: every stat-raising move in it, each slot of a doubles turn included. */
    fun passes(
        candidate: BattleActionCandidate,
        verdictOf: (BattleActionCandidate) -> Verdict?,
    ): Boolean {
        val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
        return parts.all { verdictOf(it)?.passes != false }
    }

    /** Whether [candidate] or one of its slots is a stat-raising status move. */
    fun raisesOwnStats(candidate: BattleActionCandidate): Boolean {
        val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
        return parts.any(::isSetupMove)
    }

    /**
     * A status move that only raises its user's stats. An attack with a self-boost (Flame Charge,
     * Trailblaze) is an attack first; the gate is not about it.
     */
    private fun isSetupMove(candidate: BattleActionCandidate): Boolean {
        if (candidate.kind != BattleActionKind.USE_MOVE) return false
        val details = candidate.moveDetails ?: return false
        return details.damageCategory == BattleMoveDamageCategory.STATUS && BattleStatusMoveCategories.isPureSelfSetup(details)
    }

    /** Null when [candidate] is not a stat-raising status move of an active ally. */
    fun evaluate(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        scores: MatchupScores,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
    ): Verdict? {
        if (!isSetupMove(candidate)) return null
        val state = context.state
        val user = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        val ace = scores.aces[user.battlePokemonId] ?: return Verdict(false, listOf("no_ace_score"))
        val failures = mutableListOf<String>()
        if (ace.score < ACE_PASS || ace.boostedSweep <= ace.naturalSweep) failures += "ace"
        val onField = state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        for (opponent in onField) {
            val boosted = ace.boostedByOpponent[opponent.battlePokemonId] ?: 0.0
            if (boosted < DUEL_PASS) failures += "duel:${opponent.speciesId}"
        }
        // Every attacker on the field may pick the user this turn.
        val survival = onField.fold(1.0) { chance, opponent ->
            chance * (scores.pokemon(user.battlePokemonId, opponent.battlePokemonId)?.opponentMove?.survivalByUses?.getOrNull(1) ?: 1.0)
        }
        if (survival < SURVIVAL_PASS) failures += "knockout"
        val seen = state.pokemon.filter { it.side == BattleSide.OPPONENT && !it.fainted && it.hpFraction > 0.0 }
        for (opponent in seen) {
            // Scored against this user as the ace, which is not always the side's best one.
            val anti = scores.antiAces[opponent.battlePokemonId]?.takeIf { it.aceId == user.battlePokemonId }
                ?: LocalAntiAceScoreCalculator.score(context, opponent, ace, user, scores, cache)
                ?: continue
            val stopper = anti.tools.filter { it.kind != AntiAceToolKind.OUTLASTS }.maxByOrNull { it.value } ?: continue
            val value = stopper.value + if (anti.oneTimeSurvival != null) AntiAceScore.ONE_TIME_SURVIVAL_BONUS else 0.0
            if (value >= STOPPER_PASS) failures += "stopper:${opponent.speciesId}:${stopper.kind.name.lowercase()}"
        }
        return Verdict(failures.isEmpty(), failures)
    }

    const val REASON = "setup_gate"
    const val ACE_PASS = 0.5
    const val DUEL_PASS = 0.5
    const val SURVIVAL_PASS = 0.8
    const val STOPPER_PASS = 0.5
}
