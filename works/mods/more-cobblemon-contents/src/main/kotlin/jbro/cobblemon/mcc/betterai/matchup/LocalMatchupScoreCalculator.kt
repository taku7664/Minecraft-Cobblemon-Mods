package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicFieldMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMoveTargets
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveGroup
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleStatusMoveCategories
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/** Builds a decision's [MatchupScores]: every living ally against every living opponent it has seen. */
internal object LocalMatchupScoreCalculator {
    fun calculate(
        context: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        shouldContinue: () -> Boolean = { true },
        recovery: Boolean = false,
    ): MatchupScores {
        cache.matchupRecovery = recovery
        val allies = living(context.state, BattleSide.ALLY)
        val opponents = living(context.state, BattleSide.OPPONENT)
        val built = buildPairs(context, allies, opponents, cache, shouldContinue, recovery, MatchupSpeedField.entries)
        val moves = built.moves
        val pokemon = built.pokemon
        if (!built.complete) return MatchupScores(moves, pokemon, complete = false)
        val pairs = MatchupScores(moves, pokemon, complete = true)
        val sweeps = linkedMapOf<UUID, SweepScore>()
        for (subject in allies + opponents) {
            if (!shouldContinue()) return MatchupScores(moves, pokemon, complete = false, sweeps = sweeps)
            val foes = if (subject.side == BattleSide.ALLY) opponents else allies
            sweepScore(context, subject, foes, pairs, cache)?.let { sweeps[subject.battlePokemonId] = it }
        }
        val withSweeps = MatchupScores(moves, pokemon, complete = true, sweeps = sweeps)
        val stops = linkedMapOf<UUID, StopScore>()
        for ((side, stoppers) in listOf(BattleSide.OPPONENT to allies, BattleSide.ALLY to opponents)) {
            val sweeper = withSweeps.sweeper(side, context.state) ?: continue
            val sweeperPokemon = context.state.pokemon.first { it.battlePokemonId == sweeper.subjectId }
            for (subject in stoppers) {
                if (!shouldContinue()) return MatchupScores(moves, pokemon, complete = false, sweeps = sweeps, stops = stops)
                LocalStopScoreCalculator.score(context, subject, sweeper, sweeperPokemon, pairs, cache)
                    ?.let { stops[subject.battlePokemonId] = it }
            }
        }
        val statusMoves = linkedMapOf<Pair<UUID, UUID>, List<StatusMoveMatchupScore>>()
        val switchIns = linkedMapOf<Triple<UUID, UUID, UUID>, SwitchInScore>()
        fun partial() = MatchupScores(moves, pokemon, complete = false, sweeps = sweeps, stops = stops,
            statusMovesByPair = statusMoves, switchInsByKey = switchIns)
        val allyField = allies.filter { it.activeSlot != null }
        val opponentField = opponents.filter { it.activeSlot != null }
        for (ally in allyField) for (opponent in opponentField) {
            if (!shouldContinue()) return partial()
            statusMoves[ally.battlePokemonId to opponent.battlePokemonId] =
                LocalTurnCostScoreCalculator.statusMoves(context, ally, opponent, withSweeps, cache)
            statusMoves[opponent.battlePokemonId to ally.battlePokemonId] =
                LocalTurnCostScoreCalculator.statusMoves(context, opponent, ally, withSweeps, cache)
        }
        for (incoming in allies.filter { it.activeSlot == null }) for (replaced in allyField) for (opponent in opponentField) {
            if (!shouldContinue()) return partial()
            LocalTurnCostScoreCalculator.switchIn(context, incoming, replaced, opponent, withSweeps, cache)
                ?.let { switchIns[Triple(incoming.battlePokemonId, opponent.battlePokemonId, replaced.battlePokemonId)] = it }
        }
        val preserves = LocalTurnCostScoreCalculator.preserves(allies, opponents, withSweeps) +
            LocalTurnCostScoreCalculator.preserves(opponents, allies, withSweeps)
        return MatchupScores(moves, pokemon, complete = true, sweeps = sweeps, stops = stops,
            statusMovesByPair = statusMoves, switchInsByKey = switchIns, preserves = preserves)
    }

    /** CURRENT pairs only, retaining the full calculator's ally-first orientation and arithmetic order. */
    fun currentPairs(
        context: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
        recovery: Boolean = false,
        allyIds: Set<UUID>? = null,
        opponentIds: Set<UUID>? = null,
    ): MatchupPairs {
        cache.matchupRecovery = recovery
        return buildPairs(context, living(context.state, BattleSide.ALLY), living(context.state, BattleSide.OPPONENT),
            cache, { true }, recovery, listOf(MatchupSpeedField.CURRENT), allyIds, opponentIds)
    }

    private class PairCalculation(
        val moves: Map<Pair<UUID, UUID>, List<MoveMatchupScore>>,
        val pokemon: Map<Triple<UUID, UUID, MatchupSpeedField>, PokemonMatchupScore>,
        override val complete: Boolean,
    ) : MatchupPairs {
        override fun moves(userId: UUID, targetId: UUID) = moves[userId to targetId].orEmpty()
        override fun pokemon(subjectId: UUID, opponentId: UUID, speedField: MatchupSpeedField): PokemonMatchupScore? =
            pokemon[Triple(subjectId, opponentId, speedField)] ?: pokemon[Triple(opponentId, subjectId, speedField)]?.mirrored()
    }

    private fun buildPairs(
        context: BattleDecisionContext,
        allies: List<BattlePokemonStateView>,
        opponents: List<BattlePokemonStateView>,
        cache: LocalProjectedActionCalculationCache,
        shouldContinue: () -> Boolean,
        recovery: Boolean,
        fields: List<MatchupSpeedField>,
        allyIds: Set<UUID>? = null,
        opponentIds: Set<UUID>? = null,
    ): PairCalculation {
        val moves = linkedMapOf<Pair<UUID, UUID>, List<MoveMatchupScore>>()
        val pokemon = linkedMapOf<Triple<UUID, UUID, MatchupSpeedField>, PokemonMatchupScore>()
        for (ally in allies) for (opponent in opponents) {
            if (allyIds != null && ally.battlePokemonId !in allyIds || opponentIds != null && opponent.battlePokemonId !in opponentIds) continue
            if (!shouldContinue()) return PairCalculation(moves, pokemon, complete = false)
            val position = LocalMatchupPosition.face(context, ally, opponent, cache) ?: continue
            val allyMoves = moveMatchups(position, ally.battlePokemonId, opponent.battlePokemonId, cache)
            val opponentMoves = moveMatchups(position, opponent.battlePokemonId, ally.battlePokemonId, cache)
            moves[ally.battlePokemonId to opponent.battlePokemonId] = allyMoves.map { it.score }
            moves[opponent.battlePokemonId to ally.battlePokemonId] = opponentMoves.map { it.score }
            for (field in fields) {
                val faced = if (field == MatchupSpeedField.CURRENT) position else withTrickRoomToggled(position)
                bestExchange(faced, ally.battlePokemonId, opponent.battlePokemonId, allyMoves, opponentMoves, field, recovery)?.let {
                    pokemon[Triple(ally.battlePokemonId, opponent.battlePokemonId, field)] = it
                }
            }
        }
        return PairCalculation(moves, pokemon, complete = true)
    }

    /**
     * [subject]'s sweep against [foes], as it stands and after one or two uses of each of its stat-raising
     * status moves. A boosted reading re-scores both sides' attacks, since a boost changes damage dealt,
     * damage taken and turn order alike.
     */
    private fun sweepScore(
        context: BattleDecisionContext,
        subject: BattlePokemonStateView,
        foes: List<BattlePokemonStateView>,
        pairs: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): SweepScore? {
        val matchups = foes.mapNotNull { pairs.pokemon(subject.battlePokemonId, it.battlePokemonId) }
        if (matchups.isEmpty()) return null
        val natural = matchups.map { it.winProbability }.average()
        var best = Boost(null, 0, 0.0, 1.0, emptyMap(), emptyMap())
        val costs = setupHpCosts(context, subject)
        for ((moveId, stages) in setupMoves(context, subject)) for (uses in 1..MAXIMUM_SETUP_USES) {
            val cost = costs[moveId] ?: 0.0
            // Each foe attacks through the setup turns; the boosted exchange starts from what is left.
            val outcomes = foes.mapNotNull { foe ->
                val base = pairs.pokemon(subject.battlePokemonId, foe.battlePokemonId) ?: return@mapNotNull null
                val taken = base.opponentMove?.damageWhileStandingByUses?.getOrNull(uses) ?: 0.0
                val survives = survivesCost(subject, cost, uses, base.opponentMove?.survivalByUses?.getOrNull(uses) ?: 1.0, taken)
                val position = LocalMatchupPosition.face(context, subject, foe, cache) ?: return@mapNotNull null
                val raised = setupState(position.state, subject.battlePokemonId, stages, uses, cost)
                val worn = raised.copyState(pokemon = raised.pokemon.map {
                    if (it.battlePokemonId != subject.battlePokemonId) it
                    else it.copyState(hpFraction = (it.hpFraction - taken).coerceAtLeast(MINIMUM_STANDING_HP))
                })
                val win = pairMatchup(position.copy(state = worn), subject.battlePokemonId, foe.battlePokemonId,
                    MatchupSpeedField.CURRENT, cache)?.winProbability ?: return@mapNotNull null
                Triple(foe.battlePokemonId, survives, survives * win)
            }
            if (outcomes.isEmpty()) continue
            val sweep = outcomes.map { it.third }.average()
            if (sweep > best.sweep + 1e-9) best = Boost(moveId, uses, sweep, outcomes.map { it.second }.average(),
                outcomes.associate { it.first to it.third }, outcomes.associate { it.first to it.second })
        }
        val window = windowSweep(context, subject, foes, pairs, cache)
        return SweepScore(
            windowSweep = window?.first,
            windowByOpponent = window?.second.orEmpty(),
            subjectId = subject.battlePokemonId,
            naturalSweep = natural,
            setupMoveId = best.moveId,
            setupUses = best.uses,
            boostedSweep = best.sweep,
            setupSafety = best.safety,
            score = maxOf(natural, best.sweep).coerceIn(0.0, 1.0),
            boostedByOpponent = best.byOpponent,
            setupSurvivalByOpponent = best.survivalByOpponent,
        )
    }

    /** [SweepScore.windowSweep] and its per-opponent values: the best setup move and use count, from this window. */
    private fun windowSweep(
        context: BattleDecisionContext,
        subject: BattlePokemonStateView,
        foes: List<BattlePokemonStateView>,
        pairs: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): Pair<Double, Map<UUID, Double>>? {
        if (subject.activeSlot == null) return null
        val facing = foes.filter { it.activeSlot != null }
        if (facing.isEmpty()) return null
        var best: Pair<Double, Map<UUID, Double>>? = null
        val costs = setupHpCosts(context, subject)
        for ((moveId, stages) in setupMoves(context, subject)) for (uses in 1..MAXIMUM_SETUP_USES) {
            val cost = costs[moveId] ?: 0.0
            var survives = 1.0
            var taken = 0.0
            for (foe in facing) {
                val base = pairs.pokemon(subject.battlePokemonId, foe.battlePokemonId) ?: continue
                survives *= base.opponentMove?.survivalByUses?.getOrNull(uses) ?: 1.0
                taken += base.opponentMove?.damageWhileStandingByUses?.getOrNull(uses) ?: 0.0
            }
            survives = survivesCost(subject, cost, uses, survives, taken)
            val byOpponent = foes.mapNotNull { foe ->
                val position = LocalMatchupPosition.face(context, subject, foe, cache) ?: return@mapNotNull null
                val raised = setupState(position.state, subject.battlePokemonId, stages, uses, cost)
                val worn = raised.copyState(pokemon = raised.pokemon.map {
                    if (it.battlePokemonId != subject.battlePokemonId) it
                    else it.copyState(hpFraction = (it.hpFraction - taken).coerceAtLeast(MINIMUM_STANDING_HP))
                })
                val win = pairMatchup(position.copy(state = worn), subject.battlePokemonId, foe.battlePokemonId,
                    MatchupSpeedField.CURRENT, cache)?.winProbability ?: return@mapNotNull null
                foe.battlePokemonId to survives * win
            }.toMap()
            if (byOpponent.isEmpty()) continue
            val sweep = byOpponent.values.average()
            if (best == null || sweep > best.first + 1e-9) best = sweep to byOpponent
        }
        return best
    }

    /**
     * [subject]'s win chance against [foe] with [stages] raised once: when [setupTurn], it first takes [foe]'s hit in the
     * turn it spends raising them (surviving it, and fighting from the HP it left); otherwise it starts boosted from
     * its current HP, as it would in front of a foe that comes in after the boost. Null when the pair cannot be read.
     */
    internal fun boostedWin(
        context: BattleDecisionContext,
        subject: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        stages: Map<String, Int>,
        pairs: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
        setupTurn: Boolean,
        /** The share of maximum HP the raise costs (Belly Drum's half). */
        hpCost: Double = 0.0,
    ): Double? {
        // A raise it cannot pay for fails: nothing is bought with it.
        if (hpCost > 0.0 && subject.hpFraction <= hpCost) return 0.0
        return boostedExchange(context, subject, foe, stages, pairs, cache, setupTurn, hpCost)?.let { (survives, pair) -> survives * pair.winProbability }
    }

    /** [boostedWin]'s exchange itself, with the chance [subject] lives through the setup turn: its knockout's timing too. */
    internal fun boostedExchange(
        context: BattleDecisionContext,
        subject: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        stages: Map<String, Int>,
        pairs: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
        setupTurn: Boolean,
        hpCost: Double = 0.0,
    ): Pair<Double, PokemonMatchupScore>? {
        val base = if (setupTurn) pairs.pokemon(subject.battlePokemonId, foe.battlePokemonId) ?: return null else null
        val taken = base?.opponentMove?.damageWhileStandingByUses?.getOrNull(1) ?: 0.0
        val survives = survivesCost(subject, hpCost, 1, base?.opponentMove?.survivalByUses?.getOrNull(1) ?: 1.0, taken)
        val position = LocalMatchupPosition.face(context, subject, foe, cache) ?: return null
        val raised = setupState(position.state, subject.battlePokemonId, stages, 1, hpCost)
        val worn = raised.copyState(pokemon = raised.pokemon.map {
            if (it.battlePokemonId != subject.battlePokemonId) it
            else it.copyState(hpFraction = (it.hpFraction - taken).coerceAtLeast(MINIMUM_STANDING_HP))
        })
        val pair = pairMatchup(position.copy(state = worn), subject.battlePokemonId, foe.battlePokemonId,
            MatchupSpeedField.CURRENT, cache) ?: return null
        return survives to pair
    }

    private class Boost(
        val moveId: String?,
        val uses: Int,
        val sweep: Double,
        val safety: Double,
        val byOpponent: Map<UUID, Double>,
        val survivalByOpponent: Map<UUID, Double>,
    )

    /** The pair re-scored from scratch in [position]: both sides' moves, then the exchange. */
    internal fun pairMatchup(
        position: BattleDecisionContext,
        subjectId: UUID,
        opponentId: UUID,
        speedField: MatchupSpeedField,
        cache: LocalProjectedActionCalculationCache,
    ): PokemonMatchupScore? = bestExchange(position, subjectId, opponentId,
        moveMatchups(position, subjectId, opponentId, cache),
        moveMatchups(position, opponentId, subjectId, cache), speedField, cache.matchupRecovery)

    /**
     * The exchange each side would pick: the subject's attack that does best against the opponent's most
     * damaging reply. Knockout counts alone would pick a slow two-hit move over a priority one that takes
     * the same two hits but moves first, so every pairing of the leading moves is played out.
     */
    private fun bestExchange(
        position: BattleDecisionContext,
        subjectId: UUID,
        opponentId: UUID,
        subjectMoves: List<ScoredMove>,
        opponentMoves: List<ScoredMove>,
        speedField: MatchupSpeedField,
        recovery: Boolean,
    ): PokemonMatchupScore? {
        fun exchangeable(moves: List<ScoredMove>) = moves.filterNot { it.score.moveId in FAILS_WHEN_HIT_FIRST }
        val mine = exchangeable(subjectMoves).take(EXCHANGE_MOVES).ifEmpty { listOf(null) }
        val theirs = exchangeable(opponentMoves).take(EXCHANGE_MOVES).ifEmpty { listOf(null) }
        return mine.mapNotNull { subjectMove ->
            theirs.mapNotNull { opponentMove ->
                pokemonMatchup(position, subjectId, opponentId, subjectMove, opponentMove, speedField, recovery)
            }.minByOrNull { it.score }
        }.maxByOrNull { it.score }
    }

    /**
     * [subject]'s stat-raising status moves and the stages one use adds: its own catalog, and for an
     * opponent also the setup slots this tier believes in without having seen them.
     */
    internal fun setupMoves(context: BattleDecisionContext, subject: BattlePokemonStateView): Map<String, Map<String, Int>> {
        val catalog = context.publicActionCatalog
        val known = catalog.forPokemon(subject.battlePokemonId).map { it.moveId to it.details }
        val inferred = if (subject.side != BattleSide.OPPONENT) emptyList() else
            catalog.inferredMovesForPokemon(subject.battlePokemonId)?.slots.orEmpty()
                .filter { it.group == BattleOpponentMoveGroup.PURE_SETUP && it.knowledge != BattleOpponentMoveKnowledge.GUESS }
                .mapNotNull { slot -> slot.moveId?.let { id -> slot.details?.let { id to it } } }
        return (known + inferred)
            .filter { (_, details) -> details.damageCategory == BattleMoveDamageCategory.STATUS && BattleStatusMoveCategories.isPureSelfSetup(details, subject) }
            .associate { (moveId, details) ->
                PublicIds.canonical(moveId) to details.effects!!.effects.fold(mutableMapOf<String, Int>()) { stages, effect ->
                    effect.statStages.forEach { (stat, delta) -> stages.merge(stat, delta, Int::plus) }
                    stages
                }
            }
    }

    /** The share of maximum HP one use of each of [subject]'s setup moves costs it (Belly Drum's half), where it costs any. */
    internal fun setupHpCosts(context: BattleDecisionContext, subject: BattlePokemonStateView): Map<String, Double> {
        val catalog = context.publicActionCatalog
        val known = catalog.forPokemon(subject.battlePokemonId).map { it.moveId to it.details }
        val inferred = if (subject.side != BattleSide.OPPONENT) emptyList() else
            catalog.inferredMovesForPokemon(subject.battlePokemonId)?.slots.orEmpty()
                .mapNotNull { slot -> slot.moveId?.let { id -> slot.details?.let { id to it } } }
        return (known + inferred).associate { (moveId, details) -> PublicIds.canonical(moveId) to BattleStatusMoveCategories.setupHpCost(details) }
            .filterValues { it > 0.0 }
    }

    /**
     * Each use resolves abilities, caps and item consumption before the next setup use, and pays [hpCost] of the
     * user's maximum HP first. A use it cannot pay (HP at or below the cost, as Belly Drum fails) does nothing, nor do
     * any after it.
     */
    internal fun setupState(state: BattleStateView, subjectId: UUID, stages: Map<String, Int>, uses: Int, hpCost: Double = 0.0): BattleStateView {
        var current = state
        repeat(uses) {
            if (hpCost > 0.0) {
                val subject = current.pokemon.firstOrNull { it.battlePokemonId == subjectId } ?: return current
                if (subject.hpFraction <= hpCost) return current
                current = current.copyState(pokemon = current.pokemon.map {
                    if (it.battlePokemonId == subjectId) it.copyState(hpFraction = it.hpFraction - hpCost) else it
                })
            }
            current = LocalStatStageChange.apply(current, subjectId, subjectId, stages)
        }
        return current
    }

    /**
     * The chance [subject] lives through the setup turns' hits once it has paid [hpCost] per use: a cost that leaves it
     * at or below the damage it takes meanwhile ends it. [survives] and [taken] were read from its HP before the cost.
     */
    private fun survivesCost(subject: BattlePokemonStateView, hpCost: Double, uses: Int, survives: Double, taken: Double): Double {
        if (hpCost <= 0.0) return survives
        val paid = (1..uses).takeWhile { subject.hpFraction - hpCost * (it - 1) > hpCost }.count()
        return if (taken >= subject.hpFraction - hpCost * paid) 0.0 else survives
    }

    /** A scored move and the action it was scored from, which turn order needs. */
    internal class ScoredMove(val score: MoveMatchupScore, val action: BattleActionCandidate)

    /**
     * [userId]'s damaging moves against [targetId], both already active in [position], best first: fewest
     * expected uses to a knockout, then the more accurate.
     */
    internal fun moveMatchups(
        position: BattleDecisionContext,
        userId: UUID,
        targetId: UUID,
        cache: LocalProjectedActionCalculationCache,
    ): List<ScoredMove> {
        val state = position.state
        val user = state.pokemon.firstOrNull { it.battlePokemonId == userId && it.activeSlot != null } ?: return emptyList()
        val target = state.pokemon.firstOrNull { it.battlePokemonId == targetId && it.activeSlot != null } ?: return emptyList()
        val side = user.side
        // The opponent's unrevealed slots count at the strength this tier believes in; the AI's own moves are exact.
        val hypotheses = side == BattleSide.OPPONENT
        return cache.slotActions(state, side, position.publicActionCatalog, hypotheses) {
            PublicFutureActionFactory.slotActions(state, side, position.publicActionCatalog, includeMoveHypotheses = hypotheses)
        }
            .asSequence()
            .filter { it.kind == BattleActionKind.USE_MOVE && it.actorSlot == user.activeSlot }
            .filter { it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS }
            // A revealed Max Move is what a Dynamaxed Pokemon turned one of its moves into, at the dex's placeholder power
            // of 10: the move it came from is read in its place (the inference keeps an attack of its type).
            .filterNot { jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.isMaxMove(it.moveId) }
            // A Dynamaxed user's attacks are its Max Moves.
            .map { jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.asMaxMove(it, user) }
            .filter { jbro.cobblemon.mcc.betterai.mechanics.LocalConsecutiveMoveRules.canAttempt(it,state,user) }
            .mapNotNull { original ->
                // Its own form change before it hits (Stance Change: Blade for an attack), as the turn projection reads it.
                fun calculate(action: BattleActionCandidate): BattleDecisionContext {
                    val acting = jbro.cobblemon.mcc.betterai.mechanics.LocalStanceChangeStateProjector.beforeMove(state, side, action)
                    return cache.getOrCalculate(acting, side, action, position.publicActionCatalog) {
                        PublicBattleTacticalCalculator.calculate(position.copy(state = acting, candidates = listOf(action)), side)
                    }
                }
                var action = original
                var calculated = calculate(action)
                var candidate = calculated.candidates.single()
                val targets = LocalPublicMoveTargets.resolve(candidate, calculated, side)
                if (targets.firstOrNull()?.battlePokemonId != targetId) {
                    // A spread move's rolls are for its primary target. At another it hits, it is the same move
                    // declared at that one, tagged so the calculator applies the spread reduction before it caps
                    // the rolls at the target's HP (reduced after the cap, no roll could ever knock it out).
                    if (targets.size < 2 || targets.none { it.battlePokemonId == targetId }) return@mapNotNull null
                    action = LocalPublicMoveTargets.spreadHitOn(original, target, "${original.actionId}:at:${target.side}:${target.activeSlot}")
                    calculated = calculate(action)
                    candidate = calculated.candidates.single()
                }
                val rolls = if (LocalPublicMechanicsKernel.projectMove(candidate, calculated, side).publiclyNullified) {
                    List(ROLLS) { 0.0 }
                } else {
                    PublicBattleTacticalCalculator.conservativeDamageRollFractions(candidate, calculated, side)
                } ?: return@mapNotNull null
                val accuracy = LocalPublicAccuracy.probability(candidate, calculated, side).coerceIn(0.0, 1.0)
                // A Max Move is scored under the move it came from: the battle's candidates keep that id (the game's adapter
                // hands a Dynamax move over as the base move with the Max Move's details), and every reader matches by it.
                val moveId = jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.baseMoveId(original) ?: PublicIds.canonical(candidate.moveId ?: action.actionId)
                val firstHitLimit = if (jbro.cobblemon.mcc.betterai.mechanics.LocalDeclaredMultiHit.maximumCount(candidate) <= 1 &&
                    !jbro.cobblemon.mcc.betterai.mechanics.LocalDisguise.intact(state, target) &&
                    jbro.cobblemon.mcc.betterai.mechanics.LocalFullHealthSurvivalRules.survivesAnySingleHit(state, target,
                        jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityMechanics.ignoresTargetAbility(candidate, user, target, state))) {
                    val oneHp = 1.0 / (target.combatStats?.maxHp?.maximum ?: LocalKnockoutProfile.MAXIMUM_CELLS)
                    (target.hpFraction - oneHp).coerceAtLeast(0.0)
                } else null
                val profile = LocalRepeatedMoveMechanics.profile(position, action, userId, targetId, rolls, accuracy, cache)
                    ?: if (selfCostRank(original) == SELF_KNOCKOUT_RANK) LocalKnockoutProfile.once(rolls, accuracy, target.hpFraction, MAXIMUM_USES, firstHitLimit)
                    else ownStageRolls(position, action, user, rolls, side, cache)?.let {
                        LocalKnockoutProfile.byHits(it, accuracy, target.hpFraction, MAXIMUM_USES, firstHitLimit)
                    } ?: firstHitLimit?.let { LocalKnockoutProfile.of(rolls, accuracy, target.hpFraction, MAXIMUM_USES, it) }
                // Its own knockout as the outcome projector reads it: Final Gambit's only when it lands.
                val selfKnockout = original.moveDetails?.effects?.effects.orEmpty().filter {
                    it.target == jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget.USER && it.kind == BattleMoveEffectKind.SELF_DESTRUCT
                }.maxOfOrNull {
                    (if (it.valueId == jbro.cobblemon.mcc.internal.ai.BattleDeclarativeMoveEffects.SELF_DESTRUCT_IF_HIT) accuracy else 1.0) * (it.probability ?: 1.0)
                } ?: 0.0
                // An intact Disguise takes the first hit for an eighth of the HP (not through an ability-ignoring move).
                val chip = jbro.cobblemon.mcc.betterai.mechanics.LocalDisguise.chip(state, target)
                val disguised = jbro.cobblemon.mcc.betterai.mechanics.LocalDisguise.intact(state, target) &&
                    target.hpFraction > chip &&
                    !jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityMechanics.ignoresTargetAbility(original, user, target, state)
                val guardedProfile = if (!disguised) profile
                    else (profile ?: LocalKnockoutProfile.of(rolls, accuracy, target.hpFraction, MAXIMUM_USES)).behindDisguise(chip)
                val finalProfile = if (jbro.cobblemon.mcc.betterai.mechanics.LocalConsecutiveMoveRules.needsGap(action,user))
                    (guardedProfile ?: LocalKnockoutProfile.of(rolls,accuracy,target.hpFraction,MAXIMUM_USES)).withGapBetweenUses()
                    else guardedProfile
                val firstRolls = if (firstHitLimit == null) rolls else rolls.map { minOf(it, firstHitLimit) }
                ScoredMove(moveScore(userId, moveId, targetId, firstRolls, accuracy, target.hpFraction, finalProfile).copy(
                    selfKnockoutChance = selfKnockout,
                    priority = LocalPublicTurnOrder.effectivePriority(state, side, action),
                ), original)
            }
            .groupBy { it.score.moveId }.values.map { same -> same.minWith(BEST_FIRST) }
            .sortedWith(BEST_FIRST)
    }


    /**
     * The rolls of an attack that surely changes its user's own stats, once it has landed each number of times: the
     * stages pile up and are read by the damage calculator, up to the first landing that changes nothing (the stage at
     * its limit, or a stat the attack does not use, as Close Combat's defences), unless that landing spent an item (a
     * White Herb restoring the drop once). Null when no landing changes the damage, and for a Max Move, whose Dynamax
     * ends within three turns. The user's other changes are not read here: a lowered Defence makes the other side's hits
     * no harder in this table, nor does a raised Speed change who moves first.
     */
    private fun ownStageRolls(
        position: BattleDecisionContext,
        action: BattleActionCandidate,
        user: BattlePokemonStateView,
        first: List<Double>,
        side: BattleSide,
        cache: LocalProjectedActionCalculationCache,
    ): List<List<Double>>? {
        val changes = action.moveDetails?.effects?.effects.orEmpty().filter {
            it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER && it.statStages.isNotEmpty() &&
                (it.probability ?: 1.0) >= CERTAIN
        }
        if (changes.isEmpty() || first.all { it <= 0.0 }) return null
        if (jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.baseMoveId(action) != action.moveId?.let(PublicIds::canonical)) return null
        val byHits = arrayListOf(first)
        var state = position.state
        for (landed in 1 until MAXIMUM_USES) {
            val itemBefore = state.pokemon.firstOrNull { it.battlePokemonId == user.battlePokemonId }?.knownHeldItemId
            // The whole transition: Contrary, Simple, and a White Herb undoing the drop once.
            changes.forEach { effect ->
                state = LocalStatStageChange.apply(state, user.battlePokemonId, user.battlePokemonId, effect.statStages)
            }
            val itemSpent = state.pokemon.firstOrNull { it.battlePokemonId == user.battlePokemonId }?.knownHeldItemId != itemBefore
            val calculated = cache.getOrCalculate(state, side, action, position.publicActionCatalog) {
                PublicBattleTacticalCalculator.calculate(position.copy(state = state, candidates = listOf(action)), side)
            }
            val candidate = calculated.candidates.singleOrNull() ?: break
            val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(candidate, calculated, side) ?: break
            if (rolls == byHits.last() && !itemSpent) break
            byHits += rolls
        }
        return byHits.takeIf { it.distinct().size > 1 }
    }

    private const val CERTAIN = 0.999_999

    // Of two moves knocking out as fast, the one that does not cost its user HP or the Pokemon itself.
    private val BEST_FIRST = compareBy<ScoredMove> { it.score.expectedHitsToKnockout }
        .thenBy { selfCostRank(it.action) }
        .thenByDescending { it.score.accuracy }
        .thenBy { it.score.moveId }

    private fun selfCostRank(action: BattleActionCandidate): Int {
        val effects = action.moveDetails?.effects?.effects.orEmpty().filter { it.target == jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget.USER }
        return when {
            effects.any { it.kind == jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind.SELF_DESTRUCT } -> SELF_KNOCKOUT_RANK
            effects.any { it.kind in SELF_HP_COSTS } -> 1
            else -> 0
        }
    }

    private const val SELF_KNOCKOUT_RANK = 2

    private val SELF_HP_COSTS = setOf(jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind.RECOIL_FRACTION,
        jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind.MAX_HP_RECOIL, jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind.CRASH_RECOIL,
        jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind.STRUGGLE_RECOIL)

    internal fun moveScore(
        userId: UUID,
        moveId: String,
        targetId: UUID,
        rolls: List<Double>,
        accuracy: Double,
        targetHp: Double,
        projectedProfile: LocalKnockoutProfile? = null,
    ): MoveMatchupScore {
        val profile = projectedProfile ?: LocalKnockoutProfile.of(rolls, accuracy, targetHp, MAXIMUM_USES)
        return MoveMatchupScore(
            userId = userId,
            moveId = moveId,
            targetId = targetId,
            accuracy = accuracy,
            minimumDamageFraction = rolls.minOrNull() ?: 0.0,
            maximumDamageFraction = rolls.maxOrNull() ?: 0.0,
            survivalByUses = profile.survival,
            damageWhileStandingByUses = profile.damageWhileStanding,
            expectedHitsToKnockout = profile.expectedUses,
            score = if (profile.expectedUses.isInfinite()) 0.0 else (1.0 / profile.expectedUses).coerceIn(0.0, 1.0),
        )
    }

    /**
     * The exchange between two active Pokemon, each using its best attack every turn. Knockout timing comes
     * from the two moves' use profiles, which are independent; the order from the two actions, priority
     * included.
     *
     * With [recovery], a Pokemon with a self-heal (Recover, Roost, Soft-Boiled) outlasts an attack whose expected
     * hit is less than one heal: that attack never lands the knockout. It spends the share of its turns the other's
     * hits take to undo on healing, so its own knockout comes later by that much. Without this a wall that Roosts
     * off every Fire Blast read as worn down in three hits, and the attacker kept firing.
     */
    internal fun pokemonMatchup(
        position: BattleDecisionContext,
        subjectId: UUID,
        opponentId: UUID,
        subjectMove: ScoredMove?,
        opponentMove: ScoredMove?,
        speedField: MatchupSpeedField,
        recovery: Boolean = false,
    ): PokemonMatchupScore? {
        val state = position.state
        val subject = state.pokemon.firstOrNull { it.battlePokemonId == subjectId && it.activeSlot != null } ?: return null
        val opponent = state.pokemon.firstOrNull { it.battlePokemonId == opponentId && it.activeSlot != null } ?: return null
        // One that faints coming in (a Steelix at 12% onto Stealth Rock and Spikes) never fights: the exchange is decided
        // on entry. Read as an exchange, the fainted one had no attacks and the pair came out a loss for the Ferrothorn it
        // could not even reach (2026-10-18).
        val subjectDown = subject.fainted || subject.hpFraction <= 0.0
        val opponentDown = opponent.fainted || opponent.hpFraction <= 0.0
        if (subjectDown || opponentDown) {
            val decided = when {
                subjectDown && opponentDown -> 0.5
                opponentDown -> 1.0
                else -> 0.0
            }
            return PokemonMatchupScore(subjectId, opponentId, speedField, subjectMove?.score, opponentMove?.score, 0.5, decided,
                subject.hpFraction, opponent.hpFraction,
                (decided * (0.5 + 0.5 * subject.hpFraction) - (1.0 - decided) * (0.5 + 0.5 * opponent.hpFraction)).coerceIn(-1.0, 1.0))
        }
        val first = if (subjectMove != null && opponentMove != null) {
            LocalPublicTurnOrder.actsFirstProbability(state, subject.side, subjectMove.action, opponent.side, opponentMove.action)
        } else null
        val subjectFirst = (first ?: LocalPublicTurnOrder.speedOrderProbability(state, subject, opponent) ?: 0.5).coerceIn(0.0, 1.0)
        var mine = LocalFlinchAvailability.profile(position, opponentMove, subjectMove, 1.0 - subjectFirst)
        var theirs = LocalFlinchAvailability.profile(position, subjectMove, opponentMove, subjectFirst)
        // A side the other cannot hurt still loses its status's end-of-turn damage, which has no attack's uses to ride on.
        var subjectDecay: Decay? = null
        var opponentDecay: Decay? = null
        // The turns each side gets its attack off: a paralysed Pokemon three in four, and a hit landed first that can
        // flinch takes the other's turn at its chance. The evolving exchange plays these turns out itself. Without them a
        // paralysed Kyogre facing a Serene Grace Air Slash read as untouched, and Thunder Wave on it came to 4 (2026-10-12).
        if (!jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.current().evolvingMatchups) {
            fun acts(pokemon: BattlePokemonStateView) =
                if (PublicIds.canonical(pokemon.statusId.orEmpty()) == PARALYSIS) PARALYSIS_ACTS else 1.0
            // A flinch declared on every hit works under a condition (Fake Out on its user's first turn out, Upper Hand only
            // into a priority move) and takes one turn at most, not every turn of the exchange: read as every turn, a Liepard
            // at 1/249 HP whose Fake Out could no longer work "won" against a Pelipper that knocks it out with any move
            // (league run, 2026-10-19). That one free turn is the move's own denied-turn value (LocalRootFlinchValue). A chance flinch (Air Slash) stays.
            fun flinches(move: ScoredMove?, user: BattlePokemonStateView, target: BattlePokemonStateView, userFirst: Double) =
                move?.takeUnless { candidate -> jbro.cobblemon.mcc.betterai.evaluation.LocalRootFlinchValue.declaredEveryHit(candidate.action) ||
                    candidate.action.moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY } }
                    ?.let { jbro.cobblemon.mcc.betterai.evaluation.LocalRootFlinchValue.chance(it.action, user, target, state) *
                    it.score.accuracy * userFirst * acts(user) } ?: 0.0
            val subjectActs = acts(subject) * (1.0 - flinches(opponentMove, opponent, subject, 1.0 - subjectFirst))
            val opponentActs = acts(opponent) * (1.0 - flinches(subjectMove, subject, opponent, subjectFirst))
            mine = mine?.let { thinned(it, subjectActs) }?.let { asleep(it, subject, state) }?.let { residual(it, opponent, state) }
            theirs = theirs?.let { thinned(it, opponentActs) }?.let { asleep(it, opponent, state) }?.let { residual(it, subject, state) }
            if (theirs == null) subjectDecay = decay(subject, state)
            if (mine == null) opponentDecay = decay(opponent, state)
        }
        // Which side cannot get through the other's healing, and how often the healer still attacks.
        var subjectWalled = false
        var opponentWalled = false
        var subjectAttackRate = 1.0
        var opponentAttackRate = 1.0
        if (recovery) {
            val subjectHeal = healFraction(position, subject)
            val opponentHeal = healFraction(position, opponent)
            val mineHit = mine?.let(::expectedHit) ?: 0.0
            val theirsHit = theirs?.let(::expectedHit) ?: 0.0
            // A wall that out-heals the hit is never knocked out by it, and slows its own attack by the healing turns.
            if (opponentHeal > 0.0 && mineHit < opponentHeal) {
                subjectWalled = true
                opponentAttackRate = 1.0 - mineHit / opponentHeal
                mine = mine?.let(::neverKnocksOut)
                theirs = theirs?.let { slowed(it, mineHit / opponentHeal) }
            }
            if (subjectHeal > 0.0 && theirsHit < subjectHeal) {
                opponentWalled = true
                subjectAttackRate = 1.0 - theirsHit / subjectHeal
                theirs = theirs?.let(::neverKnocksOut)
                mine = mine?.let { slowed(it, theirsHit / subjectHeal) }
            }
        }
        // The subject is standing after `n` of the opponent's attacks with this chance, having taken that much.
        fun standing(score: MoveMatchupScore?, uses: Int): Double =
            score?.survivalByUses?.let { it[uses.coerceAtMost(it.lastIndex)] } ?: 1.0
        fun taken(score: MoveMatchupScore?, uses: Int): Double =
            score?.damageWhileStandingByUses?.let { it[uses.coerceAtMost(it.lastIndex)] } ?: 0.0
        var win = 0.0
        var winHp = 0.0
        var loss = 0.0
        var lossHp = 0.0
        // `n` is the turn the attacker lands its knockout; `n = MAXIMUM_USES + 1` stands for "later or never".
        for (n in 1..MAXIMUM_USES + 1) {
            val subjectKnocksOutAt = knockoutAt(mine, n)
            if (subjectKnocksOutAt > 0.0) {
                // Moving first it has taken n - 1 attacks, moving second n; either way n - 1 turns' end-of-turn damage.
                val lasted = Decay.alive(subjectDecay, n - 1)
                val asFirst = subjectFirst * standing(theirs, n - 1) * lasted
                val asSecond = (1.0 - subjectFirst) * standing(theirs, n) * lasted
                val worn = Decay.taken(subjectDecay, n - 1)
                win += subjectKnocksOutAt * (asFirst + asSecond)
                winHp += subjectKnocksOutAt * (asFirst * (subject.hpFraction - taken(theirs, n - 1) - worn) +
                    asSecond * (subject.hpFraction - taken(theirs, n) - worn))
            }
            val opponentKnocksOutAt = knockoutAt(theirs, n)
            if (opponentKnocksOutAt > 0.0) {
                val lasted = Decay.alive(opponentDecay, n - 1)
                val asFirst = (1.0 - subjectFirst) * standing(mine, n - 1) * lasted
                val asSecond = subjectFirst * standing(mine, n) * lasted
                val worn = Decay.taken(opponentDecay, n - 1)
                loss += opponentKnocksOutAt * (asFirst + asSecond)
                lossHp += opponentKnocksOutAt * (asFirst * (opponent.hpFraction - taken(mine, n - 1) - worn) +
                    asSecond * (opponent.hpFraction - taken(mine, n) - worn))
            }
            // Falling to the end of turn n, after both have moved: the other wins if it is still standing then, and the two
            // falling on the same turn split it.
            val subjectFalls = Decay.falls(subjectDecay, n)
            if (subjectFalls > 0.0) {
                val other = standing(mine, n) * (Decay.alive(opponentDecay, n) + 0.5 * Decay.falls(opponentDecay, n))
                loss += subjectFalls * other
                lossHp += subjectFalls * other * (opponent.hpFraction - taken(mine, n) - Decay.taken(opponentDecay, n))
            }
            val opponentFalls = Decay.falls(opponentDecay, n)
            if (opponentFalls > 0.0) {
                val other = standing(theirs, n) * (Decay.alive(subjectDecay, n) + 0.5 * Decay.falls(subjectDecay, n))
                win += opponentFalls * other
                winHp += opponentFalls * other * (subject.hpFraction - taken(theirs, n) - Decay.taken(subjectDecay, n))
            }
        }
        // Neither landing a knockout in the window is a stand-off: split it by who acts first. Against healing it
        // leans to the healer by the share of turns it still attacks: a healer that must heal every turn to keep up is
        // stuck in the loop and wins nothing, and two walls split it.
        val stalemate = (1.0 - win - loss).coerceAtLeast(0.0)
        val total = win + loss + stalemate
        val subjectDamages = (mine?.maximumDamageFraction ?: 0.0) > 0.0
        val opponentDamages = (theirs?.maximumDamageFraction ?: 0.0) > 0.0
        val stalemateShare = when {
            subjectWalled && opponentWalled -> 0.5
            subjectWalled -> if (opponentDamages) 0.5 - 0.5 * opponentAttackRate else 0.5
            opponentWalled -> if (subjectDamages) 0.5 + 0.5 * subjectAttackRate else 0.5
            else -> subjectFirst
        }
        val winProbability = if (total <= 0.0) 0.5 else ((win + stalemate * stalemateShare) / total).coerceIn(0.0, 1.0)
        val subjectRemaining = if (win > 0.0) (winHp / win).coerceIn(0.0, 1.0) else subject.hpFraction
        val opponentRemaining = if (loss > 0.0) (lossHp / loss).coerceIn(0.0, 1.0) else opponent.hpFraction
        val lossProbability = 1.0 - winProbability
        return PokemonMatchupScore(
            subjectId = subjectId,
            opponentId = opponentId,
            speedField = speedField,
            subjectMove = mine,
            opponentMove = theirs,
            subjectMovesFirstProbability = subjectFirst,
            winProbability = winProbability,
            subjectRemainingHpOnWin = subjectRemaining,
            opponentRemainingHpOnLoss = opponentRemaining,
            score = (winProbability * (0.5 + 0.5 * subjectRemaining) -
                lossProbability * (0.5 + 0.5 * opponentRemaining)).coerceIn(-1.0, 1.0),
        )
    }

    /** One use's expected damage, misses included, as a share of the target's maximum HP. */
    private fun expectedHit(score: MoveMatchupScore): Double =
        score.accuracy * (score.minimumDamageFraction + score.maximumDamageFraction) / 2.0

    /**
     * The share of its maximum HP [pokemon]'s best self-heal restores, from its known moves (and, for an opponent,
     * the slots this tier believes in). Rest is left out: it puts the user to sleep for the turns it would attack.
     */
    private fun healFraction(position: BattleDecisionContext, pokemon: BattlePokemonStateView): Double {
        val catalog = position.publicActionCatalog
        val known = catalog.forPokemon(pokemon.battlePokemonId).filter { it.details.currentPp > 0 }.map { it.details }
        val inferred = if (pokemon.side != BattleSide.OPPONENT) emptyList() else
            catalog.inferredMovesForPokemon(pokemon.battlePokemonId)?.slots.orEmpty()
                .filter { it.knowledge != BattleOpponentMoveKnowledge.GUESS }.mapNotNull { it.details }
        return (known + inferred).maxOfOrNull { details ->
            val effects = details.effects?.effects.orEmpty()
            if (effects.any { it.kind == BattleMoveEffectKind.STATUS && it.target == BattleMoveEffectTarget.USER }) 0.0
            else effects.filter {
                it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER && (it.probability ?: 1.0) >= 1.0
            }.maxOfOrNull { effect -> effect.fractionRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0 } ?: 0.0
        } ?: 0.0
    }

    /** [score] with no knockout at any use: the target heals off everything it does. */
    private fun neverKnocksOut(score: MoveMatchupScore): MoveMatchupScore = score.copy(
        survivalByUses = score.survivalByUses.map { 1.0 },
        expectedHitsToKnockout = Double.POSITIVE_INFINITY,
        score = 0.0,
    )

    /**
     * [score] from a user that spends [healingShare] of its turns healing: the knockout it lands on use `n` comes on
     * turn `n / (1 - healingShare)`.
     */
    private fun slowed(score: MoveMatchupScore, healingShare: Double): MoveMatchupScore {
        val rate = (1.0 - healingShare).coerceIn(0.0, 1.0)
        if (rate >= 1.0) return score
        fun stretch(values: List<Double>) = values.indices.map { turn -> values[(turn * rate).toInt().coerceAtMost(values.lastIndex)] }
        return score.copy(
            survivalByUses = stretch(score.survivalByUses),
            damageWhileStandingByUses = stretch(score.damageWhileStandingByUses),
            expectedHitsToKnockout = if (rate <= 0.0) Double.POSITIVE_INFINITY else score.expectedHitsToKnockout / rate,
            score = score.score * rate,
        )
    }

    /**
     * [score] from a user still asleep: its uses start after the turns it sleeps through (LocalSleepClock), a third of
     * those turns used where Sleep Talk calls it. Read as awake, a Dondozo two turns into Rest was taken to attack each
     * turn (hand-played Bea, 2026-10-20).
     */
    /**
     * [score]'s hits on [target] with its status's end-of-turn damage added ([jbro.cobblemon.mcc.betterai.mechanics.LocalResidual]):
     * after `n` uses the target has also lost the residual of `n` turns, read as that many more uses at the hit's average
     * landing (fewer, for a heal), and a residual that alone empties the bar knocks it out. The evolving exchange plays
     * this out itself. Without it a badly poisoned Gardevoir read its Calm Minds as if the poison took nothing and set up
     * twice in front of the Toxapex that poisoned it (hand-played mega concept, 2026-10-20).
     */
    private fun residual(score: MoveMatchupScore, target: BattlePokemonStateView, state: jbro.cobblemon.mcc.internal.ai.BattleStateView): MoveMatchupScore {
        val survival = score.survivalByUses
        val schedule = jbro.cobblemon.mcc.betterai.mechanics.LocalResidual.schedule(state, target, survival.size) ?: return score
        val hp = target.hpFraction
        if (hp <= 0.0 || survival.size < 2) return score
        val lost = DoubleArray(survival.size).also { for (n in 1 until it.size) it[n] = it[n - 1] + schedule[n - 1] }
        val hit = score.accuracy * (score.minimumDamageFraction + score.maximumDamageFraction) / 2.0
        fun survivalAt(uses: Double): Double {
            if (uses <= 0.0) return 1.0
            val low = kotlin.math.floor(uses).toInt()
            if (low >= survival.lastIndex) return survival.last()
            val part = uses - low
            return survival[low] * (1.0 - part) + survival[low + 1] * part
        }
        val standing = survival.indices.map { n ->
            when {
                n == 0 -> 1.0
                lost[n] >= hp -> 0.0
                hit <= 0.0 -> survival[n]
                else -> survivalAt(n + lost[n] / hit).coerceIn(0.0, 1.0)
            }
        }
        val expected = if (hit <= 0.0) score.expectedHitsToKnockout else {
            val turns = score.expectedHitsToKnockout.coerceAtMost(survival.lastIndex.toDouble())
            val low = kotlin.math.floor(turns).toInt()
            val at = lost[low] + (lost.getOrElse(low + 1) { lost[low] } - lost[low]) * (turns - low)
            (score.expectedHitsToKnockout - at / hit).coerceAtLeast(0.0)
        }
        return score.copy(survivalByUses = standing, expectedHitsToKnockout = expected)
    }

    private fun asleep(score: MoveMatchupScore, user: BattlePokemonStateView, state: jbro.cobblemon.mcc.internal.ai.BattleStateView): MoveMatchupScore {
        val left = jbro.cobblemon.mcc.betterai.mechanics.LocalSleepClock.remaining(state, user) ?: return score
        val awake = 1.0 - if (jbro.cobblemon.mcc.betterai.mechanics.LocalSleepClock.talks(user)) jbro.cobblemon.mcc.betterai.mechanics.LocalSleepClock.SLEEP_TALK_SHARE else 0.0
        // Each delay split between the whole turns either side of it.
        val shifts = left.flatMap { (turns, chance) ->
            val delay = turns * awake
            val whole = kotlin.math.floor(delay).toInt()
            val part = delay - whole
            listOf(whole to chance * (1.0 - part), whole + 1 to chance * part)
        }.filter { it.second > 0.0 }
        if (shifts.all { it.first == 0 }) return score
        val survival = score.survivalByUses
        val damage = score.damageWhileStandingByUses
        val standing = survival.indices.map { n -> shifts.sumOf { (s, p) -> p * survival[(n - s).coerceAtLeast(0)] } }
        val dealt = survival.indices.map { n ->
            if (standing[n] <= 0.0) damage.getOrElse(n) { 0.0 }
            else shifts.sumOf { (s, p) -> val k = (n - s).coerceAtLeast(0); p * survival[k] * damage.getOrElse(k) { 0.0 } } / standing[n]
        }
        val now = shifts.filter { it.first == 0 }.sumOf { it.second }
        return score.copy(
            survivalByUses = standing,
            damageWhileStandingByUses = dealt,
            expectedHitsToKnockout = score.expectedHitsToKnockout + shifts.sumOf { (s, p) -> s * p },
            score = score.score * now,
        )
    }

    /**
     * [score] from a user that gets its attack off each turn with chance [rate]: after `n` turns it has landed `k` uses
     * with the binomial chance, and is standing, or has dealt, what `k` uses leave.
     */
    private fun thinned(score: MoveMatchupScore, rate: Double): MoveMatchupScore {
        val acts = rate.coerceIn(0.0, 1.0)
        if (acts >= 1.0) return score
        val survival = score.survivalByUses
        val damage = score.damageWhileStandingByUses
        fun binomial(n: Int, k: Int): Double {
            var ways = 1.0
            for (i in 1..k) ways = ways * (n - k + i) / i
            return ways * Math.pow(acts, k.toDouble()) * Math.pow(1.0 - acts, (n - k).toDouble())
        }
        val standing = survival.indices.map { n -> (0..n).sumOf { k -> binomial(n, k) * survival[k] } }
        val dealt = survival.indices.map { n ->
            if (standing[n] <= 0.0) damage.getOrElse(n) { 0.0 }
            else (0..n).sumOf { k -> binomial(n, k) * survival[k] * damage.getOrElse(k) { 0.0 } } / standing[n]
        }
        return score.copy(
            survivalByUses = standing,
            damageWhileStandingByUses = dealt,
            expectedHitsToKnockout = if (acts <= 0.0) Double.POSITIVE_INFINITY else score.expectedHitsToKnockout / acts,
            score = score.score * acts,
        )
    }

    /**
     * End-of-turn damage on its own ([jbro.cobblemon.mcc.betterai.mechanics.LocalResidual]), for a side facing no attack:
     * [alive] after `n` turns and the HP [taken] by then. Without it a badly poisoned Pokemon the other could not hurt read
     * the exchange as a stand-off however soon the poison emptied its bar (open gap, 2026-10-20).
     */
    private class Decay(private val alive: DoubleArray, private val taken: DoubleArray) {
        companion object {
            fun alive(decay: Decay?, n: Int): Double = decay?.let { it.alive[n.coerceIn(0, it.alive.lastIndex)] } ?: 1.0
            fun taken(decay: Decay?, n: Int): Double = decay?.let { it.taken[n.coerceIn(0, it.taken.lastIndex)] } ?: 0.0
            /** Falling to the end of turn [n] exactly; nothing past the window. */
            fun falls(decay: Decay?, n: Int): Double =
                if (decay == null || n > decay.alive.lastIndex) 0.0 else (decay.alive[n - 1] - decay.alive[n]).coerceAtLeast(0.0)
        }
    }

    private fun decay(target: BattlePokemonStateView, state: jbro.cobblemon.mcc.internal.ai.BattleStateView): Decay? {
        val hp = target.hpFraction
        if (hp <= 0.0) return null
        val schedule = jbro.cobblemon.mcc.betterai.mechanics.LocalResidual.schedule(state, target, MAXIMUM_USES) ?: return null
        val lost = DoubleArray(MAXIMUM_USES + 1).also { for (n in 1 until it.size) it[n] = it[n - 1] + schedule[n - 1] }
        if (lost.none { it >= hp }) return null
        return Decay(DoubleArray(lost.size) { n -> if (lost[n] >= hp) 0.0 else 1.0 }, DoubleArray(lost.size) { n -> lost[n].coerceIn(0.0, hp) })
    }

    /** The chance [score]'s knockout lands exactly on use [n]; the last index takes everything later. */
    private fun knockoutAt(score: MoveMatchupScore?, n: Int): Double {
        val survival = score?.survivalByUses ?: return 0.0
        if (n > survival.lastIndex) return 0.0
        return (survival[n - 1] - survival[n]).coerceAtLeast(0.0)
    }

    internal fun withTrickRoomToggled(position: BattleDecisionContext): BattleDecisionContext {
        val state = position.state
        val field = state.field
        val rooms = if (LocalPublicFieldMechanics.trickRoomActive(state)) {
            field.roomEffects.filterNot { PublicIds.canonical(it.effectId) == TRICK_ROOM }
        } else {
            field.roomEffects + BattleTimedEffectView(TRICK_ROOM, TRICK_ROOM_TURNS)
        }
        val toggled = state.derive(
            field = BattleFieldStateView(field.weather, field.terrain, rooms, field.globalEffects, field.sideConditions),
        )
        return position.copy(state = toggled)
    }

    private fun living(state: BattleStateView, side: BattleSide): List<BattlePokemonStateView> =
        state.pokemon.filter { it.side == side && !it.fainted && it.hpFraction > 0.0 }

    /** Uses the knockout profile follows; past it a move counts as "later or never". */
    internal const val MAXIMUM_USES = 6
    private const val PARALYSIS = "par"
    /** The share of turns a paralysed Pokemon acts (Showdown: fully paralysed one turn in four). */
    internal const val PARALYSIS_ACTS = 0.75
    private const val MAXIMUM_SETUP_USES = 2
    /**
     * Moves that fail when their user is hit before it moves. An exchange has both sides attacking every
     * turn, so they never land in one; their damage still counts in the move scores.
     */
    private val FAILS_WHEN_HIT_FIRST = setOf("focuspunch", "shelltrap")
    /** Leading moves per side played against each other in [bestExchange]. */
    private const val EXCHANGE_MOVES = 4
    private const val MINIMUM_STANDING_HP = 0.01
    private const val ROLLS = 16
    private const val TRICK_ROOM = "trickroom"
    private const val TRICK_ROOM_TURNS = 5

}
