package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.internal.ai.*

/** Two representative incoming intervals per slot replaces the Cartesian product of opponent choices. */
internal object LocalSimpleActingChance {
    internal data class Interval(val lands: Double, val low: Double, val high: Double, val ko: Double, val combines: Boolean = true)
    private data class Choice(val probability: Double, val interval: Interval)

    fun of(context: BattleDecisionContext, board: BattleDecisionContext, part: BattleActionCandidate,
        ignoring: Set<UUID>, intents: List<OpponentIntent>?, scores: MatchupScores, trace: String): Double {
        val state = board.state
        val user = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && !it.fainted && it.hpFraction > 0 } ?: return 1.0
        val allies = state.pokemon.count { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0 }.coerceAtLeast(1)
        val groups = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0 && it.battlePokemonId !in ignoring }.map { foe ->
            // This table was already built for the decision. No moveMatchups, pairMatchup, or KO profile request.
            val moves = scores.moves(foe.battlePokemonId, user.battlePokemonId)
            val options = intents?.firstOrNull { it.pokemonId == foe.battlePokemonId }?.options?.takeIf {
                it.isNotEmpty() && it.all { option -> option.probability.isFinite() && option.probability >= 0 } && it.sumOf { option -> option.probability } > 0
            }
            val worst = moves.maxByOrNull { it.knockoutChanceWithin(1) }
            val alternatives = options ?: listOf(IntentOption(IntentKind.ATTACK, worst?.moveId, null, null, 0.0, 1.0))
            val total = alternatives.sumOf { it.probability }
            fun representative(max: Boolean): List<Interval> {
                val choices = alternatives.filter { it.probability > 0 }.map { option ->
                    val probability = option.probability / total
                    val move = if (option.kind != IntentKind.ATTACK) null else (moves.firstOrNull {
                        PublicIds.canonical(it.moveId) == option.moveId?.let(PublicIds::canonical)
                    } ?: worst)
                    if (move == null) return@map Choice(probability, Interval(0.0, 0.0, 0.0, 0.0))
                    val hit = LocalSimpleMatchup.hit(context, board, move, max)
                    val first = if (hit.action.moveDetails != null)
                        LocalPublicTurnOrder.actsFirstProbability(state, user.side, part, foe.side, hit.action) ?: .5
                    else {
                        val priority = LocalPublicTurnOrder.effectivePriority(state, user.side, part)
                        when { priority > hit.move.priority -> 1.0; priority < hit.move.priority -> 0.0
                            else -> LocalPublicTurnOrder.speedOrderProbability(state, user, foe) ?: .5 }
                    }
                    val spread = hit.action.moveDetails?.targetPattern in setOf(BattleMoveTargetPattern.ALL_OPPONENTS,
                        BattleMoveTargetPattern.ALL_ADJACENT, BattleMoveTargetPattern.ALL_ACTIVE)
                    val aim = if (spread) 1.0 else option.targetId?.let { if (it == user.battlePokemonId) 1.0 else 0.0 } ?: (1.0 / allies)
                    val pending = hit.action.moveDetails?.effects?.requirements.orEmpty().any { it.kind == BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE }
                    val works = !(pending && part.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS)
                    val before = if (works) LocalKnockoutWorth.actsThisTurn(foe, state) * (1 - first) * aim else 0.0
                    val lands = before * hit.move.accuracy
                    val ko = (before * hit.move.knockoutChanceWithin(1)).coerceIn(0.0, lands)
                    Choice(probability, Interval(lands, hit.move.minimumDamageFraction, hit.move.maximumDamageFraction, ko,
                        PublicIds.canonical(move.moveId) !in PublicBattleTacticalCalculator.TARGET_HP_SET_MOVES))
                }
                val totalLands = choices.sumOf { it.probability * it.interval.lands }
                val center = if (totalLands > 0) choices.sumOf {
                    it.probability * it.interval.lands * (it.interval.low + it.interval.high) / 2
                } / totalLands else 0.0
                val (low, high) = choices.partition { (it.interval.low + it.interval.high) / 2 <= center }
                val buckets = listOf(low, high).filter { bucket -> bucket.sumOf { it.probability * it.interval.lands } > 0 }
                    .ifEmpty { listOf(choices) }
                return buckets.mapIndexed { bin, bucket ->
                    val lands = bucket.sumOf { it.probability * it.interval.lands }
                    fun mean(value: (Interval) -> Double) = if (lands <= 0) 0.0 else bucket.sumOf { it.probability * it.interval.lands * value(it.interval) } / lands
                    val result = Interval(lands, mean { it.low }, mean { it.high }, bucket.sumOf { it.probability * it.interval.ko },
                        bucket.none { it.probability * it.interval.lands > 0 && !it.interval.combines })
                    LocalDecisionInternals.record("simpleActingSlot:$trace:${foe.activeSlot}:$max:$bin", mapOf(
                        "choices" to bucket.size.toDouble(), "lands" to lands, "min" to result.low,
                        "max" to result.high, "ko1" to result.ko, "combines" to LocalDecisionInternals.flag(result.combines),
                        "center" to center, "bins" to buckets.size.toDouble(),
                        "weightedLow" to bucket.sumOf { it.probability * it.interval.lands * it.interval.low },
                        "weightedHigh" to bucket.sumOf { it.probability * it.interval.lands * it.interval.high },
                        "missingMoves" to LocalDecisionInternals.flag(moves.isEmpty()),
                        "fallbackIntent" to LocalDecisionInternals.flag(options == null)))
                    result
                }
            }
            val plain = representative(false)
            plain to if (LocalFoeDynamaxThreat.possible(context, foe)) representative(true) else null
        }
        val plain = groups.map { it.first }
        val maxRisk = groups.indices.mapNotNull { changed -> groups[changed].second?.let { max ->
            groupedKnockout(groups.mapIndexed { slot, pair -> if (slot == changed) max else pair.first }, user.hpFraction)
        } }.maxOrNull() ?: 0.0
        val ko = maxOf(groupedKnockout(plain, user.hpFraction), maxRisk)
        val possible = LocalConsecutiveMoveRules.canAttempt(part, state, user) && !LocalTruant.loafs(state, user)
        val acts = if (possible) 1.0 - ko else 0.0
        LocalHpMatchupAudit.simpleActingModel()
        LocalDecisionInternals.record("simpleActing:$trace", mapOf("knockout" to ko, "acts" to acts,
            "possible" to LocalDecisionInternals.flag(possible), "slots" to groups.size.toDouble(), "maxRisk" to maxRisk))
        return acts
    }

    /** At most four landed representative pairs; misses and one-sided hits are closed-form terms. */
    internal fun groupedKnockout(groups: List<List<Interval>>, hp: Double): Double {
        if (groups.isEmpty()) return 0.0
        if (groups.size != 2) return groups.maxOf { group -> group.sumOf { it.ko } }.coerceIn(0.0, 1.0)
        val (a, b) = groups
        val la = a.sumOf { it.lands }.coerceIn(0.0, 1.0)
        val lb = b.sumOf { it.lands }.coerceIn(0.0, 1.0)
        var risk = a.sumOf { it.ko } * (1 - lb) + b.sumOf { it.ko } * (1 - la)
        for (first in a) for (second in b) {
            if (first.lands <= 0 || second.lands <= 0) continue
            LocalHpMatchupAudit.simpleActingPair()
            risk += first.lands * second.lands * knockout(listOf(
                first.copy(lands = 1.0, ko = first.ko / first.lands),
                second.copy(lands = 1.0, ko = second.ko / second.lands)), hp)
        }
        return risk.coerceIn(0.0, 1.0)
    }

    internal fun knockout(hits: List<Interval>, hp: Double): Double {
        if (hits.isEmpty()) return 0.0
        if (hits.size != 2 || hits.any { !it.combines }) return hits.maxOf { it.ko }
        val (a, b) = hits
        val q = if (a.lands > 0) (a.ko / a.lands).coerceIn(0.0, 1.0) else 0.0
        val r = if (b.lands > 0) (b.ko / b.lands).coerceIn(0.0, 1.0) else 0.0
        val bothStanding = minOf(1 - q, 1 - r, LocalSimpleMatchup.sumBelow(a.low, a.high, b.low, b.high, hp))
        val survival = (1 - a.lands) * (1 - b.lands) + a.lands * (1 - b.lands) * (1 - q) +
            (1 - a.lands) * b.lands * (1 - r) + a.lands * b.lands * bothStanding
        return maxOf(1 - survival, a.ko, b.ko).coerceIn(0.0, 1.0)
    }
}
