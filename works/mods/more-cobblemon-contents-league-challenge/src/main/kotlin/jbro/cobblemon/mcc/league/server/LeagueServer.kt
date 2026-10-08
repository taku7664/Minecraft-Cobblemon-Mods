package jbro.cobblemon.mcc.league.server

import com.google.gson.Gson
import java.util.UUID
import jbro.cobblemon.mcc.api.access.*
import jbro.cobblemon.mcc.api.battle.ManagedPveBattles
import jbro.cobblemon.mcc.api.news.MccNews
import jbro.cobblemon.mcc.api.news.MccNewsEvent
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.rewards.BattlePointRewards
import jbro.cobblemon.mcc.api.presentation.BattleResultNotices
import jbro.cobblemon.mcc.api.rules.BattleGimmickLocks
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.hub.BattleHubEntries
import jbro.cobblemon.mcc.internal.hub.BattleHubEntry
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.terminal.TerminalInteractionResult
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.mcc.league.network.*
import jbro.cobblemon.mcc.league.system.*
import jbro.cobblemon.mcc.league.ui.LeagueRank
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.*
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** All mutations execute on the server thread. Clients send intent, never outcomes or cap values. */
object LeagueServer {
    /** [terminal] is the hologram terminal the hub was opened from, or null for a hub opened by command. */
    private data class Session(val terminal: TerminalAnchor?,
        val nonce: UUID = UUID.randomUUID(), var touched: Long = 0,
        val requests: LinkedHashMap<UUID, LeagueIntentPayload> = linkedMapOf())
    private val sessions = mutableMapOf<UUID, Session>()
    private val lastRequestTick = mutableMapOf<UUID, Int>()
    private val sentRanks = mutableMapOf<UUID, String>()
    private var access: AutoCloseable? = null
    private var gimmickLock: AutoCloseable? = null
    private val gson = Gson()
    private const val CONTENT = ManagedBattleContentIds.LEAGUE_CHALLENGE
    private const val ERROR_PREFIX = "message.${Mod.MOD_ID}."

    fun register() {
        BattleHubEntries.register(BattleHubEntry(CONTENT) { player, terminal -> openFromHub(player, terminal) })
        PayloadTypeRegistry.playS2C().register(LeagueStatePayload.TYPE, LeagueStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(LeagueRankPayload.TYPE, LeagueRankPayload.CODEC)
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
            // Gimmicks are the normal Champion's to use outside MCC content and player battles.
            gimmickLock?.close()
            gimmickLock = BattleGimmickLocks.register { player ->
                val catalog = LeagueCatalogResources.current ?: return@register null
                if (LeagueSavedData.get(server).read(catalog.id, player.uuid).champion) null
                else Component.translatable(ERROR_PREFIX + "gimmicks_locked")
            }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { LeagueSavedData.get(it).cancelInterruptedRuns() }
        ServerLifecycleEvents.SERVER_STOPPED.register { access?.close(); access = null; gimmickLock?.close(); gimmickLock = null; sessions.clear(); lastRequestTick.clear(); sentRanks.clear(); LeagueCatalogResources.clear() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> reconcileSafely(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            sessions.remove(handler.player.uuid)
            lastRequestTick.remove(handler.player.uuid)
            sentRanks.remove(handler.player.uuid)
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

    /**
     * The League tab of an open hub asks for its state. A hub opened at a hologram terminal keeps the session at
     * that terminal, so the player has to stay by it; the hub itself is already on screen.
     */
    fun openFromHub(player: ServerPlayer, terminal: TerminalInteractionResult.Verified?): Boolean = guarded(player) {
        if (!requestAllowed(player)) return@guarded
        val anchor = terminal?.let { TerminalAnchor(it.terminalId, it.dimensionId, it.x, it.y, it.z) }
        start(player, Session(anchor, touched = player.server.tickCount.toLong()))
    }

    private fun start(player: ServerPlayer, session: Session) {
        validate(player, session)
        check(ServerPlayNetworking.canSend(player, LeagueStatePayload.TYPE)) { "client_missing" }
        sessions[player.uuid] = session
        // Send before reconciling, so an integration setup error can still be displayed.
        send(player)
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

    /** News for a player's first normal or hard Champion title; repeated wins and retried callbacks stay quiet. */
    private fun announceChampion(server: MinecraftServer, playerId: UUID, catalog: LeagueCatalog, opponentKey: String,
                                 before: LeagueProgress, after: LeagueProgress) {
        val kind = when {
            after.hardChampion && !before.hardChampion -> "hard_champion"
            after.champion && !before.champion -> "champion"
            else -> return
        }
        val message = Component.translatable("news.${Mod.MOD_ID}.$kind", MccNews.playerName(server, playerId),
            Component.translatable(opponentKey), Component.translatable(catalog.nameKey))
        MccNews.publish(server, MccNewsEvent("${Mod.MOD_ID}:$kind", playerId, message))
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
                jbro.cobblemon.mcc.api.rules.BattleMechanicFlags.fromMajor(
                    challenge.mechanic.takeUnless { it == "NONE" }?.let(MajorBattleMechanic::valueOf)), skill = challenge.skill,
                appearance = jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin(
                    challenge.skin ?: "minecraft:textures/entity/player/wide/steve.png", challenge.slim),
                stage = LeagueEngine(catalog).stage(run), scenes = trainerScenes(challenge))) { outcome ->
                val storage = LeagueSavedData.get(player.server)
                val latest = storage.read(catalog.id, player.uuid)
                val completed = LeagueEngine(catalog).finish(latest, run.battleToken, outcome == ManagedPveBattles.Outcome.WIN, System.currentTimeMillis())
                commit(player.server, catalog.id, player.uuid, completed)
                announceChampion(player.server, player.uuid, catalog, challenge.nameKey, latest, completed)
                player.server.playerList.getPlayer(player.uuid)?.let { online ->
                    // Only the first callback for this battle changes the progress; retries stay quiet.
                    if (completed != latest && outcome != ManagedPveBattles.Outcome.CANCELLED) {
                        val opponent = Component.translatable(challenge.nameKey)
                        if (outcome == ManagedPveBattles.Outcome.WIN) {
                            val known = latest.rewards.map { it.token }.toSet()
                            BattleResultNotices.victory(online, opponent, completed.rewards.filter { it.token !in known }.sumOf { it.bp })
                        } else {
                            BattleResultNotices.defeat(online, opponent)
                        }
                    }
                    reconcileSafely(online)
                    // Idempotent callbacks do not repeatedly reopen the screen.
                    send(online, openScreen = completed != latest)
                }
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
        val living = player.isAlive && !player.isSpectator
        val tick = player.server.tickCount.toLong()
        val anchor = session.terminal
        val reason = if (anchor == null) TerminalAuthorization.rejectDetached(living, tick, session.touched) else {
            val pos = BlockPos(anchor.x, anchor.y, anchor.z)
            val terminalId = HoloTerminals.terminalIdAt(player.level(), pos)
            TerminalAuthorization.reject(anchor,
                TerminalObservation(terminalId, player.level().dimension().location().toString(), player.x, player.y, player.z,
                    terminalId != null, player.mayInteract(player.level(), pos), living, tick), session.touched)
        }
        check(reason == null) { requireNotNull(reason) }
    }

    private fun reconcile(player: ServerPlayer) {
        val catalog = requireNotNull(LeagueCatalogResources.current) { "catalog_unavailable" }
        val storage = LeagueSavedData.get(player.server)
        var state = storage.read(catalog.id, player.uuid)
        // Before the integration steps below, which can fail and must not keep the header's rank stale.
        syncRank(player, rank(catalog, state).name)
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

    internal fun rank(catalog: LeagueCatalog, state: LeagueProgress): LeagueRank {
        val badges = LeagueEngine(catalog).badgeCount(state).coerceIn(0, 8)
        return LeagueRank.fromProgress(badges, state.champion && badges == 8)
    }

    /** Tells the client its rank when it changed; reconcile repeats this, so admin edits reach it too. */
    private fun syncRank(player: ServerPlayer, rank: String) {
        if (sentRanks[player.uuid] == rank || !ServerPlayNetworking.canSend(player, LeagueRankPayload.TYPE)) return
        ServerPlayNetworking.send(player, LeagueRankPayload(rank))
        sentRanks[player.uuid] = rank
    }

    private fun reconcileSafely(player: ServerPlayer) {
        try { reconcile(player) } catch (failure: RuntimeException) {
            Mod.LOGGER.debug("League sync deferred for {}: {}", player.uuid, failure.message)
        } catch (failure: LinkageError) { Mod.LOGGER.error("League integration unavailable", failure) }
    }

    /**
     * Saves an operator's edit of [playerId]'s progress in the current League the way the League's own changes
     * are saved, then brings an online player's cap, badges, rank and open League tab up to date.
     */
    internal fun adminCommit(server: MinecraftServer, catalog: LeagueCatalog, playerId: UUID, state: LeagueProgress) {
        commit(server, catalog.id, playerId, state)
        server.playerList.getPlayer(playerId)?.let { online ->
            reconcileSafely(online)
            send(online)
        }
    }

    /** Runs the League's reconcile for [player] now; returns the failure reason, or null when it went through. */
    internal fun adminReconcile(player: ServerPlayer): String? = try {
        reconcile(player)
        send(player)
        null
    } catch (failure: RuntimeException) {
        failure.message?.substringBefore(':') ?: "request_failed"
    }

    /** Ends [playerId]'s run as the player's own cancel does, stopping its battle after the run is gone. */
    internal fun adminCancelRun(server: MinecraftServer, catalog: LeagueCatalog, playerId: UUID): Boolean {
        val state = LeagueSavedData.get(server).read(catalog.id, playerId)
        if (state.run == null) return false
        adminCommit(server, catalog, playerId, LeagueEngine(catalog).cancel(state))
        ManagedPveBattles.cancel(server, playerId)
        return true
    }

    private fun commit(server: MinecraftServer, league: String, player: UUID, state: LeagueProgress) {
        val storage = LeagueSavedData.get(server)
        val catalog = LeagueCatalogResources.current?.takeIf { it.id == league }
        val before = catalog?.let { rank(it, storage.read(league, player)) }
        storage.write(league, player, state)
        server.overworld().dataStorage.save()
        if (catalog == null) return
        val after = rank(catalog, state)
        server.playerList.getPlayer(player)?.let { online -> syncRank(online, after.name) }
        if (after != before) jbro.cobblemon.mcc.league.api.LeagueRanks.changed(server, player, after)
    }

    private fun send(player: ServerPlayer, errorKey: String? = null, openScreen: Boolean = false) {
        val session = sessions[player.uuid] ?: return
        val catalog = LeagueCatalogResources.current ?: return
        val state = LeagueSavedData.get(player.server).read(catalog.id, player.uuid)
        val engine = LeagueEngine(catalog)
        val badges = engine.badgeCount(state)
        val rank = rank(catalog, state).name
        fun route(gyms: List<String>, finals: List<String>) = (gyms + finals.first()).map { id ->
            val index = gyms.indexOf(id)
            val available = if (index >= 0) gyms.take(index).all { it in state.cleared } else gyms.all { it in state.cleared }
            val challenge = catalog.challenges.getValue(id)
            LeagueChallengeView(id, challenge.nameKey,
                if (id in state.cleared) "CLEARED" else if (available) "AVAILABLE" else "LOCKED", challenge.unlockCap,
                challenge.badge ?: gyms.indexOf(id).takeIf { it >= 0 }?.let { catalog.challenges.getValue(catalog.gyms[it]).badge })
        }
        val views = route(catalog.gyms, catalog.finals)
        val hardUnlocked = engine.hardUnlocked(state)
        val view = LeagueView(session.nonce, state.revision, LeagueCatalogResources.revision, catalog.nameKey,
            badges, rank, engine.cap(state), state.champion, BattlePointRewards.balance(player.server, player.uuid), views,
            state.run?.challengeId, state.run?.awaitingNext ?: false, state.rewards.any { !it.badgeDone || !it.bpDone }, errorKey,
            openScreen && runCatching { validate(player, session) }.isSuccess,
            state.run?.let { it.encounters[it.index].nameKey },
            hardUnlocked, state.hardChampion, if (hardUnlocked) route(catalog.hardGyms, catalog.hardFinals) else emptyList(),
            state.run?.let { run -> run.encounters.first().id in catalog.hardGyms + catalog.hardFinals } ?: false)
        if (ServerPlayNetworking.canSend(player, LeagueStatePayload.TYPE)) ServerPlayNetworking.send(player, LeagueStatePayload(gson.toJson(view)))
    }

    private fun guarded(player: ServerPlayer, action: () -> Unit): Boolean = try { action(); true } catch (failure: RuntimeException) {
        Mod.LOGGER.warn("League request rejected for {}: {}", player.uuid, failure.message)
        val reason = failure.message?.substringBefore(':')
        val known = setOf("cap_disabled", "catching_cap_disabled", "spawn_scaling_conflict", "cap_unmapped", "cap_unavailable", "cap_sync_failed", "badge_failed",
            "run_active", "party_required", "rewards_pending", "history_full", "prerequisite", "hard_locked", "no_run", "phase_invalid", "level_cap",
            "opponent_level_unsupported",
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
