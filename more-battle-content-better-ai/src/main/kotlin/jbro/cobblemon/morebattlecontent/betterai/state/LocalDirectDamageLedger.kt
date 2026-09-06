package jbro.cobblemon.morebattlecontent.betterai.state

import java.util.UUID

internal data class LocalDirectDamageRecipient(val actorId: UUID, val targetId: UUID)

/**
 * Submitted moves' direct damage, in fractions of each recipient's maximum HP.
 * Uses the projector's representative damage rolls. Values are conditional means inside a merged
 * branch, not exact expectations over the full damage-roll distribution.
 * Excludes delayed strikes, residual damage, recoil, contact reactions and healing.
 */
internal class LocalDirectDamageLedger private constructor(
    val amounts: Map<LocalDirectDamageRecipient, Double>,
) {
    operator fun plus(other: LocalDirectDamageLedger): LocalDirectDamageLedger {
        if (amounts.isEmpty()) return other
        if (other.amounts.isEmpty()) return this
        val sum = amounts.toMutableMap()
        other.amounts.forEach { (key, damage) -> sum[key] = (sum[key] ?: 0.0) + damage }
        return LocalDirectDamageLedger(sum)
    }

    companion object {
        val EMPTY = LocalDirectDamageLedger(emptyMap())

        fun hit(actorId: UUID, targetId: UUID?, damage: Double): LocalDirectDamageLedger =
            if (targetId == null || damage <= 0.0) EMPTY else
                LocalDirectDamageLedger(mapOf(LocalDirectDamageRecipient(actorId, targetId) to damage))

        /** Missing recipients contribute zero, including branches where the move misses. */
        fun weighted(branches: List<Pair<Double, LocalDirectDamageLedger>>): LocalDirectDamageLedger {
            val total = branches.sumOf { it.first }
            if (total <= 0.0 || branches.all { it.second.amounts.isEmpty() }) return EMPTY
            val sum = linkedMapOf<LocalDirectDamageRecipient, Double>()
            branches.forEach { (probability, ledger) ->
                ledger.amounts.forEach { (key, damage) ->
                    sum[key] = (sum[key] ?: 0.0) + probability * damage / total
                }
            }
            return LocalDirectDamageLedger(sum)
        }
    }
}
