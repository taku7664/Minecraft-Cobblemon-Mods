package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.outcome.PublicActionOutcomeProjector
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import java.util.UUID

/**
 * What the user's own knockout costs (Explosion, Final Gambit, recoil it cannot survive): the Pokemon that falls, not a
 * flat [LocalDecisionTuning.knockoutMaterialScore]. A Pokemon's
 * worth now is the mean of `(score + 1) / 2` over its one-on-ones ([PokemonMatchupScore.score], current HP and stages)
 * against every living Pokemon of the other side, scaled so the mean over every living Pokemon on the board is the flat
 * value. A Golem at 1 HP that still moves first with its Custap Berry and knocks out keeps its worth; one that loses
 * every exchange is worth almost nothing to keep, so its Explosion costs little, and an opposing ace is worth more to
 * knock out than a worn-down Pokemon (user decision 2026-10-18: both sides, normalised per board).
 *
 * The ranking credits a knockout at the flat value ([LocalTacticalSituationalEvaluator.knockoutAdjustment], its spread
 * extras, and the self-knockout in the outcome evaluator); the adjustment is the difference to the worth.
 */
internal object LocalKnockoutWorth {
    /** Each living Pokemon's knockout value in score units, the board's mean equal to the flat value. */
    fun worths(state: BattleStateView, scores: MatchupScores, flat: Double): Map<UUID, Double> {
        val living = state.pokemon.filter { !it.fainted && it.hpFraction > 0.0 }
        val raw = living.mapNotNull { pokemon ->
            val values = living.filter { it.side != pokemon.side }.mapNotNull { other ->
                if (pokemon.side == BattleSide.ALLY) scores.pokemon(pokemon.battlePokemonId, other.battlePokemonId)?.score
                else scores.pokemon(other.battlePokemonId, pokemon.battlePokemonId)?.score?.let { -it }
            }
            if (values.isEmpty()) null else pokemon.battlePokemonId to values.map { (it + 1.0) / 2.0 }.average()
        }.toMap()
        val mean = raw.values.takeIf { it.isNotEmpty() }?.average() ?: return emptyMap()
        if (mean < MINIMUM_MEAN) return emptyMap()
        return raw.mapValues { (_, value) -> flat * value / mean }
    }

    fun adjustments(
        candidates: List<BattleActionCandidate>,
        ranked: BattleDecisionContext,
        board: BattleStateView,
        scores: MatchupScores,
        tuning: LocalDecisionTuning,
    ): Map<String, Double> {
        val flat = tuning.knockoutMaterialScore
        if (flat <= 0.0 || tuning.legacyRawPowerFallback) return emptyMap()
        val worth = worths(board, scores, flat)
        if (worth.isEmpty()) return emptyMap()
        val memo = HashMap<String, Double>()
        return candidates.mapNotNull { candidate ->
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            val total = parts.sumOf { part -> memo.getOrPut(part.actionId) { difference(part, ranked, worth, flat, tuning) } }
            if (total == 0.0) null else candidate.actionId to total
        }.toMap()
    }

    private fun difference(
        move: BattleActionCandidate,
        ranked: BattleDecisionContext,
        worth: Map<UUID, Double>,
        flat: Double,
        tuning: LocalDecisionTuning,
    ): Double {
        if (move.kind != BattleActionKind.USE_MOVE) return 0.0
        val state = ranked.state
        fun at(side: BattleSide, slot: Int?) = state.pokemon.firstOrNull { it.side == side && it.activeSlot == slot && !it.fainted }
        fun scale(pokemon: BattlePokemonStateView?) = pokemon?.let { worth[it.battlePokemonId] }?.let { it / flat - 1.0 } ?: 0.0
        // An opposing Pokemon's knockout keeps the board's value: priced alone at its worth, a 1% Probopass or a
        // Steelix at 27% read as nearly worth nothing to finish while every other rule still prices in the board's unit,
        // and a Swampert set up Stealth Rock instead (measured 2026-10-18; a board-wide worth is left for later).
        var difference = 0.0
        // The user's own knockout (Explosion, Final Gambit, recoil it cannot survive), as the outcome evaluator charged it.
        if (move.moveDetails?.effects?.effects.orEmpty().any { it.target == BattleMoveEffectTarget.USER && it.kind in SELF_COST }) {
            val user = at(BattleSide.ALLY, move.actorSlot)
            val projection = PublicActionOutcomeProjector.project(move, ranked)
            val fall = maxOf(
                projection.expectedSelfFaintChance,
                if ((projection.expectedSelfRecoilFraction ?: 0.0) > 0.0 && projection.actorExpectedHpAfterSelfEffects == 0.0) 1.0 else 0.0,
            )
            difference -= flat * fall * scale(user)
        }
        return difference
    }

    private val SELF_COST = setOf(
        BattleMoveEffectKind.SELF_DESTRUCT, BattleMoveEffectKind.RECOIL_FRACTION, BattleMoveEffectKind.MAX_HP_RECOIL,
        BattleMoveEffectKind.CRASH_RECOIL, BattleMoveEffectKind.STRUGGLE_RECOIL,
    )
    private const val MINIMUM_MEAN = 1e-6
}
