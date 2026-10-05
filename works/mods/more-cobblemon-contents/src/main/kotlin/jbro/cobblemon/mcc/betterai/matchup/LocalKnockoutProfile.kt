package jbro.cobblemon.mcc.betterai.matchup

import kotlin.math.floor

/**
 * How a target's HP bar goes under repeated uses of one move: after `n` uses, the chance it is still
 * standing and the damage it has taken in those cases.
 *
 * Each use lands with the move's accuracy and then deals one of its damage rolls, all equally likely.
 * Damage accumulates on a grid of a thousandth of the target's current HP, rounded down, so a roll one
 * point short of a knockout is never promoted into one.
 *
 * Rolls come capped at the current HP. That loses nothing here: a sum of capped rolls reaches the HP
 * exactly when the sum of the real rolls does, because the cap only touches a roll that knocks out alone.
 */
internal class LocalKnockoutProfile private constructor(
    /** Index `n`: chance the target stands after `n` uses. */
    val survival: List<Double>,
    /** Index `n`: damage taken by `n` uses, as a fraction of maximum HP, averaged over standing cases. */
    val damageWhileStanding: List<Double>,
    /** Expected uses to a knockout; infinite when the move cannot deal damage. */
    val expectedUses: Double,
) {
    companion object {
        const val MAXIMUM_CELLS = 1000
        private const val GRID = MAXIMUM_CELLS

        fun fromProjected(survival: List<Double>, damage: List<Double>, targetHp: Double, meanRoll: Double,
            accuracy: Double, canContinue: Boolean): LocalKnockoutProfile {
            val left = survival.last()
            val tail = if (left <= 0.0) 0.0 else if (!canContinue || meanRoll * accuracy <= 0.0) Double.POSITIVE_INFINITY
                else left * ((targetHp - damage.last()).coerceAtLeast(0.0) / (meanRoll * accuracy))
            val expected = survival.dropLast(1).sum() + tail
            return LocalKnockoutProfile(survival, damage, expected)
        }

        fun of(rolls: List<Double>, accuracy: Double, targetHp: Double, maximumUses: Int): LocalKnockoutProfile {
            val hit = accuracy.coerceIn(0.0, 1.0)
            val meanRoll = if (rolls.isEmpty()) 0.0 else rolls.average()
            if (targetHp <= 0.0) {
                return LocalKnockoutProfile(List(maximumUses + 1) { if (it == 0) 1.0 else 0.0 }, List(maximumUses + 1) { 0.0 }, 0.0)
            }
            if (hit <= 0.0 || meanRoll <= 0.0) {
                return LocalKnockoutProfile(List(maximumUses + 1) { 1.0 }, List(maximumUses + 1) { 0.0 }, Double.POSITIVE_INFINITY)
            }
            val steps = rolls.map { floor(it / targetHp * GRID).toInt().coerceIn(0, GRID) }
            val perRoll = hit / rolls.size
            var standing = DoubleArray(GRID).also { it[0] = 1.0 }
            val survival = ArrayList<Double>(maximumUses + 1).apply { add(1.0) }
            val damage = ArrayList<Double>(maximumUses + 1).apply { add(0.0) }
            repeat(maximumUses) {
                val next = DoubleArray(GRID)
                for (taken in 0 until GRID) {
                    val mass = standing[taken]
                    if (mass == 0.0) continue
                    next[taken] += mass * (1.0 - hit)
                    for (step in steps) {
                        val reached = taken + step
                        if (reached < GRID) next[reached] += mass * perRoll
                    }
                }
                standing = next
                val alive = standing.sum()
                survival += alive.coerceIn(0.0, 1.0)
                damage += if (alive <= 0.0) 0.0 else
                    standing.indices.sumOf { it * standing[it] } / alive / GRID * targetHp
            }
            // Uses beyond the window: what is left of the bar at the average landed damage per use.
            val left = survival.last()
            val tail = if (left <= 0.0) 0.0 else left * ((targetHp - damage.last()).coerceAtLeast(0.0) / (meanRoll * hit))
            val expected = (0 until maximumUses).sumOf { survival[it] } + tail
            return LocalKnockoutProfile(survival, damage, expected)
        }
    }
}
