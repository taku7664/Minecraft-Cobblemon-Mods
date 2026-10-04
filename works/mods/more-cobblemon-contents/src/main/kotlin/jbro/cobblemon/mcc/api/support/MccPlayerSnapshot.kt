package jbro.cobblemon.mcc.api.support

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import jbro.cobblemon.mcc.internal.bp.BattlePointService
import jbro.cobblemon.mcc.internal.wiki.WikiServer
import net.minecraft.server.MinecraftServer

/**
 * A player's standing in the contents, for operators looking into what the player reported: what the wiki shows
 * them (BP, records, each content's section) plus their latest BP transactions with times. Read on the server thread.
 */
object MccPlayerSnapshot {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))

    fun of(server: MinecraftServer, playerId: UUID, bpHistory: Int = 20): JsonObject = WikiServer.dashboard(server, playerId).apply {
        remove("updated")
        add("bp_history", JsonArray().also { history ->
            if (!BattlePointService.isAvailable(server)) return@also
            // Newest first.
            for (entry in BattlePointService.history(server, playerId, bpHistory.coerceIn(1, 100))) history.add(JsonObject().apply {
                addProperty("at", TIME.format(Instant.ofEpochMilli(entry.recordedAtEpochMillis)) + " KST")
                addProperty("kind", entry.kind.name)
                addProperty("amount", entry.requestedValue)
                addProperty("balance_before", entry.balanceBefore)
                addProperty("balance_after", entry.balanceAfter)
                addProperty("source", entry.sourceId.value)
                if (entry.reason.isNotBlank()) addProperty("reason", entry.reason)
            })
        })
    }
}
