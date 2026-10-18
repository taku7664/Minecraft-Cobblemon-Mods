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
    private val denials = ThreadLocal<Map<UUID, Double>?>()

    /** The worths read for the last decision on any thread, for the measurement harness. */
    @Volatile
    var last: Map<UUID, Double> = emptyMap()
        private set

    fun of(id: UUID): Double? = active.get()?.get(id)

    /** What knocking [id] out this turn stops, in board units ([jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth.Reading.denials]). */
    fun denial(id: UUID): Double = denials.get()?.get(id) ?: 0.0

    /**
     * A knockout of [id] by an attack that moves first with [first]'s chance: its worth, and the hit it no longer gets
     * to use this turn when the attack lands before it. Without the hit a Scald that finished a 1% Probopass read as
     * worth less than Protect (2026-10-18).
     */
    fun knockoutScore(id: UUID?, tuning: LocalDecisionTuning, first: Double): Double =
        knockoutScore(id, tuning) + (id?.let(::denial) ?: 0.0) * first.coerceIn(0.0, 1.0) * tuning.boardToScore

    /** A knockout of [id] in score units: its worth, or the flat knockout value without one. */
    fun knockoutScore(id: UUID?, tuning: LocalDecisionTuning): Double =
        id?.let(::of)?.let { it * tuning.boardToScore } ?: tuning.knockoutMaterialScore

    fun set(worths: Map<UUID, Double>?, denials: Map<UUID, Double>? = null) {
        active.set(worths)
        this.denials.set(denials)
        last = worths.orEmpty()
    }

    fun clear() {
        active.remove()
        denials.remove()
    }
}
