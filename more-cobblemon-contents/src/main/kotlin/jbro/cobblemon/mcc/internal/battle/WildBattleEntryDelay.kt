package jbro.cobblemon.mcc.internal.battle

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.net.messages.server.BattleChallengePacket
import com.cobblemon.mod.common.net.serverhandling.ChallengeHandler
import com.cobblemon.mod.common.util.canInteractWith
import com.cobblemon.mod.common.util.party
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * Plays the battle entry transition before a wild battle, as the games do: a challenge to a wild Pokémon is held,
 * the client covers its screen, and once it is covered Cobblemon's own challenge handling runs, so its checks, its
 * messages and the battle itself are unchanged. The Pokémon is kept busy meanwhile so no one else takes it.
 *
 * A client that cannot show the transition, and a challenge Cobblemon would refuse anyway, go through at once.
 */
internal object WildBattleEntryDelay {
    /**
     * Ticks the server waits: until the client's flashes and cover end (cobblemon-ui's `BattleEntryTimeline`:
     * 360 + 620 ms for a legendary, 140 + 420 ms otherwise), and a tick more so the cover has finished.
     */
    const val LEGENDARY_WAIT_TICKS = 21
    const val WILD_WAIT_TICKS = 12

    private val LEGENDARY_LABELS = setOf(CobblemonPokemonLabels.LEGENDARY, CobblemonPokemonLabels.MYTHICAL,
        CobblemonPokemonLabels.ULTRA_BEAST)

    private class Pending(
        val playerId: UUID,
        val packet: BattleChallengePacket,
        val entity: PokemonEntity,
        val dueTick: Int,
        val lock: Any = Any(),
    )

    /** Server thread only. */
    private val pending = linkedMapOf<UUID, Pending>()
    private var replaying = false

    fun waitTicks(legendary: Boolean) = if (legendary) LEGENDARY_WAIT_TICKS else WILD_WAIT_TICKS

    /**
     * Called before Cobblemon handles [packet]; true when the challenge is held for the transition and Cobblemon's
     * handling should stop here, to run again once the screen is covered.
     */
    @JvmStatic
    fun intercept(packet: BattleChallengePacket, server: MinecraftServer, player: ServerPlayer): Boolean {
        if (replaying) return false
        // A second press while one is held changes nothing.
        if (player.uuid in pending) return true
        val target = player.level().getEntity(packet.targetedEntityId) as? PokemonEntity ?: return false
        if (target.owner != null || player.party()[packet.selectedPokemonId] == null) return false
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) return false
        if (!player.canInteractWith(target, Cobblemon.config.battleWildMaxDistance) || !target.canBattle(player)) return false
        if (!ServerPlayNetworking.canSend(player, StartBattleEntryPayload.TYPE)) return false
        val species = target.pokemon.species
        val legendary = species.labels.any { it in LEGENDARY_LABELS }
        val held = Pending(player.uuid, packet, target, server.tickCount + waitTicks(legendary))
        target.busyLocks.add(held.lock)
        pending[player.uuid] = held
        ServerPlayNetworking.send(player, StartBattleEntryPayload(species.resourceIdentifier, trainer = false))
        return true
    }

    fun registerServer() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (pending.isEmpty()) return@register
            pending.values.filter { server.tickCount >= it.dueTick }.forEach { start(server, it) }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            pending.remove(handler.player.uuid)?.let(::release)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            pending.values.forEach(::release)
            pending.clear()
        }
    }

    private fun release(held: Pending) {
        held.entity.busyLocks.remove(held.lock)
    }

    private fun start(server: MinecraftServer, held: Pending) {
        pending.remove(held.playerId)
        release(held)
        val player = server.playerList.getPlayer(held.playerId) ?: return
        replaying = true
        try {
            ChallengeHandler.handle(held.packet, server, player)
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.error("A held wild challenge from {} failed to start", player.uuid, failure)
        } finally {
            replaying = false
        }
        // Something changed while the screen was covered; Cobblemon has said why, and the client opens again.
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) == null &&
            ServerPlayNetworking.canSend(player, CancelBattleEntryPayload.TYPE)) {
            ServerPlayNetworking.send(player, CancelBattleEntryPayload)
        }
    }
}
