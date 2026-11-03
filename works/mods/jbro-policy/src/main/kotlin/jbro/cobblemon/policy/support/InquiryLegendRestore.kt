package jbro.cobblemon.policy.support

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.legend.LegendRestore
import net.minecraft.server.MinecraftServer

/** Runs [LegendRestore] for an inquiry on the server thread, from the review worker. */
internal object InquiryLegendRestore {
    /** Null when the inquiry does not ask for a Legend back, or it could not be done and an operator should look. */
    fun attempt(server: MinecraftServer, inquiry: Inquiry): LegendRestore.Outcome? =
        onServer(server, inquiry) { LegendRestore.attempt(server, inquiry.playerId, inquiry.reason) }

    /** What to put to an operator for this inquiry, or null when it is not about a Legend to spawn. */
    fun proposal(server: MinecraftServer, inquiry: Inquiry): LegendRestore.Ask? =
        onServer(server, inquiry) { LegendRestore.proposal(server, inquiry.playerId, inquiry.reason) }

    private fun <T : Any> onServer(server: MinecraftServer, inquiry: Inquiry, action: () -> T?): T? {
        val future = CompletableFuture<T?>()
        val mayStart = AtomicBoolean(true)
        server.execute {
            if (!mayStart.compareAndSet(true, false)) return@execute
            try {
                future.complete(action())
            } catch (failure: Exception) {
                JbroPolicy.LOGGER.warn("Could not restore a Legend for inquiry {}", inquiry.id, failure)
                future.complete(null)
            }
        }
        return try { future.get(10, TimeUnit.SECONDS) } catch (_: Exception) {
            // A queued spawn cannot start after this timeout; a running one still records itself in the spawn log.
            mayStart.set(false)
            null
        }
    }
}
