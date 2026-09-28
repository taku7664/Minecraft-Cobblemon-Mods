package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * The switching rules, read from [MatchupScores].
 *
 * A voluntary switch is judged by what the incoming Pokemon faces: the hit the opponent chose for the one it
 * replaces, then the exchange ([SwitchInScore]), against staying ([PokemonMatchupScore]).
 *
 * - `switch_in_dies` (ruled out): it does not survive that hit, unless it is a sacrifice.
 * - A clearly better switch: [SWITCH_MARGIN] or more above staying, credited by the difference.
 * - A sacrifice: the one leaving is worth keeping ([PreserveScore]) and about to be knocked out, and the one
 *   coming in is worth little - it takes the hit so the other survives.
 * - A wasted free entry: the one leaving is worth little and the one coming in is valuable. Letting the first
 *   fall brings the second in without a hit, and that is worth more than switching it in now.
 *
 * A replacement after a knockout enters without a hit, so the candidates are compared on the exchange alone.
 * So does the one a pivot brings in once the opponent has moved; before that, it takes the hit. Such a
 * replacement (a pivot's, an Eject Button's) is asked for mid-turn, with the one leaving still standing.
 *
 * A pivot (U-turn, Volt Switch, Parting Shot) is a switch that also acts: credited like a clearly better
 * switch into the best Pokemon behind it, whose entry is free with the chance the pivot moves second.
 *
 * An sweeper facing Unaware or Destiny Bond retreats ([LocalSetupGate.retreatReasons]): a switch taking it out
 * is credited by its sweep score, as long as the incoming Pokemon survives the entry.
 *
 * Adjustments are in the ranking's score units and are added after the search; the search owns the rest.
 */
internal object LocalSwitchRules {
    data class Judgement(val exclusions: Map<String, String>, val adjustments: Map<String, Double>) {
        companion object {
            val NONE = Judgement(emptyMap(), emptyMap())
        }
    }

    fun judge(candidates: List<BattleActionCandidate>, context: BattleDecisionContext, scores: MatchupScores): Judgement {
        val state = context.state
        val opponents = state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        if (opponents.isEmpty()) return Judgement.NONE
        val replacementValues = linkedMapOf<String, Double>()
        val exclusions = linkedMapOf<String, String>()
        val adjustments = linkedMapOf<String, Double>()
        // An sweeper that should retreat, by its sweep score; worked out once per Pokemon.
        val retreatCredits = HashMap<java.util.UUID, Double>()
        fun retreats(pokemon: BattlePokemonStateView): Double = retreatCredits.getOrPut(pokemon.battlePokemonId) {
            if (LocalSetupGate.retreatReasons(pokemon, context, scores).isEmpty()) 0.0
            else scores.sweeps[pokemon.battlePokemonId]?.score ?: 0.0
        }
        // A slot that may not move is being asked for a replacement.
        val movingSlots = candidates.flatMap(::parts).filter { it.kind == BattleActionKind.USE_MOVE }.map { it.actorSlot }.toSet()
        for (candidate in candidates) {
            var adjustment = 0.0
            for (part in parts(candidate)) {
                if (part.kind == BattleActionKind.USE_MOVE && pivots(part)) {
                    val user = active(state, part.actorSlot) ?: continue
                    adjustment += pivot(part, user, opponents, state, scores)
                    continue
                }
                if (part.kind != BattleActionKind.SWITCH) continue
                val incoming = state.pokemon.firstOrNull { it.battlePokemonId == part.switchPokemonId } ?: continue
                val replaced = active(state, part.actorSlot)
                if (replaced == null) {
                    replacementValues[part.actionId] = opponents.mapNotNull {
                        scores.pokemon(incoming.battlePokemonId, it.battlePokemonId)?.score
                    }.averageOrNull() ?: continue
                    continue
                }
                if (part.actorSlot !in movingSlots) {
                    replacementValues[part.actionId] = midTurnEntry(incoming, replaced, opponents, state, scores) ?: continue
                    continue
                }
                val verdict = voluntary(incoming, replaced, opponents, scores, ::retreats) ?: continue
                verdict.exclusion?.let { exclusions[candidate.actionId] = it }
                adjustment += verdict.adjustment
            }
            if (adjustment != 0.0) adjustments[candidate.actionId] = adjustment
        }
        // Replacements are credited against each other, so their average shifts nothing.
        if (replacementValues.isNotEmpty()) {
            val mean = replacementValues.values.average()
            for (candidate in candidates) {
                val values = parts(candidate).mapNotNull { replacementValues[it.actionId] }
                if (values.isEmpty()) continue
                adjustments.merge(candidate.actionId, values.sumOf { (it - mean) * SCORE_SCALE }, Double::plus)
            }
        }
        return Judgement(exclusions, adjustments)
    }

    private class Verdict(val exclusion: String?, val adjustment: Double)

    private fun voluntary(
        incoming: BattlePokemonStateView,
        replaced: BattlePokemonStateView,
        opponents: List<BattlePokemonStateView>,
        scores: MatchupScores,
        retreats: (BattlePokemonStateView) -> Double,
    ): Verdict? {
        val entries = opponents.mapNotNull { scores.switchIn(incoming.battlePokemonId, it.battlePokemonId, replaced.battlePokemonId) }
        if (entries.isEmpty()) return null
        val stays = opponents.mapNotNull { scores.pokemon(replaced.battlePokemonId, it.battlePokemonId) }
        // One opponent focusing the slot is enough to knock the incoming Pokemon out.
        val survival = entries.minOf { it.predictedSurvival }
        val switchValue = entries.map { it.score }.average()
        val stayValue = stays.map { it.score }.averageOrNull() ?: 0.0
        val keepLeaving = scores.preserves[replaced.battlePokemonId]?.score ?: 0.0
        val retreat = retreats(replaced)
        val keepIncoming = scores.preserves[incoming.battlePokemonId]?.score ?: 0.0
        val leavingDoomed = stays.any { it.winProbability < 0.5 && (it.opponentMove?.knockoutChanceWithin(1) ?: 0.0) >= DOOMED }
        val sacrifice = leavingDoomed && keepLeaving >= WORTH_KEEPING && keepIncoming <= WORTHLESS
        var adjustment = 0.0
        if (sacrifice) adjustment += keepLeaving * SACRIFICE_SCALE
        val gain = switchValue - stayValue
        if (gain >= SWITCH_MARGIN) adjustment += gain * SCORE_SCALE
        if (retreat > 0.0 && survival >= SURVIVAL_PASS) adjustment += retreat * SCORE_SCALE
        if (keepLeaving <= WORTHLESS && keepIncoming >= WORTH_KEEPING) {
            // What the incoming Pokemon would face walking in after a knockout, without the hit.
            val free = opponents.mapNotNull { scores.pokemon(incoming.battlePokemonId, it.battlePokemonId)?.score }.averageOrNull()
            val freeEntryGain = free?.let { it - switchValue } ?: 0.0
            if (freeEntryGain >= FREE_ENTRY_MARGIN) adjustment -= freeEntryGain * SCORE_SCALE
        }
        val exclusion = if (survival < SURVIVAL_PASS && !sacrifice) SWITCH_IN_DIES else null
        return Verdict(exclusion, adjustment)
    }

    /** A pivot's credit: the best switch behind it, entering free with the chance the pivot moves second. */
    private fun pivot(
        move: BattleActionCandidate,
        user: BattlePokemonStateView,
        opponents: List<BattlePokemonStateView>,
        state: BattleStateView,
        scores: MatchupScores,
    ): Double {
        val stayValue = opponents.mapNotNull { scores.pokemon(user.battlePokemonId, it.battlePokemonId)?.score }.averageOrNull() ?: return 0.0
        val priority = LocalPublicTurnOrder.effectivePriority(state, BattleSide.ALLY, move)
        val bench = state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot == null && !it.fainted && it.hpFraction > 0.0 }
        val best = bench.mapNotNull { incoming ->
            opponents.mapNotNull { opponent ->
                val free = scores.pokemon(incoming.battlePokemonId, opponent.battlePokemonId)?.score ?: return@mapNotNull null
                val hit = scores.switchIn(incoming.battlePokemonId, opponent.battlePokemonId, user.battlePokemonId)?.score ?: free
                // The opponent's attack is taken to have no priority.
                val first = when {
                    priority > 0 -> 1.0
                    priority < 0 -> 0.0
                    else -> LocalPublicTurnOrder.speedOrderProbability(state, user, opponent) ?: 0.5
                }
                first * hit + (1.0 - first) * free
            }.averageOrNull()
        }.maxOrNull() ?: return 0.0
        val gain = best - stayValue
        return if (gain >= SWITCH_MARGIN) gain * SCORE_SCALE else 0.0
    }

    /** A mid-turn replacement: free against an opponent that has moved this turn, under its hit otherwise. */
    private fun midTurnEntry(
        incoming: BattlePokemonStateView,
        replaced: BattlePokemonStateView,
        opponents: List<BattlePokemonStateView>,
        state: BattleStateView,
        scores: MatchupScores,
    ): Double? = opponents.mapNotNull { opponent ->
        val free = scores.pokemon(incoming.battlePokemonId, opponent.battlePokemonId)?.score ?: return@mapNotNull null
        val moved = state.observedEvents.any {
            it.turn == state.turn && it.kind == BattleObservedEventKind.MOVE_USED && it.actorPokemonId == opponent.battlePokemonId
        }
        if (moved) free else scores.switchIn(incoming.battlePokemonId, opponent.battlePokemonId, replaced.battlePokemonId)?.score ?: free
    }.averageOrNull()

    private fun pivots(move: BattleActionCandidate): Boolean =
        move.moveDetails?.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.SWITCH_USER }

    private fun active(state: BattleStateView, slot: Int?): BattlePokemonStateView? = state.pokemon.firstOrNull {
        it.side == BattleSide.ALLY && it.activeSlot == slot && !it.fainted && it.hpFraction > 0.0
    }

    private fun parts(candidate: BattleActionCandidate): List<BattleActionCandidate> =
        if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    const val SWITCH_IN_DIES = "switch_in_dies"
    const val SURVIVAL_PASS = 0.5
    const val SWITCH_MARGIN = 0.5
    const val FREE_ENTRY_MARGIN = 0.3
    const val DOOMED = 0.8
    const val WORTH_KEEPING = 0.25
    const val WORTHLESS = 0.05
    /** One point of matchup score in ranking units, the scale a board point has in the search. */
    const val SCORE_SCALE = 100.0
    const val SACRIFICE_SCALE = 200.0
}
