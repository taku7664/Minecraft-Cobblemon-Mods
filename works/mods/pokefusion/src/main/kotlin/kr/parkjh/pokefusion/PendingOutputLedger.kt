package kr.parkjh.pokefusion

import java.util.UUID

class PendingOutputLedger<T> {
    private val pendingByPlayer = mutableMapOf<UUID, MutableList<T>>()

    fun enqueue(playerId: UUID, items: Iterable<T>) {
        val additions = items.toList()
        if (additions.isEmpty()) return
        pendingByPlayer.getOrPut(playerId, ::mutableListOf).addAll(additions)
    }

    fun snapshot(playerId: UUID): List<T> = pendingByPlayer[playerId]?.toList().orEmpty()

    fun count(playerId: UUID): Int = pendingByPlayer[playerId]?.size ?: 0

    fun deliver(playerId: UUID, deliver: (T) -> Boolean): Boolean {
        val pending = pendingByPlayer[playerId] ?: return false
        var changed = false
        val iterator = pending.listIterator()
        while (iterator.hasNext()) {
            val delivered = try {
                deliver(iterator.next())
            } catch (_: Exception) {
                false
            }
            if (delivered) {
                iterator.remove()
                changed = true
            }
        }
        if (pending.isEmpty()) pendingByPlayer.remove(playerId)
        return changed
    }

    fun players(): Set<UUID> = pendingByPlayer.keys.toSet()
}
