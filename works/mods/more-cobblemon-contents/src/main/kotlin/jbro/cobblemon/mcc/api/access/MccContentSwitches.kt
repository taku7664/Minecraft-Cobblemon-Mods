package jbro.cobblemon.mcc.api.access

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.hub.ContentSwitchConfigFile
import net.minecraft.server.MinecraftServer

/** True while [playerId] has a run of a content under way: a streak, a tournament, a room or a match. */
fun interface ContentRunProbe {
    fun hasRun(server: MinecraftServer, playerId: UUID): Boolean
}

/**
 * Turns a switched-off content away. Only players with no run under way are turned away: a run already started
 * goes on to its end, so switching a content off never costs anyone a streak, a tournament or a match. A probe
 * that fails counts as no run, so a broken probe never opens a closed content. [S] is the server the probes read.
 */
internal class ContentSwitchPolicy<S>(
    private val enabled: (String) -> Boolean,
    private val reportFailure: (Throwable) -> Unit = {},
) {
    private class Probe<S>(val contentId: String, val hasRun: (S, UUID) -> Boolean)

    private val probes = CopyOnWriteArrayList<Probe<S>>()

    fun registerRunProbe(contentId: String, hasRun: (S, UUID) -> Boolean): AutoCloseable {
        val entry = Probe(contentId, hasRun)
        probes += entry
        return AutoCloseable { probes.remove(entry) }
    }

    fun check(server: S, playerId: UUID, contentId: String): ContentAccessDecision {
        if (enabled(contentId) || hasRun(server, playerId, contentId)) return ContentAccessDecision.Allowed
        return ContentAccessDecision.Denied(MccContentSwitches.PAUSED_REASON_KEY, MccContentSwitches.PAUSED_CODE)
    }

    private fun hasRun(server: S, playerId: UUID, contentId: String): Boolean =
        probes.filter { it.contentId == contentId }.any { entry ->
            try {
                entry.hasRun(server, playerId)
            } catch (failure: RuntimeException) {
                reportSafely(failure)
            } catch (failure: LinkageError) {
                reportSafely(failure)
            }
        }

    private fun reportSafely(failure: Throwable): Boolean {
        try { reportFailure(failure) } catch (_: RuntimeException) { /* Never fail open through logging. */ }
        return false
    }
}

/**
 * The content switches of `config/more-cobblemon-contents/content_switches.json`. [BattleContentAccess] applies them
 * before any other policy, so a content that checks access needs nothing more; a content that creates its run before
 * its first battle must check START itself before it does, and every content registers a [ContentRunProbe].
 */
object MccContentSwitches {
    const val PAUSED_REASON_KEY: String = "screen.${MoreCobblemonContents.MOD_ID}.access.paused"
    const val PAUSED_CODE: String = "content_paused"

    private val policy = ContentSwitchPolicy<MinecraftServer>(::enabled) { MoreCobblemonContents.LOGGER.error("Content run probe failed", it) }

    /** Register while initializing; [probe] runs on the server thread. */
    fun registerRunProbe(contentId: String, probe: ContentRunProbe): AutoCloseable = policy.registerRunProbe(contentId, probe::hasRun)

    /** False only for an installed content an admin switched off. */
    fun enabled(contentId: String): Boolean =
        !ContentSwitchConfigFile.switchable(contentId) || ContentSwitchConfigFile.current.enabled(contentId)

    /** Server thread. Denied when [contentId] is switched off and [playerId] has no run of it under way. */
    fun check(server: MinecraftServer, playerId: UUID, contentId: String): ContentAccessDecision =
        policy.check(server, playerId, contentId)
}
