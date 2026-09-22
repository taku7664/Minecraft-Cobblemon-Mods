package jbro.cobblemon.morebattlecontent.client

import java.util.concurrent.CopyOnWriteArrayList
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

internal class ClientSessionResetRegistry {
    private val entries = CopyOnWriteArrayList<Entry>()

    fun add(name: String, reset: () -> Unit) {
        require(name.isNotBlank())
        entries += Entry(name, reset)
    }

    fun resetAll(onFailure: (String, Throwable) -> Unit) {
        entries.forEach { entry ->
            try {
                entry.reset()
            } catch (exception: RuntimeException) {
                reportFailureSafely(entry.name, exception, onFailure)
            } catch (error: LinkageError) {
                reportFailureSafely(entry.name, error, onFailure)
            }
        }
    }

    private fun reportFailureSafely(
        name: String,
        failure: Throwable,
        onFailure: (String, Throwable) -> Unit,
    ) {
        try {
            onFailure(name, failure)
        } catch (_: RuntimeException) {
            // A broken reporter cannot leave later client state uncleared.
        } catch (_: LinkageError) {
            // Optional client integrations may fail while reporting their own reset failure.
        }
    }

    private data class Entry(val name: String, val reset: () -> Unit)
}

/** Clears every piece of client state that belongs to one server or client world. */
internal object MbcClientSessionReset {
    private val registry = ClientSessionResetRegistry()

    fun registerEvents() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetAll() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> resetAll() }
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register { _, _ -> resetAll() }
    }

    fun onReset(name: String, reset: () -> Unit) {
        registry.add(name, reset)
    }

    private fun resetAll() {
        registry.resetAll { name, exception ->
            MoreBattleContent.LOGGER.error("Failed to clear MBC client state for {}", name, exception)
        }
    }
}
