package jbro.cobblemon.morebattlecontent.internal.battle

/** Retains a completed battle result until its persistent settlement succeeds or becomes stale. */
internal class BattleCompletionRetryQueue<K, T>(
    private val keyOf: (T) -> K,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    private data class Entry<T>(val completion: T, val nextAttemptEpochMillis: Long)

    private val entries = LinkedHashMap<K, Entry<T>>()

    init {
        require(retryMillis > 0)
    }

    @Synchronized
    fun submit(completion: T, settle: (T) -> Boolean): Boolean {
        val key = keyOf(completion)
        if (settle(completion)) {
            entries.remove(key)
            return true
        }
        entries[key] = Entry(completion, currentTimeMillis() + retryMillis)
        return false
    }

    @Synchronized
    fun retryDue(force: Boolean = false, settle: (T) -> Boolean) {
        val now = currentTimeMillis()
        entries.values.map(Entry<T>::completion).forEach { completion ->
            val entry = entries[keyOf(completion)] ?: return@forEach
            if (force || entry.nextAttemptEpochMillis <= now) submit(completion, settle)
        }
    }

    @Synchronized
    fun any(predicate: (T) -> Boolean): Boolean = entries.values.any { predicate(it.completion) }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()

    internal companion object {
        const val DEFAULT_RETRY_MILLIS = 5_000L
    }
}
