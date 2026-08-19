package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.concurrent.atomic.AtomicBoolean

/** A scoped, transactional owner lookup for objects that do not live in a native persistent store. */
internal class ManagedOwnerRegistry<K : Any, V : Any> {
    private val lock = Any()
    private val owners = HashMap<K, V>()

    fun register(owner: V, keys: Collection<K>): AutoCloseable {
        val uniqueKeys = LinkedHashSet(keys)
        require(uniqueKeys.isNotEmpty()) { "At least one managed key is required" }

        synchronized(lock) {
            val conflict = uniqueKeys.firstOrNull { key ->
                owners[key]?.let { existing -> existing != owner } == true
            }
            require(conflict == null) { "Managed key is already registered to another owner: $conflict" }
            uniqueKeys.forEach { key -> owners[key] = owner }
        }

        return Registration {
            synchronized(lock) {
                uniqueKeys.forEach { key ->
                    if (owners[key] == owner) {
                        owners.remove(key)
                    }
                }
            }
        }
    }

    fun resolve(key: K): V? = synchronized(lock) { owners[key] }

    private class Registration(
        private val unregister: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                unregister()
            }
        }
    }
}
