package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalStatStageMarginalEvaluator
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectView
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.UUID

/**
 * What an attack that lowers its user's own stats (Draco Meteor, Close Combat, Superpower, Overheat) risks: the
 * opponent gets to play against the weakened user. The risk is the user's one-on-one score lost to the drop against
 * whoever faces it next, weighted by how likely each is: the target if it stands, the Pokemon the opponent is predicted
 * to switch in, or the opponent's best answer from its bench when the attack knocks out. Scored on the Pokemon in front
 * alone, a Draco Meteor that knocks Rampardos out 90% of the time paid 35 for a drop that only mattered against
 * Rampardos (2026-10-18).
 *
 * The drop only costs anything if the user is still standing next turn: the foe's hit before or after the attack may
 * knock it out first. With the user moving first (priority, then speed) and the attack knocking the foe out with k, and
 * the foe's hit knocking the user out with f:
 * `first x (k x lost(replacement) + (1 - k)(1 - f') x lost(foe)) + (1 - first)(1 - f) x (k x lost(replacement) + (1 - k) x lost(foe))`
 * with f' the foe's knockout on the lowered stats, both at the chance the foe moves at all (paralysis, sleep). At the same
 * priority, Speed decides. A Pokemon switching in does not hit this turn. Doubles: the drop counts at the chance the user
 * stands through the turn, both foes' hits read together. A Sceptile at 26% in front of a faster Alakazam paid 169 for a Leaf
 * Storm drop it would never live to feel (2026-10-19).
 *
 * The ranking already holds the drop as [LocalStatStageMarginalEvaluator.selfChangeBoard] priced it (the attack
 * pressure against the Pokemon in front); the adjustment swaps that value for this one. Read from the move data; no
 * move is named. No search: the drop is certain, so the board after it is one board.
 */
internal object LocalSelfDropRisk {
    data class Reading(
        /** The one-on-one score lost against whoever faces the user next, at the move's accuracy. */
        val risk: Double,
        /** What the ranking already charged for the same stat changes. */
        val charged: Double,
        val adjustment: Double,
    )

    /** An attack whose own stat changes include a drop. */
    fun prices(move: BattleActionCandidate): Boolean {
        if (move.kind != BattleActionKind.USE_MOVE || move.mechanic?.transformedMoveId != null) return false
        val details = move.moveDetails ?: return false
        if (details.damageCategory == BattleMoveDamageCategory.STATUS) return false
        return ownChanges(move).any { effect -> effect.statStages.values.any { it < 0 } }
    }

    fun adjustments(
        candidates: List<BattleActionCandidate>,
        ranked: BattleDecisionContext,
        board: BattleDecisionContext,
        scores: MatchupScores,
        intents: List<OpponentIntent>,
        tuning: LocalDecisionTuning,
    ): Map<String, Reading> {
        val cache = LocalProjectedActionCalculationCache()
        val readings = HashMap<String, Reading?>()
        return candidates.mapNotNull { candidate ->
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            val read = parts.filter(::prices).mapNotNull { part ->
                readings.getOrPut(part.actionId) { read(part, ranked, board, scores, intents, tuning, cache) }
            }
            if (read.isEmpty()) null else candidate.actionId to Reading(
                read.sumOf { it.risk }, read.sumOf { it.charged }, read.sumOf { it.adjustment },
            )
        }.toMap()
    }

    private fun read(
        move: BattleActionCandidate,
        ranked: BattleDecisionContext,
        board: BattleDecisionContext,
        scores: MatchupScores,
        intents: List<OpponentIntent>,
        tuning: LocalDecisionTuning,
        cache: LocalProjectedActionCalculationCache,
    ): Reading? {
        val state = board.state
        val user = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == move.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return null
        val moveId = PublicIds.canonical(move.moveId ?: return null)
        val effects = ownChanges(move)
        val accuracy = LocalPublicAccuracy.probability(move, ranked, BattleSide.ALLY).coerceIn(0.0, 1.0)
        // What the ranking charged: the same evaluator the scorer ran, on the board it ran it on.
        val rankedUser = ranked.state.pokemon.firstOrNull { it.battlePokemonId == user.battlePokemonId } ?: return null
        val charged = LocalStatStageMarginalEvaluator.selfChangeBoard(rankedUser, effects, ranked, cache, tuning) *
            tuning.boardToScore * accuracy

        val foes = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && standing(it) }
        if (foes.isEmpty()) return null
        val bench = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot == null && standing(it) }
        val targeted = targets(move, foes)
        var after = state
        var risk = 0.0
        effects.forEach { effect ->
            val holder = after.pokemon.firstOrNull { it.battlePokemonId == user.battlePokemonId } ?: return@forEach
            val changed = LocalStatStageMarginalEvaluator.applyStages(after, setOf(user.battlePokemonId),
                LocalStatStageChange.reshape(after, holder, user.battlePokemonId, effect.statStages))
            val probability = (effect.probability ?: 1.0).coerceIn(0.0, 1.0)
            risk += probability * foes.map { foe ->
                // Doubles: whether the user stands through the turn, the foes' hits read together on the lowered board
                // (leaving out the hit of a foe that switches instead).
                val standing: ((UUID?) -> Double)? = if (foes.size < 2) null else { excluded ->
                    standingThroughTurn(board, after, changed, user.battlePokemonId, foes.filter { it.battlePokemonId != excluded },
                        move, moveId, targeted, scores, cache)
                }
                slotRisk(board, after, changed, user.battlePokemonId, foe, foe in targeted, move, moveId, accuracy, bench, scores, intents, cache, standing)
            }.average()
            if (probability >= CERTAIN_PROBABILITY) after = changed
        }
        val priced = -risk * tuning.knockoutMaterialScore * accuracy
        return Reading(risk * accuracy, charged, priced - charged)
    }

    /**
     * The user's score lost against whoever stands in [foe]'s slot next turn: the foe itself, the Pokemon it is predicted
     * to switch to, or, when [targeted] and the attack knocks it out, the bench Pokemon the user does worst against.
     */
    private fun slotRisk(
        board: BattleDecisionContext,
        before: BattleStateView,
        after: BattleStateView,
        userId: UUID,
        foe: BattlePokemonStateView,
        targeted: Boolean,
        move: BattleActionCandidate,
        moveId: String,
        accuracy: Double,
        bench: List<BattlePokemonStateView>,
        scores: MatchupScores,
        intents: List<OpponentIntent>,
        cache: LocalProjectedActionCalculationCache,
        /** Doubles: the user's chance to stand through the turn, all foes together but the one excluded; null in singles. */
        standing: ((UUID?) -> Double)? = null,
    ): Double {
        val beforeScores = HashMap<UUID, Double?>()
        val afterScores = HashMap<UUID, Double?>()
        fun score(state: BattleStateView, memo: HashMap<UUID, Double?>, facer: BattlePokemonStateView) = memo.getOrPut(facer.battlePokemonId) {
            val position = board.copy(state = state)
            val subject = state.pokemon.firstOrNull { it.battlePokemonId == userId } ?: return@getOrPut null
            val faced = LocalMatchupPosition.face(position, subject, facer, cache) ?: return@getOrPut null
            LocalMatchupScoreCalculator.pairMatchup(faced, userId, facer.battlePokemonId, MatchupSpeedField.CURRENT, cache)?.score
        }
        fun lost(facer: BattlePokemonStateView): Double {
            val was = score(before, beforeScores, facer) ?: return 0.0
            val now = score(after, afterScores, facer) ?: return 0.0
            return was - now
        }
        // After a knockout the opponent sends in its best answer to the weakened user, from the bench without the
        // Pokemon that just fell.
        fun replacement(fallen: BattlePokemonStateView) = bench.filter { it.battlePokemonId != fallen.battlePokemonId }
            .minByOrNull { score(after, afterScores, it) ?: Double.MAX_VALUE }
        // The drop happens only when the attack lands, and the risk is weighed by that chance once (in [read]): the
        // knockout here is the chance given a hit. The table's chance counts the miss too, which squared the accuracy.
        fun knockout(receiver: BattlePokemonStateView) = if (!targeted || accuracy <= 0.0) 0.0
            else ((scores.moves(userId, receiver.battlePokemonId).firstOrNull { it.moveId == moveId }?.knockoutChanceWithin(1) ?: 0.0) / accuracy)
                .coerceIn(0.0, 1.0)
        val switches = intents.firstOrNull { it.pokemonId == foe.battlePokemonId }?.options.orEmpty()
            .filter { it.kind == IntentKind.SWITCH && it.switchInId != null }
            .groupBy { it.switchInId!! }.mapValues { (_, options) -> options.sumOf { it.probability } }
        val stay = (1.0 - switches.values.sum()).coerceIn(0.0, 1.0)
        val receivers = listOf(foe to stay) + switches.mapNotNull { (id, chance) ->
            bench.firstOrNull { it.battlePokemonId == id }?.let { it to chance }
        }
        return receivers.sumOf { (receiver, chance) ->
            val knockout = knockout(receiver)
            val next = if (knockout > 0.0) replacement(receiver) else null
            val afterKnockout = if (next == null) 0.0 else knockout * lost(next)
            if (receiver != foe) return@sumOf chance * (standing?.invoke(foe.battlePokemonId) ?: 1.0) *
                ((1.0 - knockout) * lost(receiver) + afterKnockout)
            if (standing != null) return@sumOf chance * standing(null) * ((1.0 - knockout) * lost(receiver) + afterKnockout)
            // The foe standing in front hits this turn: before the attack, or after it on the lowered stats unless the attack
            // knocked it out. It uses the move likeliest to knock the user out, each move with its own order against the
            // attack (priority, Speed, Quick Claw and the like) and its own knockouts before and after the drop, read from
            // the move table (the exchange's is thinned for flinches and heals), at the chance the foe moves at all.
            val foeActs = LocalKnockoutWorth.actsThisTurn(foe)
            val beforeHits = threats(board, before, userId, foe, cache)
            val afterHits = threats(board, after, userId, foe, cache).associateBy { it.score.moveId }
            class Threat(val first: Double, val fallsBefore: Double, val fallsAfter: Double)
            val threat = beforeHits.map { hit ->
                val lowered = afterHits[hit.score.moveId]?.score ?: hit.score
                Threat(LocalPublicTurnOrder.actsFirstProbability(before, BattleSide.ALLY, move, BattleSide.OPPONENT, hit.action) ?: 0.5,
                    foeActs * hit.score.knockoutChanceWithin(1), foeActs * lowered.knockoutChanceWithin(1))
            }.maxByOrNull { it.first * (1.0 - knockout) * it.fallsAfter + (1.0 - it.first) * it.fallsBefore }
                ?: Threat(0.5, 0.0, 0.0)
            chance * (threat.first * (afterKnockout + (1.0 - knockout) * (1.0 - threat.fallsAfter) * lost(receiver)) +
                (1.0 - threat.first) * (1.0 - threat.fallsBefore) * (afterKnockout + (1.0 - knockout) * lost(receiver)))
        }
    }

    /**
     * The chance [user] is still standing at the end of the turn, [foes]' likeliest knockouts read together: each at the
     * chance it moves and aims at the user (one in as many of this side's Pokemon in front), two hits that each leave it
     * standing counted together ([LocalDynamaxGuard.knockout]).
     */
    private fun standingThroughTurn(
        board: BattleDecisionContext,
        before: BattleStateView,
        after: BattleStateView,
        userId: UUID,
        foes: List<BattlePokemonStateView>,
        move: BattleActionCandidate,
        moveId: String,
        targeted: Set<BattlePokemonStateView>,
        scores: MatchupScores,
        cache: LocalProjectedActionCalculationCache,
    ): Double {
        val user = before.pokemon.firstOrNull { it.battlePokemonId == userId } ?: return 1.0
        val allies = before.pokemon.count { it.side == BattleSide.ALLY && it.activeSlot != null && standing(it) }.coerceAtLeast(1)
        val hits = foes.mapNotNull { foe ->
            val beforeHit = threats(board, before, userId, foe, cache).maxByOrNull { it.score.knockoutChanceWithin(1) } ?: return@mapNotNull null
            val beforeFirst = LocalPublicTurnOrder.actsFirstProbability(before, BattleSide.ALLY, move, BattleSide.OPPONENT, beforeHit.action) ?: 0.5
            // Moving after the attack, it hits the lowered user with its best counter there, unless the attack knocked it
            // out; that counter's own order and aim are read with it.
            val hit = if (beforeFirst >= 0.5) threats(board, after, userId, foe, cache).maxByOrNull { it.score.knockoutChanceWithin(1) } ?: beforeHit
                else beforeHit
            val first = if (hit === beforeHit) beforeFirst
                else LocalPublicTurnOrder.actsFirstProbability(before, BattleSide.ALLY, move, BattleSide.OPPONENT, hit.action) ?: 0.5
            val lowered = if (hit === beforeHit) null else hit.score
            // Given the attack landed on this foe (this board is the drop's, so it did): the knockout table counts the miss
            // at the accuracy against this foe.
            val own = scores.moves(userId, foe.battlePokemonId).firstOrNull { it.moveId == moveId }
            val fells = if (foe in targeted && own != null && own.accuracy > 0.0) (own.knockoutChanceWithin(1) / own.accuracy).coerceIn(0.0, 1.0) else 0.0
            val incoming = LocalKnockoutWorth.actsThisTurn(foe) * ((1.0 - first) + first * (1.0 - fells))
            // A spread hit lands on the user whatever it aims at; a single one at one of this side's Pokemon in front.
            val aim = if (hit.action.moveDetails?.targetPattern in SPREAD) 1.0 else 1.0 / allies
            LocalDynamaxGuard.IncomingHit(lowered ?: hit.score, incoming, aim)
        }
        if (hits.isEmpty()) return 1.0
        return 1.0 - LocalDynamaxGuard.knockout(hits, user.hpFraction).coerceIn(0.0, 1.0)
    }

    /** [foe]'s attacks on the user on [state] (before or after the drop), each with its action. */
    private fun threats(
        board: BattleDecisionContext,
        state: BattleStateView,
        userId: UUID,
        foe: BattlePokemonStateView,
        cache: LocalProjectedActionCalculationCache,
    ): List<LocalMatchupScoreCalculator.ScoredMove> {
        val user = state.pokemon.firstOrNull { it.battlePokemonId == userId } ?: return emptyList()
        val faced = LocalMatchupPosition.face(board.copy(state = state), user, foe, cache) ?: return emptyList()
        return LocalMatchupScoreCalculator.moveMatchups(faced, foe.battlePokemonId, userId, cache)
    }

    private fun targets(move: BattleActionCandidate, foes: List<BattlePokemonStateView>): Set<BattlePokemonStateView> {
        if (move.moveDetails?.targetPattern in SPREAD) return foes.toSet()
        val slots = move.targets.filter { it.side == BattleSide.OPPONENT }.map { it.slot }.toSet()
        return if (slots.isEmpty()) foes.take(1).toSet() else foes.filter { it.activeSlot in slots }.toSet()
    }

    private fun ownChanges(move: BattleActionCandidate): List<BattleMoveEffectView> =
        move.moveDetails?.effects?.effects.orEmpty().filter {
            it.kind == BattleMoveEffectKind.STAT_STAGE && it.target == BattleMoveEffectTarget.USER && it.statStages.isNotEmpty()
        }

    private fun standing(pokemon: BattlePokemonStateView) = !pokemon.fainted && pokemon.hpFraction > 0.0

    private const val CERTAIN_PROBABILITY = 0.999_999
    private val SPREAD = setOf(BattleMoveTargetPattern.ALL_OPPONENTS, BattleMoveTargetPattern.ALL_ADJACENT)
}
