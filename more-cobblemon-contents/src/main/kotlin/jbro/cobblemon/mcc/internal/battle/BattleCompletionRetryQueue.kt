package jbro.cobblemon.mcc.internal.battle

/**
 * Releases state retained only to retry a completion once persistence has succeeded and its owner
 * is no longer connected. Cleanup failures propagate so the caller can keep the completion queued.
 */
inline fun finalizeCompletionOwner(
    settled: Boolean,
    ownerOnline: Boolean,
    cleanupOfflineOwner: () -> Unit,
): Boolean {
    if (!settled) return false
    if (!ownerOnline) cleanupOfflineOwner()
    return true
}

/** Retains a completed battle result until its persistent settlement succeeds or becomes stale. */
class BattleCompletionRetryQueue<K, T>(
    private val keyOf: (T) -> K,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    private data class Entry<T>(
        val completion: T,
        var remainingDelayMillis: Long,
        var observedAtMillis: Long,
    )

    private val entries = LinkedHashMap<K, Entry<T>>()

    init {
        require(retryMillis > 0)
    }

    @Synchronized
    fun submit(completion: T, settle: (T) -> Boolean): Boolean {
        val key = keyOf(completion)
        val observedEntry = entries[key]
        if (settle(completion)) {
            if (entries[key] === observedEntry) entries.remove(key)
            return true
        }
        if (entries[key] === observedEntry) {
            entries[key] = Entry(completion, retryMillis, currentTimeMillis())
        }
        return false
    }

    @Synchronized
    fun retryDue(force: Boolean = false, settle: (T) -> Boolean) {
        val now = currentTimeMillis()
        entries.values.map(Entry<T>::completion).forEach { completion ->
            val entry = entries[keyOf(completion)] ?: return@forEach
            entry.advanceTo(now)
            if (force || entry.remainingDelayMillis == 0L) submit(completion, settle)
        }
    }

    @Synchronized
    fun any(predicate: (T) -> Boolean): Boolean = entries.values.any { predicate(it.completion) }

    @Synchronized
    fun size(): Int = entries.size

    /** The completions still waiting, oldest first, for operators to inspect. */
    @Synchronized
    fun completions(): List<T> = entries.values.map(Entry<T>::completion)

    /** Retries the waiting completions [predicate] picks now; returns how many were tried. */
    @Synchronized
    fun retryMatching(predicate: (T) -> Boolean, settle: (T) -> Boolean): Int {
        val picked = entries.values.map(Entry<T>::completion).filter(predicate)
        picked.forEach { submit(it, settle) }
        return picked.size
    }

    /** Gives up the waiting completions [predicate] picks, unsettled; returns how many were dropped. */
    @Synchronized
    fun drop(predicate: (T) -> Boolean): Int {
        val keys = entries.filterValues { predicate(it.completion) }.keys.toList()
        keys.forEach(entries::remove)
        return keys.size
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun Entry<T>.advanceTo(now: Long) {
        val elapsed = if (now < observedAtMillis) {
            0L
        } else {
            try {
                Math.subtractExact(now, observedAtMillis)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
        }
        remainingDelayMillis = if (elapsed >= remainingDelayMillis) 0L else remainingDelayMillis - elapsed
        observedAtMillis = now
    }

    internal companion object {
        const val DEFAULT_RETRY_MILLIS = 5_000L
    }
}
