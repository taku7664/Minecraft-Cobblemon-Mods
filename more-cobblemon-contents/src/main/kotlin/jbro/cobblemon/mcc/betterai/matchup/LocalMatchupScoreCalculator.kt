package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.evaluation.LocalStatStageMarginalEvaluator
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
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
    ): MatchupScores {
        val allies = living(context.state, BattleSide.ALLY)
        val opponents = living(context.state, BattleSide.OPPONENT)
        val moves = linkedMapOf<Pair<UUID, UUID>, List<MoveMatchupScore>>()
        val pokemon = linkedMapOf<Triple<UUID, UUID, MatchupSpeedField>, PokemonMatchupScore>()
        for (ally in allies) for (opponent in opponents) {
            if (!shouldContinue()) return MatchupScores(moves, pokemon, complete = false)
            val position = LocalMatchupPosition.face(context, ally, opponent, cache) ?: continue
            val allyMoves = moveMatchups(position, ally.battlePokemonId, opponent.battlePokemonId, cache)
            val opponentMoves = moveMatchups(position, opponent.battlePokemonId, ally.battlePokemonId, cache)
            moves[ally.battlePokemonId to opponent.battlePokemonId] = allyMoves.map { it.score }
            moves[opponent.battlePokemonId to ally.battlePokemonId] = opponentMoves.map { it.score }
            for (field in MatchupSpeedField.entries) {
                val faced = if (field == MatchupSpeedField.CURRENT) position else withTrickRoomToggled(position)
                bestExchange(faced, ally.battlePokemonId, opponent.battlePokemonId, allyMoves, opponentMoves, field)?.let {
                    pokemon[Triple(ally.battlePokemonId, opponent.battlePokemonId, field)] = it
                }
            }
        }
        val pairs = MatchupScores(moves, pokemon, complete = true)
        val aces = linkedMapOf<UUID, AceScore>()
        for (subject in allies + opponents) {
            if (!shouldContinue()) return MatchupScores(moves, pokemon, complete = false, aces = aces)
            val foes = if (subject.side == BattleSide.ALLY) opponents else allies
            aceScore(context, subject, foes, pairs, cache)?.let { aces[subject.battlePokemonId] = it }
        }
        val withAces = MatchupScores(moves, pokemon, complete = true, aces = aces)
        val antiAces = linkedMapOf<UUID, AntiAceScore>()
        for ((side, stoppers) in listOf(BattleSide.OPPONENT to allies, BattleSide.ALLY to opponents)) {
            val ace = withAces.ace(side, context.state) ?: continue
            val acePokemon = context.state.pokemon.first { it.battlePokemonId == ace.subjectId }
            for (subject in stoppers) {
                if (!shouldContinue()) return MatchupScores(moves, pokemon, complete = false, aces = aces, antiAces = antiAces)
                LocalAntiAceScoreCalculator.score(context, subject, ace, acePokemon, pairs, cache)
                    ?.let { antiAces[subject.battlePokemonId] = it }
            }
        }
        val statusMoves = linkedMapOf<Pair<UUID, UUID>, List<StatusMoveMatchupScore>>()
        val switchIns = linkedMapOf<Triple<UUID, UUID, UUID>, SwitchInScore>()
        fun partial() = MatchupScores(moves, pokemon, complete = false, aces = aces, antiAces = antiAces,
            statusMovesByPair = statusMoves, switchInsByKey = switchIns)
        val allyField = allies.filter { it.activeSlot != null }
        val opponentField = opponents.filter { it.activeSlot != null }
        for (ally in allyField) for (opponent in opponentField) {
            if (!shouldContinue()) return partial()
            statusMoves[ally.battlePokemonId to opponent.battlePokemonId] =
                LocalTurnCostScoreCalculator.statusMoves(context, ally, opponent, withAces, cache)
            statusMoves[opponent.battlePokemonId to ally.battlePokemonId] =
                LocalTurnCostScoreCalculator.statusMoves(context, opponent, ally, withAces, cache)
        }
        for (incoming in allies.filter { it.activeSlot == null }) for (replaced in allyField) for (opponent in opponentField) {
            if (!shouldContinue()) return partial()
            LocalTurnCostScoreCalculator.switchIn(context, incoming, replaced, opponent, withAces, cache)
                ?.let { switchIns[Triple(incoming.battlePokemonId, opponent.battlePokemonId, replaced.battlePokemonId)] = it }
        }
        val preserves = LocalTurnCostScoreCalculator.preserves(allies, opponents, withAces) +
            LocalTurnCostScoreCalculator.preserves(opponents, allies, withAces)
        return MatchupScores(moves, pokemon, complete = true, aces = aces, antiAces = antiAces,
            statusMovesByPair = statusMoves, switchInsByKey = switchIns, preserves = preserves)
    }

    /**
     * [subject]'s sweep against [foes], as it stands and after one or two uses of each of its stat-raising
     * status moves. A boosted reading re-scores both sides' attacks, since a boost changes damage dealt,
     * damage taken and turn order alike.
     */
    private fun aceScore(
        context: BattleDecisionContext,
        subject: BattlePokemonStateView,
        foes: List<BattlePokemonStateView>,
        pairs: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): AceScore? {
        val matchups = foes.mapNotNull { pairs.pokemon(subject.battlePokemonId, it.battlePokemonId) }
        if (matchups.isEmpty()) return null
        val natural = matchups.map { it.winProbability }.average()
        var best = Boost(null, 0, 0.0, 1.0, emptyMap(), emptyMap())
        for ((moveId, stages) in setupMoves(context, subject)) for (uses in 1..MAXIMUM_SETUP_USES) {
            // Each foe attacks through the setup turns; the boosted exchange starts from what is left.
            val outcomes = foes.mapNotNull { foe ->
                val base = pairs.pokemon(subject.battlePokemonId, foe.battlePokemonId) ?: return@mapNotNull null
                val survives = base.opponentMove?.survivalByUses?.getOrNull(uses) ?: 1.0
                val taken = base.opponentMove?.damageWhileStandingByUses?.getOrNull(uses) ?: 0.0
                val position = LocalMatchupPosition.face(context, subject, foe, cache) ?: return@mapNotNull null
                val raised = LocalStatStageMarginalEvaluator.applyStages(position.state,
                    setOf(subject.battlePokemonId), stages.mapValues { it.value * uses })
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
        return AceScore(
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
        moveMatchups(position, opponentId, subjectId, cache), speedField)

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
    ): PokemonMatchupScore? {
        fun exchangeable(moves: List<ScoredMove>) = moves.filterNot { it.score.moveId in FAILS_WHEN_HIT_FIRST }
        val mine = exchangeable(subjectMoves).take(EXCHANGE_MOVES).ifEmpty { listOf(null) }
        val theirs = exchangeable(opponentMoves).take(EXCHANGE_MOVES).ifEmpty { listOf(null) }
        return mine.mapNotNull { subjectMove ->
            theirs.mapNotNull { opponentMove ->
                pokemonMatchup(position, subjectId, opponentId, subjectMove, opponentMove, speedField)
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
            .filter { (_, details) -> details.damageCategory == BattleMoveDamageCategory.STATUS && BattleStatusMoveCategories.isPureSelfSetup(details) }
            .associate { (moveId, details) ->
                PublicIds.canonical(moveId) to details.effects!!.effects.fold(mutableMapOf<String, Int>()) { stages, effect ->
                    effect.statStages.forEach { (stat, delta) -> stages.merge(stat, delta, Int::plus) }
                    stages
                }
            }
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
            .mapNotNull { action ->
                val calculated = cache.getOrCalculate(state, side, action, position.publicActionCatalog) {
                    PublicBattleTacticalCalculator.calculate(position.copy(state = state, candidates = listOf(action)), side)
                }
                val candidate = calculated.candidates.single()
                val primary = LocalPublicMoveTargets.resolve(candidate, calculated, side).firstOrNull()
                if (primary?.battlePokemonId != targetId) return@mapNotNull null
                val rolls = if (LocalPublicMechanicsKernel.projectMove(candidate, calculated, side).publiclyNullified) {
                    List(ROLLS) { 0.0 }
                } else {
                    PublicBattleTacticalCalculator.conservativeDamageRollFractions(candidate, calculated, side)
                } ?: return@mapNotNull null
                val accuracy = LocalPublicAccuracy.probability(candidate, calculated, side).coerceIn(0.0, 1.0)
                ScoredMove(moveScore(userId, PublicIds.canonical(candidate.moveId ?: action.actionId), targetId,
                    rolls, accuracy, target.hpFraction), action)
            }
            .groupBy { it.score.moveId }.values.map { same -> same.minWith(BEST_FIRST) }
            .sortedWith(BEST_FIRST)
    }

    private val BEST_FIRST = compareBy<ScoredMove> { it.score.expectedHitsToKnockout }
        .thenByDescending { it.score.accuracy }
        .thenBy { it.score.moveId }

    internal fun moveScore(
        userId: UUID,
        moveId: String,
        targetId: UUID,
        rolls: List<Double>,
        accuracy: Double,
        targetHp: Double,
    ): MoveMatchupScore {
        val profile = LocalKnockoutProfile.of(rolls, accuracy, targetHp, MAXIMUM_USES)
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
     */
    internal fun pokemonMatchup(
        position: BattleDecisionContext,
        subjectId: UUID,
        opponentId: UUID,
        subjectMove: ScoredMove?,
        opponentMove: ScoredMove?,
        speedField: MatchupSpeedField,
    ): PokemonMatchupScore? {
        val state = position.state
        val subject = state.pokemon.firstOrNull { it.battlePokemonId == subjectId && it.activeSlot != null } ?: return null
        val opponent = state.pokemon.firstOrNull { it.battlePokemonId == opponentId && it.activeSlot != null } ?: return null
        val first = if (subjectMove != null && opponentMove != null) {
            LocalPublicTurnOrder.actsFirstProbability(state, subject.side, subjectMove.action, opponent.side, opponentMove.action)
        } else null
        val subjectFirst = (first ?: LocalPublicTurnOrder.speedOrderProbability(state, subject, opponent) ?: 0.5).coerceIn(0.0, 1.0)
        val mine = subjectMove?.score
        val theirs = opponentMove?.score
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
                // Moving first it has taken n - 1 attacks, moving second n.
                val asFirst = subjectFirst * standing(theirs, n - 1)
                val asSecond = (1.0 - subjectFirst) * standing(theirs, n)
                win += subjectKnocksOutAt * (asFirst + asSecond)
                winHp += subjectKnocksOutAt * (asFirst * (subject.hpFraction - taken(theirs, n - 1)) +
                    asSecond * (subject.hpFraction - taken(theirs, n)))
            }
            val opponentKnocksOutAt = knockoutAt(theirs, n)
            if (opponentKnocksOutAt > 0.0) {
                val asFirst = (1.0 - subjectFirst) * standing(mine, n - 1)
                val asSecond = subjectFirst * standing(mine, n)
                loss += opponentKnocksOutAt * (asFirst + asSecond)
                lossHp += opponentKnocksOutAt * (asFirst * (opponent.hpFraction - taken(mine, n - 1)) +
                    asSecond * (opponent.hpFraction - taken(mine, n)))
            }
        }
        // Neither landing a knockout in the window is a stand-off: split it by who acts first.
        val stalemate = (1.0 - win - loss).coerceAtLeast(0.0)
        val total = win + loss + stalemate
        val winProbability = if (total <= 0.0) 0.5 else ((win + stalemate * subjectFirst) / total).coerceIn(0.0, 1.0)
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
