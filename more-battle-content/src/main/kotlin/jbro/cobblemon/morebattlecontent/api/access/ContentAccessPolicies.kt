package jbro.cobblemon.morebattlecontent.api.access

import java.util.UUID

enum class ContentAccessAction { OPEN, MUTATE, START }

sealed interface ContentAccessDecision {
    data object Allowed : ContentAccessDecision
    data class Denied(val reasonKey: String, val code: String, val arguments: List<String> = emptyList()) : ContentAccessDecision {
        init {
            require(reasonKey.isNotBlank() && reasonKey.length <= 256)
            require(code.isNotBlank() && code.length <= 128)
            require(arguments.size <= 8 && arguments.all { it.length <= 256 })
        }
    }
}

fun interface ContentAccessPolicy {
    fun check(playerId: UUID, contentId: String, action: ContentAccessAction): ContentAccessDecision
}

/** One server's policies. No registration means unchanged standalone MBC behavior. */
class ContentAccessPolicies(private val reportFailure: (Throwable) -> Unit = {}) {
    private data class Entry(val ids: Set<String>, val policy: ContentAccessPolicy)
    private val entries = linkedMapOf<UUID, Entry>()

    @Synchronized
    fun register(contentIds: Set<String>, policy: ContentAccessPolicy): AutoCloseable {
        require(contentIds.isNotEmpty() && contentIds.all { it.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")) })
        val token = UUID.randomUUID()
        entries[token] = Entry(contentIds.toSet(), policy)
        return AutoCloseable { synchronized(this) { entries.remove(token) } }
    }

    fun check(playerId: UUID, contentId: String, action: ContentAccessAction): ContentAccessDecision {
        val snapshot = synchronized(this) { entries.values.filter { contentId in it.ids } }
        for (entry in snapshot) {
            val result = try {
                entry.policy.check(playerId, contentId, action)
            } catch (failure: RuntimeException) {
                failed(failure)
            } catch (failure: LinkageError) {
                failed(failure)
            }
            if (result is ContentAccessDecision.Denied) return result
        }
        return ContentAccessDecision.Allowed
    }

    private fun failed(failure: Throwable): ContentAccessDecision.Denied {
        try { reportFailure(failure) } catch (_: RuntimeException) { /* Never fail open through logging. */ }
        return ContentAccessDecision.Denied("screen.cobblemon_more_battle_content.access.provider_failed", "provider_failed")
    }
}
