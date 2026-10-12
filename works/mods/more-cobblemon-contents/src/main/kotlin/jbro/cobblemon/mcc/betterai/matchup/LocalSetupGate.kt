package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStatusMoveCategories
import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.betterai.mechanics.copyState

/**
 * A stat-raising status move: what it is worth ([score]) and what rules it out ([evaluate]).
 *
 * Its worth is whether the user still wins the one-on-one after boosting, times a sweep bonus of at least 1 for the
 * opponents still to come that the boosted user finds easy. It is ruled out only when, read from [MatchupScores],
 * an opponent on the field can stop it once boosted. A benched stopper has to come in first, which costs
 *   its side a turn and a hit; the next decision judges it once it is in front of the boosted user.
 *   - What each opponent move does to the boosted sweeper is played out in the engine ([LocalEngineStopProbe]),
 *     from the deciding tier's view of the opponent's moves; a move's data alone decides only declared effects.
 *   - A lock (Encore's kind) or a forced switch stops it whenever an opponent has one ([ALWAYS_STOPS]). Against
 *     a sweeper's boosts not counting or both fainting together, it should not stay at all: see [retreatReasons].
 *   - A reset (Haze's kind), Perish Song's count and a block of the setup move (Taunt's kind) do not: the boost is
 *     traded for the opponent's own turn, or is already up ([NEVER_STOPS]).
 *   - Any other tool stops it at [STOPPER_PASS] or more. Simply beating it is [score]'s: the win chance falls.
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

    /** The moves [opponent] is known to hold: revealed in battle, or read as confirmed by the deciding tier. */
    private fun knownMoves(opponent: BattlePokemonStateView, context: BattleDecisionContext): Set<String> =
        opponent.knownMoveIds.mapTo(hashSetOf(), PublicIds::canonical) +
            context.publicActionCatalog.inferredMovesForPokemon(opponent.battlePokemonId)?.slots.orEmpty()
                .filter { it.knowledge == jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveKnowledge.CONFIRMED }
                .mapNotNull { it.moveId?.let(PublicIds::canonical) }

    /** Null when [candidate] is not a stat-raising status move of an active ally. */
    fun evaluate(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        scores: MatchupScores,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        /** The deciding tier; null leaves the engine out and reads only the moves' declared effects. */
        tier: BattleTrainerTier? = null,
    ): Verdict? {
        if (!isSetupMove(candidate)) return null
        val state = context.state
        val user = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        // Whether it still wins after boosting is [score]'s; here only what stops the boost once it is up. The stoppers
        // are read against the user as the sweeper, so without a sweep reading there is nothing to read them against.
        val sweeper = scores.sweeps[user.battlePokemonId] ?: return Verdict(true, emptyList())
        val failures = mutableListOf<String>()
        for ((opponent, read) in fieldStoppers(user, sweeper, context, scores, cache, tier, LocalEngineStopProbe.Opening.Move(candidate))) {
            // Only a move the opponent is known to hold stops it: revealed, or confirmed by what the tier reads. A move
            // assumed from usage or the learnset is a guess, and a guess does not rule a setup out (2026-10-12).
            val known = knownMoves(opponent, context)
            val anti = read.copy(tools = read.tools.filter { tool -> tool.moveId == null || PublicIds.canonical(tool.moveId) in known })
            // Played out, a lock or a forced switch counts only where it stopped the sweeper in the engine.
            anti.tools.firstOrNull { it.kind in ALWAYS_STOPS && (!it.playedOut || it.value > 0.0) }?.let {
                failures += "stopper:${opponent.speciesId}:${it.kind.name.lowercase()}"
                continue
            }
            val stopper = anti.tools.filter { it.kind != StopToolKind.OUTLASTS && it.kind !in NEVER_STOPS }
                .maxByOrNull { it.value } ?: continue
            // A tool played out in the engine already met the survival item or ability there.
            val value = stopper.value + if (anti.oneTimeSurvival != null && !stopper.playedOut) StopScore.ONE_TIME_SURVIVAL_BONUS else 0.0
            if (value >= STOPPER_PASS) failures += "stopper:${opponent.speciesId}:${stopper.kind.name.lowercase()}"
        }
        return Verdict(failures.isEmpty(), failures)
    }

    /** What [score] read: the one-on-one win chance after boosting, the sweep bonus, and the value in the root's units. */
    data class Reading(val win: Double, val bonus: Double, val value: Double)

    /**
     * A stat raise's value in the root's units: the chance the user still wins the one-on-one after raising its stats
     * in front of the Pokemon it faces (the weakest such chance in doubles), the setup turn's hit paid, at
     * [knockoutMaterialScore] for a sure win, times [sweepBonus]. No turn is searched; the exchange is the pair table's.
     * Whether the boost raises the win chance is not asked: one that already wins without it still wins with it, which
     * is when a free turn to boost is safest. Null when [candidate] is not a stat-raising status move of an active ally
     * or the pair cannot be read.
     */
    fun score(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        scores: MatchupScores,
        knockoutMaterialScore: Double,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
    ): Reading? {
        if (!isSetupMove(candidate)) return null
        val user = context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        val moveId = PublicIds.canonical(candidate.moveId ?: return null)
        val stages = LocalMatchupScoreCalculator.setupMoves(context, user)[moveId] ?: return null
        val facing = context.state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        if (facing.isEmpty()) return null
        val win = facing.minOf { foe ->
            // A foe that will not act this turn (recharging, asleep, frozen) lands its setup-turn hit only when it does.
            val hit = LocalMatchupScoreCalculator.boostedWin(context, user, foe, stages, scores, cache, setupTurn = true) ?: return null
            val free = LocalMatchupScoreCalculator.boostedWin(context, user, foe, stages, scores, cache, setupTurn = false) ?: return null
            actingChance(foe) * hit + (1.0 - actingChance(foe)) * free
        }
        val bonus = sweepBonus(context, user, facing, stages, scores, cache)
        return Reading(win, bonus, win * knockoutMaterialScore * bonus)
    }

    /**
     * At least 1, and [SWEEP_BONUS_STEP] more for each opponent still to come that the boosted user finds easy: a
     * benched one already seen by the boosted user's win chance against it from its current HP, one known only from
     * the team preview by the lead choice's edge ([jbro.cobblemon.mcc.betterai.policy.LocalLeadChoice.matchup], one
     * doubling of damage in the user's favour counting as fully easy). Without a preview only the seen ones count.
     */
    fun sweepBonus(
        context: BattleDecisionContext,
        user: BattlePokemonStateView,
        facing: List<BattlePokemonStateView>,
        stages: Map<String, Int>,
        scores: MatchupScores,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
    ): Double {
        val seen = context.state.pokemon.filter { it.side == BattleSide.OPPONENT }
        val benched = seen.filter { foe ->
            foe.activeSlot == null && !foe.fainted && foe.hpFraction > 0.0 && facing.none { it.battlePokemonId == foe.battlePokemonId }
        }
        var easy = benched.sumOf { foe ->
            LocalMatchupScoreCalculator.boostedWin(context, user, foe, stages, scores, cache, setupTurn = false) ?: 0.0
        }
        val preview = context.opponentTeamPreview
        val ownTeam = context.exactOwnTeam
        if (preview != null && ownTeam != null) {
            val unseen = preview.pokemon.filter { slot ->
                seen.none { jbro.cobblemon.mcc.betterai.simulation.NativeInBattleFormes.samePublicIdentity(it, slot) }
            }
            val coming = (preview.selectionSize - seen.size).coerceIn(0, unseen.size)
            if (coming > 0) {
                val lead = jbro.cobblemon.mcc.internal.ai.BattleLeadChoiceContext(context.state.format,
                    context.state.pokemon.filter { it.side == BattleSide.ALLY }.map { it.copyState(activeSlot = null) },
                    context.publicActionCatalog, ownTeam, preview)
                val benchedUser = user.copyState(activeSlot = null)
                easy += coming * unseen.map { foe ->
                    jbro.cobblemon.mcc.betterai.policy.LocalLeadChoice.matchup(benchedUser, foe, lead, detailed = true).coerceIn(0.0, 1.0)
                }.average()
            }
        }
        return 1.0 + SWEEP_BONUS_STEP * easy
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
        tier: BattleTrainerTier? = null,
    ): List<String> {
        val sweeper = scores.sweeps[user.battlePokemonId]?.takeIf { it.score >= SWEEP_PASS && it.setupMoveId != null } ?: return emptyList()
        // Only a sweeper on the field can be played out against the opponent there; on the bench the declared effects decide.
        val opening = if (user.activeSlot != null) LocalEngineStopProbe.Opening.Attack else null
        return fieldStoppers(user, sweeper, context, scores, cache, tier, opening).flatMap { (opponent, anti) ->
            val known = knownMoves(opponent, context)
            anti.tools.filter { it.kind in RETREATS_FROM && (!it.playedOut || it.value > 0.0) &&
                (it.moveId == null || PublicIds.canonical(it.moveId) in known) }.map { "${opponent.speciesId}:${it.kind.name.lowercase()}" }
        }.distinct()
    }

    /** The stopping reading of every opponent on the field against [user] as the sweeper. */
    private fun fieldStoppers(
        user: BattlePokemonStateView,
        sweeper: SweepScore,
        context: BattleDecisionContext,
        scores: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
        tier: BattleTrainerTier?,
        opening: LocalEngineStopProbe.Opening?,
    ): List<Pair<BattlePokemonStateView, StopScore>> = context.state.pokemon.filter {
        it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
    }.mapNotNull { opponent ->
        // Scored against this user as the sweeper, which is not always the side's best one.
        val anti = scores.stops[opponent.battlePokemonId]?.takeIf { it.sweeperId == user.battlePokemonId }
            ?: LocalStopScoreCalculator.score(context, opponent, sweeper, user, scores, cache)
        // What the opponent's moves do that their data does not declare, played out in the engine.
        val played = if (tier == null || opening == null || context.state.format != BattleFormat.SINGLE) emptyList()
            else LocalEngineStopProbe.tools(context, tier, user, opponent, opening, scores)
        anti?.let { opponent to if (played.isEmpty()) it else it.copy(tools = (it.tools + played).sortedByDescending { tool -> tool.value },
            score = maxOf(it.score, played.maxOf { tool -> tool.value })) }
    }

    private const val RECHARGE = "mustrecharge"
    private const val SLEEPING_ACTS = 0.5
    private const val FROZEN_ACTS = 0.2
    const val REASON = "setup_gate"
    const val SWEEP_PASS = 0.5
    /** The sweep bonus each fully easy opponent still to come adds over 1. */
    const val SWEEP_BONUS_STEP = 0.15
    const val STOPPER_PASS = 0.5
    val ALWAYS_STOPS = setOf(StopToolKind.ENCORE, StopToolKind.FORCES_SWITCH,
        StopToolKind.IGNORES_BOOSTS, StopToolKind.DESTINY_BOND)
    val NEVER_STOPS = setOf(StopToolKind.RESETS_BOOSTS, StopToolKind.PERISH_SONG, StopToolKind.TAUNT)
    val RETREATS_FROM = setOf(StopToolKind.IGNORES_BOOSTS, StopToolKind.DESTINY_BOND)
}
