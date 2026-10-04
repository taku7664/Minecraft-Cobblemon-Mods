package kr.parkjh.pokefusion

/**
 * Delivers a durable queue one entry at a time.
 *
 * The next queue state is checkpointed before [grant] mutates external state. If a grant only
 * consumes part of an entry, its remainder is checkpointed with the untouched tail and delivery
 * stops so ordering stays deterministic.
 */
internal object PendingDeliveryQueue {
    fun <T> deliver(
        pending: List<T>,
        checkpoint: (List<T>) -> Unit,
        grant: (T) -> T?
    ): Int {
        var queue = pending.toList()
        var delivered = 0
        while (queue.isNotEmpty()) {
            val current = queue.first()
            val tail = queue.drop(1)
            checkpoint(tail)

            val remainder = try {
                grant(current)
            } catch (exception: Exception) {
                checkpoint(listOf(current) + tail)
                throw exception
            }

            if (remainder == null) {
                delivered++
                queue = tail
            } else {
                queue = listOf(remainder) + tail
                checkpoint(queue)
                break
            }
        }
        return delivered
    }
}
