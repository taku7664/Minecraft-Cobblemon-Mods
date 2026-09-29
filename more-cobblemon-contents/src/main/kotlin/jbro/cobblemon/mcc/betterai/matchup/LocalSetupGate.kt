package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStatusMoveCategories
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * The path a stat-raising status move has to pass before the AI may pick it.
 *
 * All of these, read from [MatchupScores]:
 * - the user is a sweeper: its [SweepScore] reaches [SWEEP_PASS], and through the setup, not in spite of it;
 * - after setting up it wins against every opponent on the field, the setup turns paid for ([DUEL_PASS]);
 * - the opponents on the field do not knock it out this turn ([SURVIVAL_PASS]);
 * - no opponent on the field can stop it once boosted. A benched stopper has to come in first, which costs
 *   its side a turn and a hit; the next decision judges it once it is in front of the boosted user.
 *   - Encore, a forced switch, Unaware and Destiny Bond stop it whenever an opponent has them
 *     ([ALWAYS_STOPS]). Against the last two the sweeper should not stay at all: see [retreatReasons].
 *   - Haze, Clear Smog, Perish Song and Taunt do not: the boost is traded for the opponent's own turn, or
 *     is already up ([NEVER_STOPS]).
 *   - Any other tool stops it at [STOPPER_PASS] or more. Simply beating it is the sweeper and duel checks'.
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
        val sweeper = scores.sweeps[user.battlePokemonId] ?: return Verdict(false, listOf("no_sweep_score"))
        val failures = mutableListOf<String>()
        if (sweeper.score < SWEEP_PASS || sweeper.boostedSweep <= sweeper.naturalSweep) failures += "sweep"
        val onField = state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        for (opponent in onField) {
            val boosted = sweeper.boostedByOpponent[opponent.battlePokemonId] ?: 0.0
            if (boosted < DUEL_PASS) failures += "duel:${opponent.speciesId}"
        }
        // Every attacker on the field may pick the user this turn, if it gets to act at all.
        val survival = onField.fold(1.0) { chance, opponent ->
            val hitSurvival = scores.pokemon(user.battlePokemonId, opponent.battlePokemonId)?.opponentMove?.survivalByUses?.getOrNull(1) ?: 1.0
            chance * (1.0 - actingChance(opponent) * (1.0 - hitSurvival))
        }
        if (survival < SURVIVAL_PASS) failures += "knockout"
        for ((opponent, anti) in fieldStoppers(user, sweeper, context, scores, cache)) {
            anti.tools.firstOrNull { it.kind in ALWAYS_STOPS }?.let {
                failures += "stopper:${opponent.speciesId}:${it.kind.name.lowercase()}"
                continue
            }
            val stopper = anti.tools.filter { it.kind != StopToolKind.OUTLASTS && it.kind !in NEVER_STOPS }
                .maxByOrNull { it.value } ?: continue
            val value = stopper.value + if (anti.oneTimeSurvival != null) StopScore.ONE_TIME_SURVIVAL_BONUS else 0.0
            if (value >= STOPPER_PASS) failures += "stopper:${opponent.speciesId}:${stopper.kind.name.lowercase()}"
        }
        return Verdict(failures.isEmpty(), failures)
    }

    /**
     * How likely [opponent] is to act this turn: a Pokemon that must recharge does not, a sleeping one wakes
     * about half the time, a frozen one thaws one time in five. The window a sleeping or frozen opponent opens is
     * the classic moment to set up.
     */
    private fun actingChance(opponent: BattlePokemonStateView): Double {
        if (RECHARGE in opponent.canonicalKnownVolatileEffectIds) return 0.0
        return when (opponent.statusId?.let(PublicIds::canonical)) {
            "slp", "sleep", "asleep" -> SLEEPING_ACTS
            "frz", "freeze", "frozen" -> FROZEN_ACTS
            else -> 1.0
        }
    }

    /**
     * Why [user], a sweeper, should leave the field: an opponent there ignores its boosts (Unaware) or takes it
     * down with it (Destiny Bond). Empty when it is no sweeper or nothing there does either.
     */
    fun retreatReasons(
        user: BattlePokemonStateView,
        context: BattleDecisionContext,
        scores: MatchupScores,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
    ): List<String> {
        val sweeper = scores.sweeps[user.battlePokemonId]?.takeIf { it.score >= SWEEP_PASS && it.setupMoveId != null } ?: return emptyList()
        return fieldStoppers(user, sweeper, context, scores, cache).flatMap { (opponent, anti) ->
            anti.tools.filter { it.kind in RETREATS_FROM }.map { "${opponent.speciesId}:${it.kind.name.lowercase()}" }
        }.distinct()
    }

    /** The stopping reading of every opponent on the field against [user] as the sweeper. */
    private fun fieldStoppers(
        user: BattlePokemonStateView,
        sweeper: SweepScore,
        context: BattleDecisionContext,
        scores: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): List<Pair<BattlePokemonStateView, StopScore>> = context.state.pokemon.filter {
        it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
    }.mapNotNull { opponent ->
        // Scored against this user as the sweeper, which is not always the side's best one.
        val anti = scores.stops[opponent.battlePokemonId]?.takeIf { it.sweeperId == user.battlePokemonId }
            ?: LocalStopScoreCalculator.score(context, opponent, sweeper, user, scores, cache)
        anti?.let { opponent to it }
    }

    private const val RECHARGE = "mustrecharge"
    private const val SLEEPING_ACTS = 0.5
    private const val FROZEN_ACTS = 0.2
    const val REASON = "setup_gate"
    const val SWEEP_PASS = 0.5
    const val DUEL_PASS = 0.5
    const val SURVIVAL_PASS = 0.8
    const val STOPPER_PASS = 0.5
    val ALWAYS_STOPS = setOf(StopToolKind.ENCORE, StopToolKind.FORCES_SWITCH,
        StopToolKind.IGNORES_BOOSTS, StopToolKind.DESTINY_BOND)
    val NEVER_STOPS = setOf(StopToolKind.RESETS_BOOSTS, StopToolKind.PERISH_SONG, StopToolKind.TAUNT)
    val RETREATS_FROM = setOf(StopToolKind.IGNORES_BOOSTS, StopToolKind.DESTINY_BOND)
}
