package jbro.cobblemon.morebattlecontent.betterai.mechanics

/**
 * Which damage roll a trainer expects to get.
 *
 * A Showdown damage range is sixteen equally likely rolls, and every consumer of it here collapsed
 * that to the midpoint. That is the expected-value answer, and no real player gives it: one plays as
 * though the roll will be kind, another as though it will not, and the difference is most of what
 * makes two trainers feel like different people.
 *
 * The same idea is how the mainline-derived engines express trainer character - a risky trainer
 * assumes the highest roll, a conservative one assumes the lowest - and it is worth copying for a
 * reason beyond fidelity. Risk was already modelled here, but only inside the action *selector*,
 * where it tilts a weighted draw. That draw was measured to have almost nothing to tilt: the
 * shortlist collapses to a single entry in a third of positions and the favourite takes 89% of the
 * rest, so personality had about 7% of turns to express itself in and produced no measurable
 * difference between trainers at all. Attitude applied to the *value* needs no such room. It changes
 * what the trainer believes the move will do, which is upstream of every comparison.
 *
 * Attitude runs 0.0 to 1.0 and is a position within the roll range, not a multiplier: 0.0 expects the
 * lowest roll, 1.0 the highest, and 0.5 reproduces the midpoint exactly. Neutral is therefore
 * behaviour-preserving, which is what makes this safe to thread through by default.
 */
internal object LocalRiskAttitude {
    /** Expected-value behaviour: the midpoint, exactly as every call site computed before. */
    const val NEUTRAL = 0.5

    /**
     * The roll a trainer with this [attitude] expects, as a fraction between [minimum] and [maximum].
     *
     * Attitude outside 0..1 is clamped rather than rejected. It reaches here from personality values
     * and from a position-adjusted risk budget, and a decision path is the wrong place to throw.
     */
    fun expectedFraction(minimum: Double, maximum: Double, attitude: Double): Double {
        val position = attitude.coerceIn(0.0, 1.0)
        return minimum + (maximum - minimum) * position
    }

    /**
     * The attitude to use when judging what the *other* side will do to you.
     *
     * Caution is not a belief that dice are small; it is a belief that they favour the opponent. A
     * trainer who expects their own low roll and the opponent's low roll is not cautious, just
     * confused, and would read as inconsistent across a battle.
     */
    fun opposing(attitude: Double): Double = 1.0 - attitude.coerceIn(0.0, 1.0)
}
