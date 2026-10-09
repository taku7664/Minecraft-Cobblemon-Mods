package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier

/**
 * The matchup rules' view of which Pokemon matter, as material multipliers on the scale of
 * [jbro.cobblemon.mcc.betterai.evaluation.LocalOpponentThreat]'s weights, for the native search's
 * AI-only threat term.
 *
 * - An ally is worth its larger role: its [LocalAceScore], or how well it stops the opponent's sweeper
 *   ([StopScore]) times how strong that sweeper is.
 * - An opponent is worth more the better it stops this side's sweeper, times how strong that sweeper is:
 *   removing it frees the sweep.
 *
 * Each side is normalized to a mean of one and clamped into the tier's band, like the threat weights.
 * Like them, they never steer the model of what the opponent chooses.
 */
internal object LocalRuleWeights {
    data class Band(val minimum: Double, val maximum: Double)

    fun allyBand(tier: BattleTrainerTier): Band? = when (tier) {
        BattleTrainerTier.ADVANCED -> Band(0.85, 1.3)
        BattleTrainerTier.BOSS -> Band(0.75, 1.5)
        else -> null
    }

    fun opponentBand(tier: BattleTrainerTier): Band? = when (tier) {
        BattleTrainerTier.ADVANCED -> Band(0.9, 1.3)
        BattleTrainerTier.BOSS -> Band(0.8, 1.5)
        else -> null
    }

    fun weights(
        state: BattleStateView,
        tier: BattleTrainerTier,
        aceScores: Map<UUID, Double>,
        scores: MatchupScores?,
    ): Map<UUID, Double> {
        val allyBand = allyBand(tier) ?: return emptyMap()
        val opponentBand = opponentBand(tier) ?: return emptyMap()
        fun living(side: BattleSide) = state.pokemon.filter { it.side == side && !it.fainted && it.hpFraction > 0.0 }
        val allies = living(BattleSide.ALLY)
        val opponents = living(BattleSide.OPPONENT)
        val opponentSweep = scores?.sweeper(BattleSide.OPPONENT, state)?.score ?: 0.0
        val allySweep = scores?.sweeper(BattleSide.ALLY, state)?.score ?: 0.0
        val allyRaw = allies.associate { ally ->
            val stop = (scores?.stops?.get(ally.battlePokemonId)?.score ?: 0.0) * opponentSweep
            ally.battlePokemonId to maxOf(aceScores[ally.battlePokemonId] ?: 0.0, stop)
        }
        val opponentRaw = if (scores == null) emptyMap() else opponents.associate { foe ->
            foe.battlePokemonId to (scores.stops[foe.battlePokemonId]?.score ?: 0.0) * allySweep
        }
        return normalize(allyRaw, allyBand) + normalize(opponentRaw, opponentBand)
    }

    /** Raw 0..1 mapped onto the band, then normalized to a mean of one and clamped back into it. */
    private fun normalize(raw: Map<UUID, Double>, band: Band): Map<UUID, Double> {
        if (raw.size < 2 || raw.values.all { it <= 0.0 }) return emptyMap()
        val mapped = raw.mapValues { (_, value) ->
            band.minimum + (band.maximum - band.minimum) * value.coerceIn(0.0, 1.0)
        }
        val mean = mapped.values.average()
        return mapped.mapValues { (_, value) -> (value / mean).coerceIn(band.minimum, band.maximum) }
    }
}
