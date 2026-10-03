package jbro.cobblemon.mcc.internal.battle

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.pokemon.Pokemon
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.battle.MccBattleTags
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks
import jbro.cobblemon.mcc.internal.mixin.BattleRegistryInvoker
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer

/**
 * Plays the battle entry transition between a battle's approval and its start, as the games do. Cobblemon starts a
 * battle in two steps: every `BATTLE_STARTED_PRE` subscriber may refuse it, then the battle is registered and
 * Showdown starts. MCC holds the second step's Showdown start: an approved wild, legendary or trainer battle waits,
 * already registered (so the player counts as in battle and no second battle can start), while the client covers its
 * screen, and starts the moment the client says it is covered. A refused battle never shows a transition.
 *
 * MCC's own content (Tower, Factory, League, PvP), battles with more than one player, and clients without the
 * transition start at once. A client that never answers is waited for [TIMEOUT_TICKS] at most.
 */
internal object BattleEntryHold {
    /** About two and a half times the longest cover, for a slow or stalled client. */
    const val TIMEOUT_TICKS = 80

    private class Held(val battle: PokemonBattle, val playerId: UUID, val dueTick: Int, val heldAt: Long = net.minecraft.Util.getMillis())

    /** Server thread only. */
    private val held = linkedMapOf<UUID, Held>()
    private var starting: UUID? = null
    private var server: MinecraftServer? = null

    /**
     * Called as Cobblemon is about to start Showdown for an approved [battle]; true when the start is held here and
     * Cobblemon should not start it now.
     */
    @JvmStatic
    fun intercept(battle: PokemonBattle): Boolean {
        if (battle.battleId == starting || battle.battleId in held) return false
        val server = server ?: return false
        return try {
            hold(server, battle)
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.warn("Battle {} could not be held for its entry transition", battle.battleId, failure)
            false
        }
    }

    /** [battle] is being stopped; a held one starts first so Cobblemon ends it the usual way. */
    @JvmStatic
    fun beforeStop(battle: PokemonBattle) {
        held.remove(battle.battleId)?.let { start(it, cancelOnFailure = false, reason = "stopped") }
    }

    fun registerServer() {
        ServerLifecycleEvents.SERVER_STARTED.register { server = it }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            // Cobblemon closes every battle as the server stops; a held one has no Showdown battle to close yet.
            held.clear()
            server = null
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (held.isEmpty()) return@register
            held.values.filter { server.tickCount >= it.dueTick }.forEach { due ->
                held.remove(due.battle.battleId)
                start(due, reason = "timeout")
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(BattleEntryReadyPayload.TYPE) { payload, context ->
            val waiting = held[payload.battleId] ?: return@registerGlobalReceiver
            if (waiting.playerId != context.player().uuid) return@registerGlobalReceiver
            held.remove(payload.battleId)
            start(waiting, reason = "covered")
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            // Start it so Cobblemon's own logout handling forfeits and closes it as any battle.
            held.values.filter { it.playerId == handler.player.uuid }.forEach {
                held.remove(it.battle.battleId)
                start(it, cancelOnFailure = false, reason = "disconnected")
            }
        }
    }

    private fun hold(server: MinecraftServer, battle: PokemonBattle): Boolean {
        val players = battle.actors.filterIsInstance<PlayerBattleActor>()
        val player = players.singleOrNull()?.entity ?: return false
        if (Cobblemon173BattleRuleHooks.isRegisteredBattle(battle.battleId)) return false
        val side = players.single().getSide()
        val opponents = side.getOppositeSide().actors.toList()
        val lead = opponents.firstOrNull()?.pokemonList?.firstOrNull()?.effectedPokemon
        val tag = MccBattleTags.of(battle.battleId)
        val entry = classify(
            playerCount = players.size,
            playerSideSize = side.actors.size,
            opponentTypes = opponents.map { it.type },
            contentStage = tag?.stage,
            contentTagged = tag != null,
            leadLabels = lead?.let(::labelsOf).orEmpty(),
        ) ?: return false
        if (!ServerPlayNetworking.canSend(player, BattleEntryHoldPayload.TYPE)) return false
        held[battle.battleId] = Held(battle, player.uuid, server.tickCount + TIMEOUT_TICKS)
        ServerPlayNetworking.send(player, BattleEntryHoldPayload(
            battleId = battle.battleId,
            style = entry,
            species = lead?.species?.resourceIdentifier,
            form = lead?.form?.name.orEmpty(),
            labels = lead?.let(::labelsOf).orEmpty(),
        ))
        return true
    }

    private fun labelsOf(pokemon: Pokemon): Set<String> = buildSet {
        if (pokemon.isAlpha) add("alpha")
        val labels = pokemon.species.labels
        if ("legendary" in labels || "mythical" in labels) add("legendary")
        if ("ultra_beast" in labels) add("ultra_beast")
    }

    /**
     * Which transition a battle gets, or null to start it at once: one player alone on their side, against wild
     * Pokémon (legendary when the lead is legendary, mythical or an Ultra Beast) or trainers, outside MCC's content
     * except its wild trainers.
     */
    fun classify(
        playerCount: Int,
        playerSideSize: Int,
        opponentTypes: List<ActorType>,
        contentStage: String?,
        contentTagged: Boolean,
        leadLabels: Set<String>,
    ): BattleEntryStyle? {
        if (playerCount != 1 || playerSideSize != 1 || opponentTypes.isEmpty()) return null
        if (contentTagged && contentStage?.startsWith("wild_trainer") != true) return null
        return when {
            opponentTypes.all { it == ActorType.WILD } ->
                if (leadLabels.any { it == "legendary" || it == "ultra_beast" }) BattleEntryStyle.LEGENDARY else BattleEntryStyle.WILD
            opponentTypes.all { it == ActorType.NPC } -> BattleEntryStyle.TRAINER
            else -> null
        }
    }

    private fun start(waiting: Held, cancelOnFailure: Boolean = true, reason: String) {
        val battle = waiting.battle
        MoreCobblemonContents.LOGGER.info("Held battle {} starts after {} ms ({})", battle.battleId,
            net.minecraft.Util.getMillis() - waiting.heldAt, reason)
        if (battle.ended || BattleRegistry.getBattle(battle.battleId) !== battle) {
            cancel(waiting)
            return
        }
        starting = battle.battleId
        try {
            (BattleRegistry as Any as BattleRegistryInvoker).`mcc$startShowdown`(battle)
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.error("Held battle {} failed to start", battle.battleId, failure)
            if (cancelOnFailure) abandon(waiting)
        } finally {
            starting = null
        }
    }

    /** Showdown refused the battle: take it out of the registry so no one stays stuck in it. */
    private fun abandon(waiting: Held) {
        val battle = waiting.battle
        try {
            battle.actors.forEach { actor -> actor.pokemonList.forEach { it.entity?.battleId = null } }
            battle.actors.filter { it.type == ActorType.WILD }.forEach { actor ->
                actor.pokemonList.forEach { pokemon -> pokemon.originalPokemon.entity?.battleId = null }
            }
            BattleRegistry.closeBattle(battle)
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.warn("Held battle {} could not be closed cleanly", battle.battleId, failure)
        }
        cancel(waiting)
    }

    private fun cancel(waiting: Held) {
        val player = server?.playerList?.getPlayer(waiting.playerId) ?: return
        if (ServerPlayNetworking.canSend(player, BattleEntryCancelPayload.TYPE)) {
            ServerPlayNetworking.send(player, BattleEntryCancelPayload(waiting.battle.battleId))
        }
    }
}
