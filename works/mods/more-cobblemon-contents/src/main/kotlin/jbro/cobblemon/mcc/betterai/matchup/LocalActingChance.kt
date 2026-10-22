package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals
import jbro.cobblemon.mcc.betterai.mechanics.LocalMechanicActivationProjector
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveRequirementKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * The chance this candidate acts before being knocked out. Each opposing choice is weighted by the decision's
 * existing intent probabilities: switches and status choices contribute no incoming attack. An attack contributes
 * its own order, target and one-hit KO chance, with accuracy already included in the KO chance.
 *
 * With no usable intent, retain the conservative attack reading and identify that fallback in the trace. Potential
 * Dynamax remains conservative within each selected attack; there is no invented probability of using the mechanic.
 * Doubles combines the two selected hits before pricing their KO, and permits at most one opposing Dynamax.
 */
internal object LocalActingChance {
    fun of(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        @Suppress("UNUSED_PARAMETER") scores: MatchupScores,
        actorSlot: Int? = candidate.actorSlot,
        /** An attack's aimed foes may already be covered by its KO credit. */
        ignoring: Set<java.util.UUID> = emptySet(),
        intents: List<OpponentIntent>? = null,
        calculationCache: LocalProjectedActionCalculationCache = LocalProjectedActionCalculationCache(),
    ): Double {
        val board = context.copy(state = LocalMechanicActivationProjector.beforeMoves(context.state, BattleSide.ALLY, candidate))
        val state = board.state
        val part = if (candidate.kind == BattleActionKind.COMPOSITE)
            candidate.componentActions.firstOrNull { it.actorSlot == actorSlot } ?: return 1.0 else candidate
        val user = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return 1.0
        val allies = state.pokemon.count { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            .coerceAtLeast(1)
        val trace = "${candidate.actionId}:${part.actorSlot}" + if (ignoring.isEmpty()) "" else
            ":ignoring=" + ignoring.sortedBy { it.toString() }.joinToString(",")
        var fallbackFoes = 0
        var predictedFoes = 0
        val choices = state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 &&
                it.battlePokemonId !in ignoring
        }.mapNotNull { foe ->
            val faced = LocalMatchupPosition.face(board, user, foe, calculationCache) ?: return@mapNotNull null
            val plain = LocalMatchupScoreCalculator.moveMatchups(faced, foe.battlePokemonId, user.battlePokemonId, calculationCache)
            val max = LocalFoeDynamaxThreat.hits(context, board, user, foe, calculationCache)
            val worstPlain = plain.maxByOrNull { it.score.knockoutChanceWithin(1) }
            val worstMax = max.maxByOrNull { it.score.knockoutChanceWithin(1) }
            val options = intents?.firstOrNull { it.pokemonId == foe.battlePokemonId }?.options
            val usable = options?.takeIf { options ->
                options.isNotEmpty() && options.all { it.probability.isFinite() && it.probability >= 0.0 } &&
                    options.sumOf { it.probability } > 0.0
            }
            if (usable == null) fallbackFoes++ else predictedFoes++
            val mass = usable?.sumOf { it.probability } ?: 1.0
            val alternatives = usable?.filter { it.probability > 0.0 } ?: listOf(
                IntentOption(IntentKind.ATTACK, worstPlain?.score?.moveId, null, null, 0.0, 1.0))
            alternatives.mapIndexed { index, option ->
                val probability = option.probability / mass
                val moveId = option.moveId?.let(PublicIds::canonical)
                val attack = option.kind == IntentKind.ATTACK
                val matching = if (!attack) null else plain.firstOrNull { PublicIds.canonical(it.score.moveId) == moveId }
                val unresolved = attack && matching == null
                // An unresolvable predicted attack keeps its own probability, with the conservative hit as fallback.
                val normal = if (!attack) null else matching ?: worstPlain
                val dynamax = if (!attack) null else if (usable == null || unresolved) worstMax
                    else max.firstOrNull { PublicIds.canonical(it.score.moveId) == moveId }
                fun incoming(hit: LocalMatchupScoreCalculator.ScoredMove?, suffix: String): LocalDynamaxGuard.IncomingHit? {
                    hit ?: return null
                    val first = LocalPublicTurnOrder.actsFirstProbability(state, BattleSide.ALLY, part, BattleSide.OPPONENT, hit.action) ?: 0.5
                    val pattern = hit.action.moveDetails?.targetPattern
                    val spread = pattern == BattleMoveTargetPattern.ALL_OPPONENTS || pattern == BattleMoveTargetPattern.ALL_ADJACENT ||
                        pattern == BattleMoveTargetPattern.ALL_ACTIVE
                    val aim = when {
                        spread -> 1.0
                        option.targetId != null -> if (option.targetId == user.battlePokemonId) 1.0 else 0.0
                        else -> 1.0 / allies
                    }
                    // A known status candidate gives an opposing Sucker Punch/Thunderclap no pending attack to hit.
                    val pending = hit.action.moveDetails?.effects?.requirements.orEmpty().any {
                        it.kind == BattleMoveRequirementKind.TARGET_PENDING_DAMAGING_MOVE
                    }
                    val works = if (pending && part.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS) 0.0 else 1.0
                    val acts = LocalKnockoutWorth.actsThisTurn(foe, state)
                    val before = acts * (1.0 - first) * works
                    LocalDecisionInternals.record("actingHit:$trace:${foe.activeSlot}:$index:$suffix:${hit.score.moveId}", mapOf(
                        "min" to hit.score.minimumDamageFraction, "max" to hit.score.maximumDamageFraction,
                        "ko1" to hit.score.knockoutChanceWithin(1), "incoming" to before, "hp" to user.hpFraction,
                        "probability" to probability, "first" to first, "opponentActs" to acts, "works" to works, "target" to aim))
                    return LocalDynamaxGuard.IncomingHit(hit.score, before, aim)
                }
                val hit = incoming(normal, "plain")
                val maxHit = incoming(dynamax, "max")
                fun risk(value: LocalDynamaxGuard.IncomingHit?) = value?.let {
                    it.move.knockoutChanceWithin(1) * it.incomingProbability * it.targetProbability
                } ?: 0.0
                val risk = maxOf(risk(hit), risk(maxHit))
                LocalDecisionInternals.record("actingChoice:$trace:${foe.activeSlot}:$index", mapOf(
                    "probability" to probability, "attack" to LocalDecisionInternals.flag(attack),
                    "fallback" to LocalDecisionInternals.flag(usable == null || unresolved),
                    "plainRisk" to risk(hit), "maxRisk" to risk(maxHit), "risk" to risk,
                    "contribution" to probability * risk))
                WeightedHit(probability, hit, maxHit)
            }
        }
        val knockout = expectedKnockout(choices, user.hpFraction)
        val possible = jbro.cobblemon.mcc.betterai.mechanics.LocalConsecutiveMoveRules.canAttempt(part,state,user)
        val survives = 1.0 - knockout
        val acts = if (possible) survives else 0.0
        LocalDecisionInternals.record("actingModel:$trace", mapOf(
            "knockout" to knockout, "acts" to acts, "predictedFoes" to predictedFoes.toDouble(),
            "fallbackFoes" to fallbackFoes.toDouble(), "foes" to choices.size.toDouble(),
            "survives" to survives,"possible" to LocalDecisionInternals.flag(possible)))
        return acts
    }

    internal data class WeightedHit(
        val probability: Double,
        val hit: LocalDynamaxGuard.IncomingHit?,
        val dynamaxHit: LocalDynamaxGuard.IncomingHit? = null,
    )

    /** Numeric expectation over mutually exclusive choices; this does not simulate future turns. */
    internal fun expectedKnockout(choices: List<List<WeightedHit>>, hp: Double): Double {
        fun knockout(hits: List<LocalDynamaxGuard.IncomingHit>) =
            if (hits.isEmpty()) 0.0 else LocalDynamaxGuard.knockout(hits, hp)
        fun total(index: Int, selected: List<WeightedHit>): Double {
            if (index < choices.size) return choices[index].sumOf { branch ->
                branch.probability * total(index + 1, selected + branch)
            }
            val plain = selected.mapNotNull { it.hit }
            val worst = selected.indices.mapNotNull { changed ->
                selected[changed].dynamaxHit?.let { max ->
                    knockout(selected.mapIndexedNotNull { slot, branch -> if (slot == changed) max else branch.hit })
                }
            }.maxOrNull() ?: 0.0
            return maxOf(knockout(plain), worst)
        }
        return total(0, emptyList()).coerceIn(0.0, 1.0)
    }
}
