package jbro.cobblemon.mcc.betterai.evaluation

import java.util.UUID

/**
 * Each Pokemon's worth, in board units, for the decision this thread is making: what it adds to the board's material
 * ([LocalBoardMaterial]) and what knocking it out is worth ([LocalTacticalSituationalEvaluator.knockoutAdjustment] and
 * the other knockout prices), in place of the same two units for every Pokemon. Set by the brain for the tiers that
 * read the matchup table ([jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth.worths]); a reader outside such a
 * decision, or a Pokemon without a reading, keeps the flat [LocalDecisionTuning.livingPokemonValue] (user decision
 * 2026-10-18: each side's mean held at one Pokemon, so a knockout always takes the opponent a piece closer to losing).
 */
internal object LocalPokemonWorth {
    private val active = ThreadLocal<Map<UUID, Double>?>()

    /** The worths read for the last decision on any thread, for the measurement harness. */
    @Volatile
    var last: Map<UUID, Double> = emptyMap()
        private set

    fun of(id: UUID): Double? = active.get()?.get(id)

    /** A knockout of [id] in score units: its worth, or the flat knockout value without one. */
    fun knockoutScore(id: UUID?, tuning: LocalDecisionTuning): Double =
        id?.let(::of)?.let { it * tuning.boardToScore } ?: tuning.knockoutMaterialScore

    fun set(worths: Map<UUID, Double>?) {
        active.set(worths)
        last = worths.orEmpty()
    }

    fun clear() = active.remove()
}
