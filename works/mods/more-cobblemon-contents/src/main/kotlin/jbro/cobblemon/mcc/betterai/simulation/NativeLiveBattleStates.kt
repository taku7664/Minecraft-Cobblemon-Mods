package jbro.cobblemon.mcc.betterai.simulation

import java.security.MessageDigest

/**
 * Live battles Showdown serialized, held under a digest so a search token names one instead of carrying it: every
 * position of every world refers to its root's live battle, and a token is copied at each node.
 *
 * Shared by all workers of the process, since a session's later turn may land on another worker. Only recent states
 * are kept; a token whose state was dropped can no longer be rebuilt.
 */
internal object NativeLiveBattleStates {
    private const val LIMIT = 64
    private val states = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > LIMIT
    }

    fun register(json: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        val key = digest.take(16).joinToString("") { "%02x".format(it) }
        synchronized(states) { states[key] = json }
        return key
    }

    fun get(key: String): String? = synchronized(states) { states[key] }
}
