package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID

internal const val PVP_COMPLETION_RETRY_MILLIS = 5_000L

internal data class PendingPvpCompletion(
    val matchId: UUID,
    val battleId: UUID,
    val winnerId: UUID,
    val loserId: UUID,
    val nextAttemptEpochMillis: Long = 0L,
)

/** Keeps a finished PvP result retryable while its paired persistent record is unavailable. */
internal class PvpCompletionRetryQueue(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val retryMillis: Long = PVP_COMPLETION_RETRY_MILLIS,
) {
    private val entries = LinkedHashMap<UUID, PendingPvpCompletion>()

    init {
        require(retryMillis > 0)
    }

    @Synchronized
    fun submit(completion: PendingPvpCompletion, settle: (PendingPvpCompletion) -> Boolean): Boolean {
        if (settle(completion)) {
            entries.remove(completion.matchId)
            return true
        }
        entries[completion.matchId] = completion.copy(
            nextAttemptEpochMillis = currentTimeMillis() + retryMillis,
        )
        return false
    }

    @Synchronized
    fun retryDue(force: Boolean = false, settle: (PendingPvpCompletion) -> Boolean) {
        val now = currentTimeMillis()
        entries.values.toList().forEach { pending ->
            if (force || pending.nextAttemptEpochMillis <= now) submit(pending, settle)
        }
    }

    @Synchronized
    operator fun contains(matchId: UUID): Boolean = matchId in entries

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()
}
