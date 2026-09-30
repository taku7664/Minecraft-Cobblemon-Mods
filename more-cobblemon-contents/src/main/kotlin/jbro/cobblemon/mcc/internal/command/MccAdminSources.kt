package jbro.cobblemon.mcc.internal.command

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

/** A battle result a content could not save yet and keeps retrying. */
data class MccPendingResult(val playerId: UUID, val battleId: UUID?, val detail: String)

/**
 * One content's part in the shared operator commands: what `/mcc status` shows for it, the results it is still
 * retrying for `/mcc battle pending`, and whether a player has live progress that record edits would race with.
 * Every call runs on the server thread.
 */
interface MccAdminSource {
    /** Shown before this source's lines, such as the content's name. */
    val label: Component

    fun status(server: MinecraftServer): List<Component>

    fun pending(server: MinecraftServer): List<MccPendingResult> = emptyList()

    /** Retries the waiting results, only [playerId]'s when given; returns how many were tried. */
    fun retryPending(server: MinecraftServer, playerId: UUID?): Int = 0

    /** Gives up the waiting results, only [playerId]'s when given; returns how many were dropped. */
    fun dropPending(server: MinecraftServer, playerId: UUID?): Int = 0

    /** True while [playerId] has a session or result here that holds progress outside the record store. */
    fun busy(server: MinecraftServer, playerId: UUID): Boolean = false
}

object MccAdminSources {
    private val sources = CopyOnWriteArrayList<MccAdminSource>()

    fun register(source: MccAdminSource): AutoCloseable {
        sources += source
        return AutoCloseable { sources.remove(source) }
    }

    fun all(): List<MccAdminSource> = sources.toList()
}
