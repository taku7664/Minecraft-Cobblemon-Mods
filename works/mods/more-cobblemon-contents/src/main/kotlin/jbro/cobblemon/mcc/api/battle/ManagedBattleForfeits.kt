package jbro.cobblemon.mcc.api.battle

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.platform.events.PlatformEvents
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/**
 * Players who left a battle by dying or logging out. Cobblemon ends such a battle as a forced tie (`>forcetie`) with
 * nobody winning, which content would otherwise read as "no contest" and let a losing player walk away for free.
 * Content reads [of] when a battle ends without a winner and counts these players as having lost it.
 */
object ManagedBattleForfeits {
    private val ledger = BattleForfeitLedger()

    /** The players recorded as having left [battleId] by dying or logging out. */
    fun of(battleId: UUID): Set<UUID> = ledger.of(battleId)

    internal fun register() {
        // Before Cobblemon's own death subscriber, which stops the battle.
        PlatformEvents.PLAYER_DEATH.subscribe(Priority.HIGHEST) { event -> mark(event.player) }
        // Before every default-phase disconnect listener: Cobblemon's logout and each content's own cleanup end the
        // battle there, in an order Fabric does not fix.
        val early = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "battle_forfeits")
        ServerPlayConnectionEvents.DISCONNECT.addPhaseOrdering(early, Event.DEFAULT_PHASE)
        ServerPlayConnectionEvents.DISCONNECT.register(early) { handler, _ -> mark(handler.player) }
    }

    private fun mark(player: ServerPlayer) {
        // A stopping server disconnects everyone; that is nobody's loss.
        if (!player.server.isRunning) return
        val battle = BattleRegistry.getBattleByParticipatingPlayer(player) ?: return
        if (battle.ended) return
        ledger.mark(battle.battleId, player.uuid)
    }
}

/** Forfeits by battle, forgotten after [retentionNanos] so finished battles do not pile up. */
internal class BattleForfeitLedger(
    private val retentionNanos: Long = 10L * 60 * 1_000_000_000,
    private val clock: () -> Long = System::nanoTime,
) {
    private class Entry(val players: MutableSet<UUID>, var markedAt: Long)

    private val entries = HashMap<UUID, Entry>()

    @Synchronized
    fun mark(battleId: UUID, playerId: UUID) {
        val now = clock()
        entries.values.removeIf { now - it.markedAt > retentionNanos }
        entries.getOrPut(battleId) { Entry(LinkedHashSet(), now) }.also {
            it.players += playerId
            it.markedAt = now
        }
    }

    @Synchronized
    fun of(battleId: UUID): Set<UUID> {
        val entry = entries[battleId] ?: return emptySet()
        if (clock() - entry.markedAt > retentionNanos) return emptySet()
        return entry.players.toSet()
    }
}
