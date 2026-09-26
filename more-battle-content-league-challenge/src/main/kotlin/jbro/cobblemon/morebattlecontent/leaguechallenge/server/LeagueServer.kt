package jbro.cobblemon.morebattlecontent.leaguechallenge.server

import com.google.gson.Gson
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.access.*
import jbro.cobblemon.morebattlecontent.api.battle.ManagedPveBattles
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.morebattlecontent.api.rewards.BattlePointRewards
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.leaguechallenge.MoreBattleContentLeagueChallenge as Mod
import jbro.cobblemon.morebattlecontent.leaguechallenge.network.*
import jbro.cobblemon.morebattlecontent.leaguechallenge.system.*
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.*
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** All mutations execute on the server thread. Clients send intent, never outcomes or cap values. */
object LeagueServer {
    private data class Session(val pos: BlockPos, val dimension: String, val terminalId: UUID,
        val nonce: UUID = UUID.randomUUID(), var touched: Long = 0,
        val requests: LinkedHashMap<UUID, LeagueIntentPayload> = linkedMapOf())
    private val sessions = mutableMapOf<UUID, Session>()
    private val lastRequestTick = mutableMapOf<UUID, Int>()
    private var access: AutoCloseable? = null
    private val gson = Gson()
    private const val CONTENT = "${Mod.MOD_ID}:league"
    private const val ERROR_PREFIX = "message.${Mod.MOD_ID}."

    fun register() {
        PayloadTypeRegistry.playS2C().register(LeagueStatePayload.TYPE, LeagueStatePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(LeagueIntentPayload.TYPE, LeagueIntentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(LeagueIntentPayload.TYPE) { payload, context ->
            handle(context.player(), payload)
        }
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            access?.close()
            access = BattleContentAccess.register(server, setOf(ManagedBattleContentIds.BATTLE_TOWER, ManagedBattleContentIds.BATTLE_FACTORY)) { player, _, _ ->
                val catalog = LeagueCatalogResources.current
                if (catalog != null && LeagueSavedData.get(server).read(catalog.id, player).champion) ContentAccessDecision.Allowed
                else ContentAccessDecision.Denied(ERROR_PREFIX + "champion_required", "champion_required")
            }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { LeagueSavedData.get(it).cancelInterruptedRuns() }
        ServerLifecycleEvents.SERVER_STOPPED.register { access?.close(); access = null; sessions.clear(); lastRequestTick.clear(); LeagueCatalogResources.clear() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> reconcileSafely(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            sessions.remove(handler.player.uuid)
            lastRequestTick.remove(handler.player.uuid)
            LeagueCatalogResources.current?.let { catalog ->
                try {
                    val storage = LeagueSavedData.get(server)
                    val state = storage.read(catalog.id, handler.player.uuid)
                    storage.write(catalog.id, handler.player.uuid, LeagueEngine(catalog).cancel(state))
                } catch (failure: RuntimeException) { Mod.LOGGER.error("Could not cancel disconnected League run", failure) }
            }
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (server.tickCount % 200 == 0) server.playerList.players.forEach(::reconcileSafely)
        }
    }

    fun open(player: ServerPlayer, pos: BlockPos): Boolean = guarded(player) {
        if (!requestAllowed(player)) return@guarded
        val entity = player.level().getBlockEntity(pos) as? TerminalEntity ?: error("terminal_invalid")
        val session = Session(pos.immutable(), player.level().dimension().location().toString(), entity.terminalId,
            touched = player.server.tickCount.toLong())
        validate(player, session)
        check(ServerPlayNetworking.canSend(player, LeagueStatePayload.TYPE)) { "client_missing" }
        sessions[player.uuid] = session
        reconcile(player)
        send(player)
    }

    private fun handle(player: ServerPlayer, intent: LeagueIntentPayload) {
        if (!requestAllowed(player)) return
        guarded(player) {
            val session = sessions[player.uuid] ?: error("terminal_invalid")
            validate(player, session)
            check(session.nonce == intent.nonce) { "terminal_invalid" }
            session.requests[intent.requestId]?.let { previous ->
                check(previous == intent) { "request_conflict" }
                send(player)
                return@guarded
            }
            val catalog = requireNotNull(LeagueCatalogResources.current) { "catalog_unavailable" }
            val storage = LeagueSavedData.get(player.server)
            val state = storage.read(catalog.id, player.uuid)
            check(intent.catalogRevision == LeagueCatalogResources.revision && intent.revision == state.revision) { "stale_revision" }
            // Remember even rejected/uncertain starts: retries can refresh, never launch twice.
            session.requests[intent.requestId] = intent
            while (session.requests.size > 128) session.requests.remove(session.requests.keys.first())
            session.touched = player.server.tickCount.toLong()
            val engine = LeagueEngine(catalog)
            when (intent.action) {
                LeagueAction.REFRESH -> reconcile(player)
                LeagueAction.CANCEL -> {
                    commit(player.server, catalog.id, player.uuid, engine.cancel(state))
                    ManagedPveBattles.cancel(player.server, player.uuid)
                }
                LeagueAction.START, LeagueAction.NEXT -> {
                    LeagueIntegrations.validateCaps(catalog)
                    reconcile(player)
                    val current = storage.read(catalog.id, player.uuid)
                    LeagueIntegrations.syncCap(player, engine.cap(current))
                    val next = if (intent.action == LeagueAction.START) engine.begin(current, intent.challengeId,
                        ManagedPveBattles.snapshotParty(player, engine.cap(current))) else engine.next(current)
                    commit(player.server, catalog.id, player.uuid, next)
                    launch(player, catalog, next)
                }
            }
            send(player)
        }
    }

    private fun requestAllowed(player: ServerPlayer): Boolean {
        val now = player.server.tickCount
        val last = lastRequestTick[player.uuid]
        if (last != null && now - last in 0..3) return false
        lastRequestTick[player.uuid] = now
        return true
    }

    private fun launch(player: ServerPlayer, catalog: LeagueCatalog, state: LeagueProgress) {
        val run = requireNotNull(state.run)
        val challenge = run.encounters[run.index]
        try {
            val id = ManagedPveBattles.start(player, ManagedPveBattles.Request(run.battleToken, CONTENT,
                challenge.id, challenge.nameKey, run.party, challenge.team, ManagedPveBattles.Format.valueOf(challenge.format),
                challenge.mechanic.takeUnless { it == "NONE" }?.let(MajorBattleMechanic::valueOf), skill = challenge.skill,
                appearance = jbro.cobblemon.morebattlecontent.api.presentation.TrainerResourceSkin(
                    challenge.skin ?: "minecraft:textures/entity/player/wide/steve.png", challenge.slim))) { outcome ->
                val storage = LeagueSavedData.get(player.server)
                val latest = storage.read(catalog.id, player.uuid)
                val completed = LeagueEngine(catalog).finish(latest, run.battleToken, outcome == ManagedPveBattles.Outcome.WIN, System.currentTimeMillis())
                commit(player.server, catalog.id, player.uuid, completed)
                player.server.playerList.getPlayer(player.uuid)?.let { online -> reconcileSafely(online); send(online) }
            }
            check(id != null) { "battle_unavailable" }
        } catch (failure: RuntimeException) {
            commit(player.server, catalog.id, player.uuid, LeagueEngine(catalog).cancel(state))
            throw failure
        } catch (failure: LinkageError) {
            commit(player.server, catalog.id, player.uuid, LeagueEngine(catalog).cancel(state))
            throw failure
        }
    }

    private fun validate(player: ServerPlayer, session: Session) {
        val reason = TerminalAuthorization.reject(
            TerminalAnchor(session.terminalId, session.dimension, session.pos.x, session.pos.y, session.pos.z),
            TerminalObservation((player.level().getBlockEntity(session.pos) as? TerminalEntity)?.terminalId,
                player.level().dimension().location().toString(), player.x, player.y, player.z,
                player.level().getBlockState(session.pos).`is`(LeagueTerminal.block), player.mayInteract(player.level(), session.pos),
                player.isAlive && !player.isSpectator, player.server.tickCount.toLong()), session.touched)
        check(reason == null) { requireNotNull(reason) }
    }

    private fun reconcile(player: ServerPlayer) {
        val catalog = requireNotNull(LeagueCatalogResources.current) { "catalog_unavailable" }
        val storage = LeagueSavedData.get(player.server)
        var state = storage.read(catalog.id, player.uuid)
        LeagueIntegrations.syncCap(player, LeagueEngine(catalog).cap(state))
        // Player data and world SavedData do not share one disk transaction. Repair badges from wins.
        for (id in catalog.gyms.filter { it in state.cleared }) {
            val badge = requireNotNull(catalog.challenges.getValue(id).badge)
            if (!LeagueIntegrations.hasBadge(player, badge)) check(LeagueIntegrations.awardBadge(player, badge)) { "badge_failed" }
        }
        for (reward in state.rewards.toList()) {
            val badgeDone = reward.badgeDone || LeagueIntegrations.awardBadge(player, requireNotNull(reward.badge))
            val bpDone = reward.bp == 0L || BattlePointRewards.award(player.server, player.uuid, reward.token, reward.bp,
                CONTENT, reward.challengeId).accepted
            if (badgeDone != reward.badgeDone || bpDone != reward.bpDone) {
                state = state.copy(rewards = state.rewards.map { if (it.token == reward.token) it.copy(badgeDone = badgeDone, bpDone = bpDone) else it })
                commit(player.server, catalog.id, player.uuid, state)
            }
        }
    }

    private fun reconcileSafely(player: ServerPlayer) {
        try { reconcile(player) } catch (failure: RuntimeException) {
            Mod.LOGGER.debug("League sync deferred for {}: {}", player.uuid, failure.message)
        } catch (failure: LinkageError) { Mod.LOGGER.error("League integration unavailable", failure) }
    }

    private fun commit(server: MinecraftServer, league: String, player: UUID, state: LeagueProgress) {
        LeagueSavedData.get(server).write(league, player, state)
        server.overworld().dataStorage.save()
    }

    private fun send(player: ServerPlayer, errorKey: String? = null) {
        val session = sessions[player.uuid] ?: return
        val catalog = LeagueCatalogResources.current ?: return
        val state = LeagueSavedData.get(player.server).read(catalog.id, player.uuid)
        val engine = LeagueEngine(catalog)
        val badges = engine.badgeCount(state)
        val rank = when { state.champion -> "CHAMPION"; badges == 8 -> "MASTER_BALL"; badges >= 5 -> "ULTRA_BALL"; badges >= 3 -> "GREAT_BALL"; else -> "POKE_BALL" }
        val views = (catalog.gyms + catalog.finals.first()).map { id ->
            val index = catalog.gyms.indexOf(id)
            val available = if (index >= 0) catalog.gyms.take(index).all { it in state.cleared } else badges == 8
            LeagueChallengeView(id, catalog.challenges.getValue(id).nameKey,
                if (id in state.cleared) "CLEARED" else if (available) "AVAILABLE" else "LOCKED", catalog.challenges.getValue(id).unlockCap)
        }
        val view = LeagueView(session.nonce, state.revision, LeagueCatalogResources.revision, catalog.nameKey,
            badges, rank, engine.cap(state), state.champion, BattlePointRewards.balance(player.server, player.uuid), views,
            state.run?.challengeId, state.run?.awaitingNext ?: false, state.rewards.any { !it.badgeDone || !it.bpDone }, errorKey)
        if (ServerPlayNetworking.canSend(player, LeagueStatePayload.TYPE)) ServerPlayNetworking.send(player, LeagueStatePayload(gson.toJson(view)))
    }

    private fun guarded(player: ServerPlayer, action: () -> Unit): Boolean = try { action(); true } catch (failure: RuntimeException) {
        Mod.LOGGER.warn("League request rejected for {}: {}", player.uuid, failure.message)
        val reason = failure.message?.substringBefore(':')
        val known = setOf("cap_disabled", "cap_unmapped", "cap_unavailable", "cap_sync_failed", "badge_failed",
            "run_active", "party_required", "rewards_pending", "history_full", "prerequisite", "no_run", "phase_invalid", "level_cap",
            "battle_active", "battle_unavailable", "catalog_unavailable", "terminal_invalid", "terminal_expired",
            "client_missing", "request_conflict", "stale_revision")
        val key = ERROR_PREFIX + if (reason in known) reason else "request_failed"
        player.sendSystemMessage(Component.translatable(key))
        try { send(player, key) } catch (_: RuntimeException) { /* Broken storage stays locked. */ }
        false
    } catch (failure: LinkageError) {
        Mod.LOGGER.error("League dependency failure", failure)
        player.sendSystemMessage(Component.translatable(ERROR_PREFIX + "integration_failed"))
        false
    }
}
