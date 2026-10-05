package jbro.cobblemon.mcc.api.battle

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.JsonParser
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.compat.cobblemon173.*
import jbro.cobblemon.mcc.internal.battle.BattleCompletionRetryQueue
import jbro.cobblemon.mcc.internal.command.MccAdminSource
import jbro.cobblemon.mcc.internal.command.MccPendingResult
import net.minecraft.network.chat.Component
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Experimental single PvE API. The caller owns progression; MCC owns battle lifecycle and clones. */
object ManagedPveBattles {
    enum class Format { SINGLE, DOUBLE }
    enum class Outcome { WIN, LOSS, CANCELLED }
    data class Request(
        val transactionId: UUID, val contentId: String, val trainerId: String, val trainerNameKey: String,
        val lockedParty: List<String>, val opponentProperties: List<String>,
        val format: Format = Format.SINGLE, val mechanic: MajorBattleMechanic? = null, val skill: Int = 3,
        val appearance: jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin? = null,
        /** Where in the content this battle stands, for the clients' [MccBattleTag]; the trainer id is its opponent. */
        val stage: String? = null,
        /**
         * What the clients are told this battle is, when that differs from [contentId], [stage] and [trainerId]: an AI
         * test runs under its own content id but should look and sound like the content battle it stands in for.
         */
        val clientTag: MccBattleTag? = null,
    )
    private data class Active(val playerId: UUID, val request: Request, val complete: (Outcome) -> Unit)
    private data class Completion(val active: Active, val outcome: Outcome)
    private class State {
        val battles = linkedMapOf<UUID, Active>()
        val pending = BattleCompletionRetryQueue<UUID, Completion>({ it.active.request.transactionId })
        var ticks = 0
    }
    private val servers = java.util.IdentityHashMap<MinecraftServer, State>()

    fun snapshotParty(player: ServerPlayer, levelCap: Int): List<String> {
        check(player.server.isSameThread)
        require(levelCap in 1..100)
        require(BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) == null) { "battle_active" }
        val party = Cobblemon.storage.getParty(player).toList()
        require(party.size in 1..6 && party.map { it.uuid }.distinct().size == party.size) { "party_required" }
        require(party.all { it.level <= levelCap && !it.isBattleClone() }) { "level_cap" }
        return party.map { it.saveToJSON(player.registryAccess()).toString() }
    }

    fun start(player: ServerPlayer, request: Request, complete: (Outcome) -> Unit): UUID? {
        check(player.server.isSameThread)
        require(ManagedBattleContentIds.isValid(request.contentId) && ManagedBattleContentIds.isValid(request.trainerId))
        require(request.trainerNameKey.isNotBlank() && request.trainerNameKey.length <= 256)
        require(request.skill in 0..5 && request.lockedParty.size in 1..6 && request.opponentProperties.size in 1..6)
        require(request.lockedParty.all { it.length <= 524288 } && request.opponentProperties.all { it.length <= 2048 })
        if (request.format == Format.DOUBLE) require(request.opponentProperties.size >= 2)
        val state = servers.getOrPut(player.server, ::State)
        if (state.battles.values.any { it.playerId == player.uuid } || state.pending.any { it.active.playerId == player.uuid }) return null
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) return null
        val active = Active(player.uuid, request, complete)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(battleId: UUID, outcome: Outcome) {
            completed.set(true)
            val settlement = Runnable {
                state.battles.remove(battleId)
                state.pending.submit(Completion(active, outcome), ::settleOne)
            }
            if (player.server.isSameThread) settlement.run() else player.server.execute(settlement)
        }
        val runtime = ManagedPveBattleRuntime(
            playerResolver = { player.server.playerList.getPlayer(it) },
            sessionCompletion = { _, _, id, outcome -> finish(id, if (outcome == PveOutcome.WIN) Outcome.WIN else Outcome.LOSS) },
            sessionCancellation = { _, _, id -> finish(id, Outcome.CANCELLED) },
        )
        val profile = BattleTrainerProfile.balanced(request.skill)
        val prepared = ManagedPvePrepared(player.uuid, request.contentId, request.trainerId, request.trainerNameKey,
            PveFormat.valueOf(request.format.name), request.mechanic,
            request.lockedParty.map { raw ->
                BattlePokemon.Companion.safeCopyOf(Pokemon().loadFromJSON(player.registryAccess(), JsonParser.parseString(raw).asJsonObject))
                    .also { it.effectedPokemon.heal() }
            }, request.opponentProperties.map { raw ->
                val properties = PokemonProperties.Companion.parse(raw)
                require(properties.species != null && properties.species != "random") { "Unknown or random opponent species" }
                require(properties.level in 1..Cobblemon.config.maxPokemonLevel) { "Opponent level must be explicit and within Cobblemon's maximum" }
                require(!properties.moves.isNullOrEmpty()) { "Opponent moves must be explicit" }
                BattlePokemon.Companion.safeCopyOf(Cobblemon173CatalogPokemonCreator.create(properties, properties.form)).also {
                    protectManagedOpponent(it)
                }
            }, profile, BattleBrainSelectionContext(request.contentId, BattleEncounterRole.BOSS, profile.difficulty.tier), request.transactionId,
            appearance = request.appearance)
        val tag = request.clientTag ?: MccBattleTag(request.contentId, request.stage, request.trainerId)
        return when (val result = MccBattleTags.during(setOf(player.uuid), tag) { runtime.startManaged(prepared) }) {
            is PveLaunchResult.Started -> result.battleId.also {
                if (!completed.get()) state.battles[it] = active
            }
            PveLaunchResult.Unavailable -> null
        }
    }

    fun cancel(server: MinecraftServer, playerId: UUID) {
        check(server.isSameThread)
        servers[server]?.battles?.filterValues { it.playerId == playerId }?.keys?.toList()?.forEach(Cobblemon173ManagedBattleTermination::end)
    }

    /** The shared operator view: results waiting to be settled, and players with a battle or result here. */
    internal val adminSource = object : MccAdminSource {
        override val label: Component = Component.translatable("command.${MoreCobblemonContents.MOD_ID}.admin.source.managed_pve")

        override fun status(server: MinecraftServer): List<Component> {
            val state = servers[server]
            return listOf(Component.translatable("command.${MoreCobblemonContents.MOD_ID}.admin.status.managed_pve",
                state?.battles?.size ?: 0, state?.pending?.size() ?: 0))
        }

        override fun pending(server: MinecraftServer): List<MccPendingResult> =
            servers[server]?.pending?.completions().orEmpty().map { pending ->
                val request = pending.active.request
                MccPendingResult(pending.active.playerId, null, "${request.contentId} ${request.trainerId} ${pending.outcome.name.lowercase()}")
            }

        override fun retryPending(server: MinecraftServer, playerId: UUID?): Int =
            servers[server]?.pending?.retryMatching({ playerId == null || it.active.playerId == playerId }, ::settleOne) ?: 0

        override fun dropPending(server: MinecraftServer, playerId: UUID?): Int =
            servers[server]?.pending?.drop { playerId == null || it.active.playerId == playerId } ?: 0

        override fun busy(server: MinecraftServer, playerId: UUID): Boolean {
            val state = servers[server] ?: return false
            return state.battles.values.any { it.playerId == playerId } || state.pending.any { it.active.playerId == playerId }
        }
    }

    internal fun registerLifecycle() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            val state = servers[server]
            if (state != null && ++state.ticks % 20 == 0) state.pending.retryDue(settle = ::settleOne)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            cancel(server, handler.player.uuid)
            servers[server]?.pending?.retryDue(force = true, settle = ::settleOne)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            servers[server]?.let { state ->
                state.battles.keys.toList().forEach(Cobblemon173ManagedBattleTermination::end)
                state.pending.retryDue(force = true, settle = ::settleOne)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { servers.remove(it) }
    }

    private fun settleOne(pending: Completion): Boolean = try {
        pending.active.complete(pending.outcome)
        true
    } catch (failure: RuntimeException) {
        MoreCobblemonContents.LOGGER.error("Managed PvE result ${pending.active.request.transactionId} will be retried", failure)
        false
    } catch (failure: LinkageError) {
        MoreCobblemonContents.LOGGER.error("Managed PvE result ${pending.active.request.transactionId} will be retried", failure)
        false
    }
}
