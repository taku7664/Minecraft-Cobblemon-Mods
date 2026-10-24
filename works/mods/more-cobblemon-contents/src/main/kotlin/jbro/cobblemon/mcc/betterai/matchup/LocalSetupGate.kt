package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleStatusMoveCategories
import jbro.cobblemon.mcc.internal.ai.PublicIds
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind

/**
 * A stat-raising status move: what it is worth ([score]) and what rules it out ([evaluate]).
 *
 * Its worth is the actual HP/KO pressure and move-order change against the remaining opponents. It is ruled out only when, read from [MatchupScores],
 * an opponent on the field can stop it once boosted. A benched stopper has to come in first, which costs
 *   its side a turn and a hit; the next decision judges it once it is in front of the boosted user.
 *   - What each opponent move does to the boosted sweeper is read from its data ([LocalStopScoreCalculator]); no
 *     turn is played out (user decision 2026-10-19: no event-branch search).
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

    /** Whether [candidate], a stat raise, still raises a stat of [user] once its ability (Contrary, Simple) reshapes it. */
    fun raisesFor(candidate: BattleActionCandidate, state: BattleStateView, user: BattlePokemonStateView): Boolean {
        if (!isSetupMove(candidate)) return false
        val stages = candidate.moveDetails!!.effects!!.effects.fold(mutableMapOf<String, Int>()) { stages, effect ->
            effect.statStages.forEach { (stat, delta) -> stages.merge(stat, delta, Int::plus) }
            stages
        }
        return raisesFor(stages, state, user)
    }

    private fun raisesFor(stages: Map<String, Int>, state: BattleStateView, user: BattlePokemonStateView): Boolean =
        LocalStatStageChange.reshape(state, user, user.battlePokemonId, stages).values.any { it > 0 }

    /**
     * A status move that only raises its user's stats. An attack with a self-boost (Flame Charge,
     * Trailblaze) is an attack first; the gate is not about it.
     */
    /** A Ghost's Curse: half its HP to curse the target, no raise (the move's other half is for every other type). */
    private fun ghostCurse(candidate: BattleActionCandidate, user: BattlePokemonStateView): Boolean =
        user.knownTypeIds.any { PublicIds.canonical(it) == "ghost" } &&
            candidate.moveDetails?.effects?.effects.orEmpty().any { it.valueId == jbro.cobblemon.mcc.internal.ai.BattleDeclarativeMoveEffects.GHOST_CURSE }

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
        if (ghostCurse(candidate, user)) return null
        // Whether it still wins after boosting is [score]'s; here only what stops the boost once it is up. The stoppers
        // are read against the user as the sweeper, so without a sweep reading there is nothing to read them against.
        val sweeper = scores.sweeps[user.battlePokemonId] ?: return Verdict(true, emptyList())
        val failures = mutableListOf<String>()
        for ((opponent, read) in fieldStoppers(user, sweeper, context, scores, cache)) {
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

    /** All components use score units: actual marginal HP/KO pressure, action order, HP paid and usable effect chance. */
    data class Reading(val pressure: Double, val speed: Double, val hpCost: Double, val survival: Double,
        val holds: Double, val effectChance: Double, val value: Double,
        val hpRatio: Double, val hpSafety: Double)

    fun score(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        scores: MatchupScores,
        knockoutMaterialScore: Double,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        copyRisk: Double = 0.0,
        foeSwitchChances: Map<java.util.UUID, Double> = emptyMap(),
    ): Reading? {
        val user = context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        return marginal(candidate, context, user, scores, knockoutMaterialScore, cache, copyRisk, foeSwitchChances)
    }

    /**
     * The raise's own change against each remaining foe, averaged in the same HP/KO units as an attack.
     * Both stage boards precede the hit. No pass threshold, whole-knockout reward, or team-size multiplier.
     * The root separately applies the chance of falling before acting; here only falling after raising loses the gain.
     * The opponent predictor uses the same formula and also pays its before-action survival here.
     */
    fun marginal(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        user: BattlePokemonStateView,
        scores: MatchupScores,
        knockoutMaterialScore: Double,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        copyRisk: Double = 0.0,
        foeSwitchChances: Map<java.util.UUID, Double> = emptyMap(),
        includesBeforeActionRisk: Boolean = false,
    ): Reading? {
        if (!isSetupMove(candidate) || ghostCurse(candidate, user)) return null
        val moveId = PublicIds.canonical(candidate.moveId ?: return null)
        val stages = LocalMatchupScoreCalculator.setupMoves(context, user)[moveId] ?: return null
        val cost = LocalMatchupScoreCalculator.setupHpCosts(context, user)[moveId] ?: 0.0
        // User-approved low-HP caution curve for unmodelled item/spread error, separate from calculated KO risk.
        val hpRatio = user.hpFraction.coerceIn(0.0, 1.0)
        val missingHp = 1.0 - hpRatio
        val missingHpSquared = missingHp * missingHp
        val hpSafety = 1.0 - missingHpSquared * missingHpSquared
        if (cost > 0.0 && user.hpFraction <= cost) return Reading(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, hpRatio, hpSafety)
        val foes = context.state.pokemon.filter { it.side != user.side && !it.fainted && it.hpFraction > 0.0 }
        if (foes.isEmpty()) return null
        val facing = foes.filter { it.activeSlot != null }
        val tuning = jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.CURRENT.let {
            it.copy(leafKnockoutPressure = knockoutMaterialScore / it.boardToScore)
        }
        val perspective = if (user.side == BattleSide.ALLY) 1.0 else -1.0
        val changes = foes.mapNotNull { foe ->
            val position = LocalMatchupPosition.face(context, user, foe, cache) ?: return@mapNotNull null
            val raised = LocalMatchupScoreCalculator.setupState(position.state, user.battlePokemonId, stages, 1)
            jbro.cobblemon.mcc.betterai.evaluation.LocalStatStageMarginalEvaluator.evaluate(
                position.state, raised, position, cache, tuning)
        }
        if (changes.isEmpty()) return null
        val pressure = changes.map { it.pressureBoardDelta * tuning.boardToScore * perspective }.average()
        val speed = changes.map { it.speedBoardDelta * tuning.boardToScore * perspective }.average()
        val afterCost = LocalMatchupScoreCalculator.setupState(context.state, user.battlePokemonId, stages, 1, cost)
        val raisedContext = context.copy(state = afterCost)
        val survival = facing.fold(1.0) { survives, foe ->
            val hit = LocalMatchupScoreCalculator.moveMatchups(raisedContext, foe.battlePokemonId, user.battlePokemonId, cache).firstOrNull() ?: return@fold survives
            val first = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder.actsFirstProbability(
                afterCost, user.side, candidate, foe.side, hit.action) ?: 0.5
            val acts = actingChance(foe, context.state) *
                (1.0 - (foeSwitchChances[foe.battlePokemonId] ?: 0.0).coerceIn(0.0, 1.0))
            val plainFall = hit.score.knockoutChanceWithin(1)
            val fall = if (user.side == BattleSide.ALLY) LocalFoeDynamaxThreat.bestHit(
                raisedContext, raisedContext, afterCost.pokemon.first { it.battlePokemonId == user.battlePokemonId }, foe, cache)
                ?.score?.knockoutChanceWithin(1)?.let { maxOf(it, plainFall) } ?: plainFall else plainFall
            survives * (1.0 - acts * fall * if (includesBeforeActionRisk) 1.0 else first)
        }.coerceIn(0.0, 1.0)
        val holds = resetHolds(context, user, facing, stages, scores, cache) * (1.0 - copyRisk.coerceIn(0.0, 1.0))
        val effectChance = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy.probability(candidate, context, user.side) *
            candidate.moveDetails?.effects?.effects.orEmpty().filter { it.kind == BattleMoveEffectKind.STAT_STAGE }
                .minOfOrNull { (it.probability ?: 1.0).coerceIn(0.0, 1.0) }.let { it ?: 1.0 }
        val hpCost = cost * tuning.boardToScore
        // Only positive setup gain is discounted; HP paid and negative outcomes keep their full cost.
        val gain = (pressure + speed) * survival * holds
        val guardedGain = if (gain > 0.0) gain * hpSafety else gain
        val value = (guardedGain - hpCost) * effectChance
        jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record("setup:${candidate.actionId}", mapOf(
            "pressure" to pressure, "speed" to speed, "hpCost" to hpCost, "survival" to survival, "holds" to holds,
            "hpRatio" to hpRatio, "hpSafety" to hpSafety, "gain" to gain, "guardedGain" to guardedGain,
            "effectChance" to effectChance, "opponents" to changes.size.toDouble(), "value" to value))
        return Reading(pressure, speed, hpCost, survival, holds, effectChance, value, hpRatio, hpSafety)
    }

    /**
     * The chance the raise is still there when it is used: a foe in front with a stage reset it has shown, or this tier
     * read from its set (Haze, Clear Smog: [BattleMoveEffectKind.CLEARS_STAT_STAGES]), clears it on its next turn unless
     * the raised user knocks it out first.
     * ```
     * holds = prod over such foes ( f x (1 - acts) + (1 - f) x (1 - acts x (1 - knockout x first)) )
     * ```
     * f is the chance the raise goes before the foe moves this turn (it answers it at once), knockout and first the raised
     * user's next hit on it and the chance that lands before the foe moves next turn, acts the foe's chance to move at all. Read as a trade of turns (the reset's own turn spent), a Kingambit Swords Danced twice
     * into a Toxapex that had shown Haze, was burned by its Scald in between and never used a boost (hand-played Piers,
     * 2026-10-20).
     */
    private fun resetHolds(
        context: BattleDecisionContext,
        user: BattlePokemonStateView,
        facing: List<BattlePokemonStateView>,
        stages: Map<String, Int>,
        scores: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): Double = facing.fold(1.0) { holds, foe ->
        if (!resets(foe, user, context)) return@fold holds
        val position = LocalMatchupPosition.face(context, user, foe, cache) ?: return@fold holds
        val raised = position.copy(state = LocalMatchupScoreCalculator.setupState(position.state, user.battlePokemonId, stages, 1))
        val pair = LocalMatchupScoreCalculator.pairMatchup(raised, user.battlePokemonId, foe.battlePokemonId,
            MatchupSpeedField.CURRENT, cache) ?: return@fold holds
        val knockout = pair.subjectMove?.knockoutChanceWithin(1) ?: 0.0
        val acts = LocalKnockoutWorth.actsThisTurn(foe, context.state)
        // Raised first, the foe answers on this very turn; raised last, the raised hit comes first next turn only.
        val raisedFirst = scores.pokemon(user.battlePokemonId, foe.battlePokemonId)?.subjectMovesFirstProbability ?: 0.5
        val keeps = raisedFirst * (1.0 - acts) + (1.0 - raisedFirst) * (1.0 - acts * (1.0 - knockout * pair.subjectMovesFirstProbability))
        holds * keeps.coerceIn(0.0, 1.0)
    }

    /**
     * Whether [foe] holds a stage reset this side knows of: shown, or read from its set by this tier (no guess). A move
     * that forces [user] out (Whirlwind, Roar) resets its stages as Haze does, unless it cannot be forced out (Dynamax,
     * Suction Cups, Ingrain). Read for Haze alone, a Tyranitar's Dragon Dance kept +240 in front of a Skarmory that had
     * Whirlwinded its partner out (hand-played hazards concept, 2026-10-20).
     */
    private fun resets(foe: BattlePokemonStateView, user: BattlePokemonStateView, context: BattleDecisionContext): Boolean {
        // Held in, read from the ability's and the moves' code (onDragOut), no name listed.
        val held = jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.active(user) ||
            jbro.cobblemon.mcc.internal.ai.BattleShowdownHeldEffects.resistsForcedSwitch(
                jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState.effectiveKnownAbility(context.state, user)) ||
            user.knownVolatileEffectIds.any { PublicIds.canonical(it) in jbro.cobblemon.mcc.betterai.mechanics.LocalMoveCode.anchoringVolatiles }
        fun clears(details: jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView?) =
            details?.effects?.effects.orEmpty().any { effect ->
                effect.kind == BattleMoveEffectKind.CLEARS_STAT_STAGES ||
                    !held && effect.kind == BattleMoveEffectKind.SWITCH_TARGET && effect.target == jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget.SELECTED_TARGET
            }
        val shown = context.publicActionCatalog.forPokemon(foe.battlePokemonId).any { clears(it.details) }
        return shown || context.publicActionCatalog.inferredMovesForPokemon(foe.battlePokemonId)?.slots.orEmpty().any {
            it.source == jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveSource.DIFFICULTY_SET_READ && clears(it.details)
        }
    }

    /**
     * How likely [opponent] is to act this turn: a Pokemon that must recharge does not, a sleeping one wakes
     * about half the time, a frozen one thaws one time in five. The window a sleeping or frozen opponent opens is
     * the classic moment to set up.
     */
    internal fun actingChance(opponent: BattlePokemonStateView, state: jbro.cobblemon.mcc.internal.ai.BattleStateView? = null): Double {
        if (state != null && jbro.cobblemon.mcc.betterai.mechanics.LocalTruant.loafs(state, opponent)) return 0.0
        if (RECHARGE in opponent.canonicalKnownVolatileEffectIds) return 0.0
        // A sleep whose start was seen: its counter and Sleep Talk (LocalSleepClock); the flat half otherwise.
        state?.let { jbro.cobblemon.mcc.betterai.mechanics.LocalSleepClock.acts(it, opponent) }?.let { return it }
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
        return fieldStoppers(user, sweeper, context, scores, cache).flatMap { (opponent, anti) ->
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
    const val STOPPER_PASS = 0.5
    val ALWAYS_STOPS = setOf(StopToolKind.ENCORE, StopToolKind.FORCES_SWITCH,
        StopToolKind.IGNORES_BOOSTS, StopToolKind.DESTINY_BOND)
    val NEVER_STOPS = setOf(StopToolKind.RESETS_BOOSTS, StopToolKind.PERISH_SONG, StopToolKind.TAUNT)
    val RETREATS_FROM = setOf(StopToolKind.IGNORES_BOOSTS, StopToolKind.DESTINY_BOND)
}
