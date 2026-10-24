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
    /** A legal first use, then an intervening turn between uses (the data's cantusetwice flag). */
    fun withGapBetweenUses(firstAvailable: Boolean = true): LocalKnockoutProfile = LocalKnockoutProfile(
        survival.indices.map { survival[(it + if (firstAvailable) 1 else 0) / 2] },
        damageWhileStanding.indices.map { damageWhileStanding[(it + if (firstAvailable) 1 else 0) / 2] },
        if (expectedUses.isInfinite()) expectedUses else (2.0 * expectedUses - if (firstAvailable) 1.0 else 0.0).coerceAtLeast(0.0),
    )
    /**
     * The same move into an intact Disguise: the first use only breaks it for an eighth of the target's HP, so every
     * knockout comes one use later. Read without it, a Bisharp's Iron Head was a sure knockout into a fresh Mimikyu and
     * it died without its Sucker Punch (dev server, 2026-10-20).
     */
    fun behindDisguise(chip: Double): LocalKnockoutProfile = LocalKnockoutProfile(
        listOf(1.0) + survival.dropLast(1),
        listOf(chip) + damageWhileStanding.dropLast(1).map { it + chip },
        expectedUses + 1.0,
    )

    companion object {
        /** Already approximated future-only profiles; ordinary callers retain of/byHits unchanged. */
        internal fun forecast(survival: List<Double>, damage: List<Double>, expected: Double): LocalKnockoutProfile =
            LocalKnockoutProfile(survival, damage, expected)

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

        /**
         * A move its user can use only once, as it faints doing so (Explosion; Final Gambit when it lands): the first use
         * as [of] reads it, nothing after. Repeating its roll made an Explosion that leaves the target standing read as
         * two uses to a knockout, the opponent's best hit at every Pokemon in front of an Explosion user (2026-10-18).
         */
        fun once(rolls: List<Double>, accuracy: Double, targetHp: Double, maximumUses: Int,
            firstHitLimit: Double? = null): LocalKnockoutProfile {
            val first = of(rolls, accuracy, targetHp, 1, firstHitLimit)
            val survival = List(maximumUses + 1) { first.survival[minOf(it, 1)] }
            val damage = List(maximumUses + 1) { first.damageWhileStanding[minOf(it, 1)] }
            return LocalKnockoutProfile(survival, damage, if (survival[1] <= 0.0) 1.0 else Double.POSITIVE_INFINITY)
        }

        /**
         * A move whose damage changes with each landed use, as its own stat changes pile up (Draco Meteor's -2 Special
         * Attack, Power-Up Punch's +1 Attack): [rollsByHits] at index `k` are its rolls once it has landed `k` times, the
         * last list for every landing after. A miss changes nothing, so the table also tracks how many have landed.
         * Repeating the first roll read the third Draco Meteor as hard as the first (2026-10-18).
         */
        fun byHits(rollsByHits: List<List<Double>>, accuracy: Double, targetHp: Double, maximumUses: Int,
            firstHitLimit: Double? = null): LocalKnockoutProfile {
            if (rollsByHits.size <= 1) return of(rollsByHits.firstOrNull().orEmpty(), accuracy, targetHp, maximumUses, firstHitLimit)
            val key = ByHitsKey(rollsByHits.map { it.toList() }, accuracy, targetHp, maximumUses, firstHitLimit)
            knownByHits[key]?.let { return it }
            return computedByHits(rollsByHits, accuracy, targetHp, maximumUses, firstHitLimit).also {
                if (knownByHits.size >= KNOWN_LIMIT) knownByHits.clear()
                knownByHits[key] = it
            }
        }

        private fun computedByHits(rollsByHits: List<List<Double>>, accuracy: Double, targetHp: Double,
            maximumUses: Int, firstHitLimit: Double?): LocalKnockoutProfile {
            val hit = accuracy.coerceIn(0.0, 1.0)
            if (targetHp <= 0.0 || hit <= 0.0 || rollsByHits.first().isEmpty() || rollsByHits.first().average() <= 0.0) {
                return of(rollsByHits.first(), accuracy, targetHp, maximumUses)
            }
            val steps = rollsByHits.map { rolls -> rolls.map { floor(it / targetHp * GRID).toInt().coerceIn(0, GRID) } }
            // standing[k][taken]: still up, having taken that much from k landed uses.
            var standing = Array(maximumUses + 1) { DoubleArray(GRID) }.also { if (firstHitLimit == null) it[0][0] = 1.0 }
            // Misses leave full-health survival ready. A landed damaging hit spends it even if max HP is one,
            // where the surviving target still has its whole (one-HP) bar and cannot be distinguished by HP alone.
            var fresh = DoubleArray(maximumUses + 1).also { if (firstHitLimit != null) it[0] = 1.0 }
            val firstLimit = firstHitLimit?.let { floor(it / targetHp * GRID).toInt().coerceIn(0, GRID - 1) } ?: GRID
            val survival = ArrayList<Double>(maximumUses + 1).apply { add(1.0) }
            val damage = ArrayList<Double>(maximumUses + 1).apply { add(0.0) }
            repeat(maximumUses) {
                val next = Array(maximumUses + 1) { DoubleArray(GRID) }
                val nextFresh = DoubleArray(maximumUses + 1)
                for (landed in 0 until maximumUses) {
                    val rolls = steps[minOf(landed, steps.lastIndex)]
                    val perRoll = if (rolls.isEmpty()) 0.0 else hit / rolls.size
                    val ready = fresh[landed]
                    if (ready > 0.0) {
                        nextFresh[landed] += ready * (1.0 - hit)
                        for (step in rolls) {
                            if (step <= 0) nextFresh[landed + 1] += ready * perRoll
                            else next[landed + 1][minOf(step, firstLimit)] += ready * perRoll
                        }
                    }
                    for (taken in 0 until GRID) {
                        val mass = standing[landed][taken]
                        if (mass == 0.0) continue
                        next[landed][taken] += mass * (1.0 - hit)
                        for (step in rolls) {
                            val reached = taken + step
                            if (reached < GRID) next[landed + 1][reached] += mass * perRoll
                        }
                    }
                }
                standing = next
                fresh = nextFresh
                var alive = fresh.sum()
                var taken = 0.0
                for (row in standing) for (cell in row.indices) {
                    alive += row[cell]
                    taken += cell * row[cell]
                }
                survival += alive.coerceIn(0.0, 1.0)
                damage += if (alive <= 0.0) 0.0 else taken / alive / GRID * targetHp
            }
            // Uses beyond the window: each standing case goes on from the stage it has reached, one landing (1 / accuracy
            // uses) at a time at that stage's average hit, so a case that missed every time still hits as hard as the
            // first and weakens after it lands.
            val means = rollsByHits.map { if (it.isEmpty()) 0.0 else it.average() }
            fun usesLeft(landed: Int, left: Double): Double {
                var stage = landed
                var remaining = left
                var uses = 0.0
                while (remaining > 0.0) {
                    val mean = means[minOf(stage, means.lastIndex)]
                    if (mean <= 0.0) return Double.POSITIVE_INFINITY
                    if (remaining <= mean) return uses + if (firstHitLimit != null) 1.0 / hit else remaining / (mean * hit)
                    uses += 1.0 / hit
                    remaining -= mean
                    stage++
                }
                return uses
            }
            var tail = 0.0
            for (landed in standing.indices) for (cell in 0 until GRID) {
                val mass = standing[landed][cell]
                if (mass == 0.0) continue
                tail += mass * usesLeft(landed, (targetHp - cell.toDouble() / GRID * targetHp).coerceAtLeast(0.0))
            }
            fun freshUsesLeft(landed: Int): Double {
                val stage = minOf(landed, steps.lastIndex)
                val rolls = steps[stage]
                val zero = rolls.count { it <= 0 }.toDouble() / rolls.size
                val afterHit = rolls.filter { it > 0 }.sumOf { step ->
                    usesLeft(landed + 1, targetHp * (1.0 - minOf(step, firstLimit).toDouble() / GRID))
                } / rolls.size
                return if (stage == steps.lastIndex) (1.0 / hit + afterHit) / (1.0 - zero)
                    else 1.0 / hit + afterHit + if (zero > 0.0) zero * freshUsesLeft(landed + 1) else 0.0
            }
            for (landed in fresh.indices) if (fresh[landed] > 0.0) tail += fresh[landed] * freshUsesLeft(landed)
            return LocalKnockoutProfile(survival, damage, (0 until maximumUses).sumOf { survival[it] } + tail)
        }

        /**
         * The same rolls, accuracy and HP give the same profile, and the exchange tables ask for the same ones over and over
         * (every board a decision projects): kept, a bounded few thousand, cleared when full. The profile is never changed.
         */
        private data class Key(val rolls: List<Double>, val accuracy: Double, val targetHp: Double, val maximumUses: Int,
            val firstHitLimit: Double?)
        private val known = java.util.concurrent.ConcurrentHashMap<Key, LocalKnockoutProfile>()
        // The landed-use table is immutable too. Snapshot nested rolls so caller mutations cannot
        // corrupt a stored key; stage sequences and first-hit survival remain part of the input.
        private data class ByHitsKey(val rolls: List<List<Double>>, val accuracy: Double, val targetHp: Double,
            val maximumUses: Int, val firstHitLimit: Double?)
        private val knownByHits = java.util.concurrent.ConcurrentHashMap<ByHitsKey, LocalKnockoutProfile>()
        private const val KNOWN_LIMIT = 4096

        fun of(rolls: List<Double>, accuracy: Double, targetHp: Double, maximumUses: Int,
            firstHitLimit: Double? = null): LocalKnockoutProfile {
            val key = Key(rolls.toList(), accuracy, targetHp, maximumUses, firstHitLimit)
            known[key]?.let { return it }
            val result = if (firstHitLimit != null && targetHp > 0.0 && accuracy > 0.0 && rolls.isNotEmpty() && rolls.average() > 0.0)
                byHits(listOf(rolls, rolls), accuracy, targetHp, maximumUses, firstHitLimit)
                else computed(rolls, accuracy, targetHp, maximumUses)
            return result.also {
                if (known.size >= KNOWN_LIMIT) known.clear()
                known[key] = it
            }
        }

        private fun computed(rolls: List<Double>, accuracy: Double, targetHp: Double, maximumUses: Int): LocalKnockoutProfile {
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
            // Cells past `top` hold nothing: the loops stop there, the sums in the same order (a zero cell adds nothing).
            val longest = steps.max()
            var top = 0
            repeat(maximumUses) {
                val next = DoubleArray(GRID)
                for (taken in 0..top) {
                    val mass = standing[taken]
                    if (mass == 0.0) continue
                    next[taken] += mass * (1.0 - hit)
                    for (step in steps) {
                        val reached = taken + step
                        if (reached < GRID) next[reached] += mass * perRoll
                    }
                }
                standing = next
                top = minOf(top + longest, GRID - 1)
                var alive = 0.0
                for (cell in 0..top) alive += standing[cell]
                survival += alive.coerceIn(0.0, 1.0)
                damage += if (alive <= 0.0) 0.0 else {
                    var taken = 0.0
                    for (cell in 0..top) taken += cell * standing[cell]
                    taken / alive / GRID * targetHp
                }
            }
            // Uses beyond the window: what is left of the bar at the average landed damage per use.
            val left = survival.last()
            val tail = if (left <= 0.0) 0.0 else left * ((targetHp - damage.last()).coerceAtLeast(0.0) / (meanRoll * hit))
            val expected = (0 until maximumUses).sumOf { survival[it] } + tail
            return LocalKnockoutProfile(survival, damage, expected)
        }
    }
}
