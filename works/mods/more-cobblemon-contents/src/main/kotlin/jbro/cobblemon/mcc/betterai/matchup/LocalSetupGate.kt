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
 * Its worth is whether the user still wins the one-on-one after boosting, times a sweep bonus of at least 1 for the
 * opponents still to come that the boosted user finds easy. It is ruled out only when, read from [MatchupScores],
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

    /** What [score] read: the one-on-one win chance after boosting, the sweep bonus, and the value in the root's units. */
    data class Reading(val win: Double, val bonus: Double, val value: Double)

    /**
     * A stat raise's value in the root's units: whether the user still wins the one-on-one after raising its stats
     * in front of the Pokemon it faces (the weakest such chance in doubles), the setup turn's hit paid. A win chance of
     * [WIN_PASS] or more is a win, worth [knockoutMaterialScore] times [sweepBonus]; anything less is worth nothing. The
     * question is yes or no: read as a share, a coin flip took half a knockout and, with the bonus, three times the
     * attack beside it (a Lucario's Nasty Plot at 0.5, 2026-10-13). No turn is searched; the exchange is the pair table's.
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
        /**
         * The chance a waiting opponent copies the raise on entry ([jbro.cobblemon.mcc.betterai.search.LocalEntryCopyRisk]).
         * It takes from the value, not from the win: the one-on-one is still won, the boost just changes hands.
         */
        copyRisk: Double = 0.0,
    ): Reading? {
        if (!isSetupMove(candidate)) return null
        val user = context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        if (ghostCurse(candidate, user)) return null
        val moveId = PublicIds.canonical(candidate.moveId ?: return null)
        val stages = LocalMatchupScoreCalculator.setupMoves(context, user)[moveId] ?: return null
        // A raise its user's ability turns into drops (Contrary Bulk Up, a Mega Staraptor's included) raises nothing:
        // no win is bought with it. Read as won, it took the full setup value beside the attack (2026-10-13).
        if (!raisesFor(stages, context.state, user)) return Reading(0.0, 1.0, 0.0)
        val facing = context.state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        if (facing.isEmpty()) return null
        val cost = LocalMatchupScoreCalculator.setupHpCosts(context, user)[moveId] ?: 0.0
        val win = facing.minOf { foe ->
            // A foe that will not act this turn (recharging, asleep, frozen) lands its setup-turn hit only when it does.
            val hit = LocalMatchupScoreCalculator.boostedWin(context, user, foe, stages, scores, cache, setupTurn = true, hpCost = cost) ?: return null
            val free = LocalMatchupScoreCalculator.boostedWin(context, user, foe, stages, scores, cache, setupTurn = false, hpCost = cost) ?: return null
            // A foe that may still Dynamax knocks the raised user out with its Max Move where its plain moves would
            // not; moving first only buys the raise it never uses. Moving second, [LocalActingChance] already took it.
            // The setup-turn win already holds the plain hit's survival (1 - plain); the Max Move's replaces it, (1 - max)
            // over (1 - plain), in the share where the user moves first.
            val dynamaxSurvival = LocalFoeDynamaxThreat.bestHit(context, context, user, foe, cache)?.let { max ->
                val plain = scores.moves(foe.battlePokemonId, user.battlePokemonId).maxOfOrNull { it.knockoutChanceWithin(1) } ?: 0.0
                val falls = max.score.knockoutChanceWithin(1)
                if (falls <= plain) return@let 1.0
                val first = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder.actsFirstProbability(
                    context.state, BattleSide.ALLY, candidate, BattleSide.OPPONENT, max.action) ?: 0.5
                val ratio = if (plain >= 1.0) 0.0 else ((1.0 - falls) / (1.0 - plain)).coerceIn(0.0, 1.0)
                1.0 - first + first * ratio
            } ?: 1.0
            actingChance(foe, context.state) * hit * dynamaxSurvival + (1.0 - actingChance(foe, context.state)) * free
        }
        val bonus = sweepBonus(context, user, facing, stages, scores, cache, cost)
        // A raise buys something only where it changes a fight, against any foe still to beat (in front, seen behind, or
        // in the preview and not yet seen): a loss turned into a win, a knockout in fewer uses, more of the foe's hits
        // survived, or the first move instead of the second. Whatever the stat raised, each read from the exchange with
        // and without it. Read as a win alone, a Scizor that beat what stood in front kept raising: three Swords Dances to
        // +6 before it attacked (dev server, 2026-10-20).
        fun turns(move: MoveMatchupScore?) = move?.let { m -> (1..LocalMatchupScoreCalculator.MAXIMUM_USES).firstOrNull { m.knockoutChanceWithin(it) >= 0.5 } }
            ?: (LocalMatchupScoreCalculator.MAXIMUM_USES + 1)
        fun changes(board: BattleDecisionContext, foe: BattlePokemonStateView): Boolean {
            val now = LocalMatchupScoreCalculator.boostedExchange(board, user, foe, emptyMap(), scores, cache, setupTurn = false)?.second ?: return true
            val raised = LocalMatchupScoreCalculator.boostedExchange(board, user, foe, stages, scores, cache, setupTurn = false, hpCost = cost)
                ?.second ?: return true
            // Not yet winning the one in front: whether the raise wins it is the score's own question below.
            if (now.winProbability < WIN_PASS) return raised.winProbability >= WIN_PASS || facing.any { it.battlePokemonId == foe.battlePokemonId }
            return turns(raised.subjectMove) < turns(now.subjectMove) ||
                turns(raised.opponentMove) > turns(now.opponentMove) ||
                now.subjectMovesFirstProbability < 0.5 && raised.subjectMovesFirstProbability >= 0.5
        }
        val behind = context.state.pokemon.filter { foe ->
            foe.side == BattleSide.OPPONENT && foe.activeSlot == null && !foe.fainted && foe.hpFraction > 0.0
        }
        val buys = facing.any { changes(context, it) } || behind.any { changes(context, it) } ||
            LocalSwitchRules.previewFoes(context)?.let { (board, foes) -> foes.any { changes(context.copy(state = board), it) } } == true
        val won = if (win >= WIN_PASS && buys) 1.0 else 0.0
        return Reading(win, bonus, won * knockoutMaterialScore * bonus * (1.0 - copyRisk.coerceIn(0.0, 1.0)) *
            sureKnockoutCost(context, user, facing, scores, cache) * resetHolds(context, user, facing, stages, scores, cache))
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
     * With an attack that knocks a foe in front out this turn ([WIN_PASS] or more), the raise is paid for with the hit
     * taken while it goes up: its value at the share of HP left, 1 - the foe's largest expected hit / the user's HP. A
     * Lucario at 97% chose Swords Dance (273.9) over a sure Close Combat (141.8) on a Bronzong and took an Earthquake for
     * 62% (league run, 2026-10-19; user decision, option C). A foe that hits for little (a 13% Strange Steam) leaves the
     * raise nearly whole; one that will not act this turn costs nothing, a paralysed one three quarters, and one that may still
     * Dynamax hits with its Max Move. 1 without a sure knockout.
     */
    private fun sureKnockoutCost(
        context: BattleDecisionContext,
        user: BattlePokemonStateView,
        facing: List<BattlePokemonStateView>,
        scores: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): Double {
        val sure = facing.any { foe ->
            scores.moves(user.battlePokemonId, foe.battlePokemonId).any { it.knockoutChanceWithin(1) >= WIN_PASS }
        }
        if (!sure || user.hpFraction <= 0.0) return 1.0
        fun expected(move: MoveMatchupScore) = (move.minimumDamageFraction + move.maximumDamageFraction) / 2.0 * move.accuracy
        // The foe's hit at the chance it acts at all (paralysis included), its Max Move where it may still Dynamax.
        val taken = facing.maxOf { foe ->
            LocalKnockoutWorth.actsThisTurn(foe, context.state) * maxOf(
                scores.moves(foe.battlePokemonId, user.battlePokemonId).maxOfOrNull(::expected) ?: 0.0,
                LocalFoeDynamaxThreat.hits(context, context, user, foe, cache).maxOfOrNull { expected(it.score) } ?: 0.0,
            )
        }
        return (1.0 - taken / user.hpFraction).coerceIn(0.0, 1.0)
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
        /** The share of maximum HP the raise costs (Belly Drum's half). */
        hpCost: Double = 0.0,
    ): Double {
        val seen = context.state.pokemon.filter { it.side == BattleSide.OPPONENT }
        val benched = seen.filter { foe ->
            foe.activeSlot == null && !foe.fainted && foe.hpFraction > 0.0 && facing.none { it.battlePokemonId == foe.battlePokemonId }
        }
        var easy = benched.sumOf { foe ->
            LocalMatchupScoreCalculator.boostedWin(context, user, foe, stages, scores, cache, setupTurn = false, hpCost = hpCost) ?: 0.0
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
    internal fun actingChance(opponent: BattlePokemonStateView, state: jbro.cobblemon.mcc.internal.ai.BattleStateView? = null): Double {
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
    /** The win chance after boosting that [score] reads as a win (user decision, 2026-10-13). */
    const val WIN_PASS = 0.7
    /** The sweep bonus each fully easy opponent still to come adds over 1. */
    const val SWEEP_BONUS_STEP = 0.15
    const val STOPPER_PASS = 0.5
    val ALWAYS_STOPS = setOf(StopToolKind.ENCORE, StopToolKind.FORCES_SWITCH,
        StopToolKind.IGNORES_BOOSTS, StopToolKind.DESTINY_BOND)
    val NEVER_STOPS = setOf(StopToolKind.RESETS_BOOSTS, StopToolKind.PERISH_SONG, StopToolKind.TAUNT)
    val RETREATS_FROM = setOf(StopToolKind.IGNORES_BOOSTS, StopToolKind.DESTINY_BOND)
}
