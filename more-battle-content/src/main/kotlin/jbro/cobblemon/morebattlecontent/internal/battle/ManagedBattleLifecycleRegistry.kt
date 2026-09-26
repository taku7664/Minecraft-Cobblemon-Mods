package jbro.cobblemon.morebattlecontent.internal.battle

/**
 * Tracks temporary battle state until Cobblemon has actually released its spawned entities.
 *
 * Ending a battle and removing its entities are separate operations in Cobblemon. Keeping the
 * entry through that gap lets disconnect and server-stop handlers synchronously finish cleanup.
 */
internal class ManagedBattleLifecycleRegistry<P : Any, B : Any, T : Any> {
    internal data class Entry<P : Any, B : Any, T : Any>(
        val playerId: P,
        val battleId: B,
        val targets: List<T>,
        var endedTicks: Int? = null,
    )

    private val entriesByBattle = LinkedHashMap<B, Entry<P, B, T>>()

    @Synchronized
    fun register(playerId: P, battleId: B, targets: List<T>) {
        require(targets.isNotEmpty()) { "A managed battle must own at least one cleanup target" }
        require(battleId !in entriesByBattle) { "Managed battle $battleId is already registered" }
        entriesByBattle[battleId] = Entry(playerId, battleId, targets.toList())
    }

    @Synchronized
    fun markEnded(battleId: B): Boolean {
        val entry = entriesByBattle[battleId] ?: return false
        if (entry.endedTicks == null) entry.endedTicks = 0
        return true
    }

    @Synchronized
    fun takeBattle(battleId: B): Entry<P, B, T>? = entriesByBattle.remove(battleId)

    /**
     * Removes completed entries once their targets are gone. Entries still alive after the grace
     * window are returned to the caller for synchronous forced release.
     */
    @Synchronized
    fun advanceEnded(
        graceTicks: Int,
        isReleased: (T) -> Boolean,
    ): List<Entry<P, B, T>> {
        require(graceTicks > 0) { "Grace ticks must be positive" }
        val forced = ArrayList<Entry<P, B, T>>()
        val iterator = entriesByBattle.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val endedTicks = entry.endedTicks ?: continue
            if (entry.targets.all(isReleased)) {
                iterator.remove()
                continue
            }
            val advancedTicks = endedTicks + 1
            entry.endedTicks = advancedTicks
            if (advancedTicks >= graceTicks) {
                iterator.remove()
                forced += entry
            }
        }
        return forced
    }

    @Synchronized
    fun takePlayer(playerId: P): List<Entry<P, B, T>> {
        val claimed = entriesByBattle.values.filter { it.playerId == playerId }
        claimed.forEach { entriesByBattle.remove(it.battleId) }
        return claimed
    }

    @Synchronized
    fun takeAll(): List<Entry<P, B, T>> = entriesByBattle.values.toList().also {
        entriesByBattle.clear()
    }

    @Synchronized
    fun size(): Int = entriesByBattle.size
}
