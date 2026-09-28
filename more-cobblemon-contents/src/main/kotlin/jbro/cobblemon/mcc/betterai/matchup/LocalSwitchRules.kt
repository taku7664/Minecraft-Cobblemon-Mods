package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide

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
        for (candidate in candidates) {
            val switches = parts(candidate).filter { it.kind == BattleActionKind.SWITCH && it.switchPokemonId != null }
            if (switches.isEmpty()) continue
            var adjustment = 0.0
            for (part in switches) {
                val incoming = state.pokemon.firstOrNull { it.battlePokemonId == part.switchPokemonId } ?: continue
                val replaced = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot }
                if (replaced == null || replaced.fainted || replaced.hpFraction <= 0.0) {
                    replacementValues[part.actionId] = opponents.mapNotNull {
                        scores.pokemon(incoming.battlePokemonId, it.battlePokemonId)?.score
                    }.averageOrNull() ?: continue
                    continue
                }
                val verdict = voluntary(incoming, replaced, opponents, scores) ?: continue
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
    ): Verdict? {
        val entries = opponents.mapNotNull { scores.switchIn(incoming.battlePokemonId, it.battlePokemonId, replaced.battlePokemonId) }
        if (entries.isEmpty()) return null
        val stays = opponents.mapNotNull { scores.pokemon(replaced.battlePokemonId, it.battlePokemonId) }
        // One opponent focusing the slot is enough to knock the incoming Pokemon out.
        val survival = entries.minOf { it.predictedSurvival }
        val switchValue = entries.map { it.score }.average()
        val stayValue = stays.map { it.score }.averageOrNull() ?: 0.0
        val keepLeaving = scores.preserves[replaced.battlePokemonId]?.score ?: 0.0
        val keepIncoming = scores.preserves[incoming.battlePokemonId]?.score ?: 0.0
        val leavingDoomed = stays.any { it.winProbability < 0.5 && (it.opponentMove?.knockoutChanceWithin(1) ?: 0.0) >= DOOMED }
        val sacrifice = leavingDoomed && keepLeaving >= WORTH_KEEPING && keepIncoming <= WORTHLESS
        var adjustment = 0.0
        if (sacrifice) adjustment += keepLeaving * SACRIFICE_SCALE
        val gain = switchValue - stayValue
        if (gain >= SWITCH_MARGIN) adjustment += gain * SCORE_SCALE
        if (keepLeaving <= WORTHLESS && keepIncoming >= WORTH_KEEPING) {
            // What the incoming Pokemon would face walking in after a knockout, without the hit.
            val free = opponents.mapNotNull { scores.pokemon(incoming.battlePokemonId, it.battlePokemonId)?.score }.averageOrNull()
            val freeEntryGain = free?.let { it - switchValue } ?: 0.0
            if (freeEntryGain >= FREE_ENTRY_MARGIN) adjustment -= freeEntryGain * SCORE_SCALE
        }
        val exclusion = if (survival < SURVIVAL_PASS && !sacrifice) SWITCH_IN_DIES else null
        return Verdict(exclusion, adjustment)
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
