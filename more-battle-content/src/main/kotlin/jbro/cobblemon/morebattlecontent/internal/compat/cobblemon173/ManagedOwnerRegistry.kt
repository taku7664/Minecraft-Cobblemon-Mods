package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.concurrent.atomic.AtomicBoolean

/** A scoped, transactional owner lookup for objects that do not live in a native persistent store. */
internal class ManagedOwnerRegistry<K : Any, V : Any> {
    private val lock = Any()
    private val owners = HashMap<K, Ownership<V>>()

    fun register(owner: V, keys: Collection<K>): AutoCloseable {
        val uniqueKeys = LinkedHashSet(keys)
        require(uniqueKeys.isNotEmpty()) { "At least one managed key is required" }

        val registrations = synchronized(lock) {
            val conflict = uniqueKeys.firstOrNull { key ->
                owners[key]?.let { existing -> existing.owner != owner } == true
            }
            require(conflict == null) { "Managed key is already registered to another owner: $conflict" }
            uniqueKeys.associateWith { key ->
                owners[key]?.also { it.registrations += 1 }
                    ?: Ownership(owner).also { owners[key] = it }
            }
        }

        return Registration {
            synchronized(lock) {
                registrations.forEach { (key, registration) ->
                    if (owners[key] === registration) {
                        registration.registrations -= 1
                        if (registration.registrations == 0) owners.remove(key)
                    }
                }
            }
        }
    }

    fun resolve(key: K): V? = synchronized(lock) { owners[key]?.owner }

    fun clear() = synchronized(lock) { owners.clear() }

    private class Ownership<V : Any>(
        val owner: V,
        var registrations: Int = 1,
    )

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
