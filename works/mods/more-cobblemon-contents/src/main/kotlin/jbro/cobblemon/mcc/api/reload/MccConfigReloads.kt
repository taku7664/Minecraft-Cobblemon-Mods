package jbro.cobblemon.mcc.api.reload

import java.util.concurrent.CopyOnWriteArrayList
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.server.MinecraftServer

/** What `/mcc reload` did with one config file. */
sealed interface MccReloadResult {
    /** The file as an admin knows it, such as `hub_tabs.json`. */
    val file: String

    /** The file is in use now; [notes] tells the admin more, such as entries that were missing and took their defaults. */
    data class Applied(override val file: String, val notes: List<String> = emptyList()) : MccReloadResult

    /** The file was rejected for [reason]; what was in use before the reload stays in use. */
    data class Kept(override val file: String, val reason: String) : MccReloadResult
}

/** Rereads config files on the server thread. A rejected file must leave its last good values in use. */
fun interface MccConfigReloader {
    fun reload(server: MinecraftServer): List<MccReloadResult>
}

/** The reloaders of one game, in registration order; [S] is the server they run against. */
internal class ConfigReloadRegistry<S>(private val reportFailure: (String, Throwable) -> Unit = { _, _ -> }) {
    private class Entry<S>(val name: String, val reload: (S) -> List<MccReloadResult>)

    private val entries = CopyOnWriteArrayList<Entry<S>>()

    fun register(name: String, reload: (S) -> List<MccReloadResult>): AutoCloseable {
        require(name.isNotBlank()) { "A config reloader needs a name" }
        val entry = Entry(name, reload)
        entries += entry
        return AutoCloseable { entries.remove(entry) }
    }

    /** Runs every reloader; one that throws is reported under its name and does not stop the rest. */
    fun reloadAll(server: S): List<MccReloadResult> = entries.flatMap { entry ->
        try {
            entry.reload(server)
        } catch (failure: RuntimeException) {
            failed(entry, failure)
        } catch (failure: LinkageError) {
            failed(entry, failure)
        }
    }

    private fun failed(entry: Entry<S>, failure: Throwable): List<MccReloadResult> {
        try { reportFailure(entry.name, failure) } catch (_: RuntimeException) { /* The result below still reports it. */ }
        return listOf(MccReloadResult.Kept(entry.name, failure.message ?: failure.javaClass.simpleName))
    }
}

/**
 * The config files `/mcc reload` rereads without a data pack reload. MCC and its addons register theirs while they
 * initialize. Data pack resources are not reread here: `/reload` reopens the packs, which this cannot do safely.
 */
object MccConfigReloads {
    private val registry = ConfigReloadRegistry<MinecraftServer> { name, failure ->
        MoreCobblemonContents.LOGGER.error("Config reload {} failed", name, failure)
    }

    /** [name] labels the result when [reloader] itself fails, so name it after its file. */
    fun register(name: String, reloader: MccConfigReloader): AutoCloseable = registry.register(name, reloader::reload)

    /** Runs every reloader in registration order on the server thread. */
    fun reloadAll(server: MinecraftServer): List<MccReloadResult> = registry.reloadAll(server)
}
