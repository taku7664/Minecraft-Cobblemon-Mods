package jbro.cobblemon.mcc.pwt.server

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.Gson
import java.util.UUID
import jbro.cobblemon.mcc.api.access.BattleContentAccess
import jbro.cobblemon.mcc.api.access.ContentAccessDecision
import jbro.cobblemon.mcc.api.battle.ManagedPveBattles
import jbro.cobblemon.mcc.api.news.MccNews
import jbro.cobblemon.mcc.api.news.MccNewsEvent
import jbro.cobblemon.mcc.api.presentation.BattleResultNotices
import jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin
import jbro.cobblemon.mcc.api.rewards.BattlePointRewards
import jbro.cobblemon.mcc.api.terminal.HoloTerminalPalette
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.battle.LegendaryClassPolicy
import jbro.cobblemon.mcc.internal.hub.BattleHubEntries
import jbro.cobblemon.mcc.internal.hub.BattleHubEntry
import jbro.cobblemon.mcc.internal.hub.BattleHubIds
import jbro.cobblemon.mcc.pwt.MoreCobblemonContentsPwt as Mod
import jbro.cobblemon.mcc.pwt.network.*
import jbro.cobblemon.mcc.pwt.system.PwtBracket
import jbro.cobblemon.mcc.pwt.system.PwtRules
import kotlin.random.Random
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Every mutation runs on the server thread; clients send intents, never results. */
object PwtServer {
    /** A tournament under way: the bracket, the three Pokémon entered, and the battle being fought. */
    private class Run(val token: UUID, var bracket: PwtBracket, val team: List<String>, val teamSpecies: List<String>) {
        var battleToken: UUID? = null
    }

    private val nonces = HashMap<UUID, UUID>()
    private val runs = HashMap<UUID, Run>()
    private val random = Random.Default
    private val gson = Gson()
    private var access: AutoCloseable? = null
    private const val ERROR = "screen.${Mod.MOD_ID}.error."

    fun register() {
        HoloTerminals.register(ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "pwt_terminal"),
            listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, Mod.CONTENT),
            // Tournament navy with a trophy-gold crystal.
            HoloTerminalPalette(0x162A5A, 0x2848A8, 0xFFC844, 0xFFE38A, 0xFFE9A6, 0x3C6CE0, 0x78A0FF))
        BattleHubEntries.register(BattleHubEntry(Mod.CONTENT) { player, _ -> open(player) })
        PayloadTypeRegistry.playS2C().register(PwtStatePayload.TYPE, PwtStatePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(PwtIntentPayload.TYPE, PwtIntentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(PwtIntentPayload.TYPE) { payload, context -> handle(context.player(), payload) }
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            access?.close()
            access = BattleContentAccess.register(server, setOf(Mod.CONTENT)) { player, _, _ ->
                if (PwtLeagueGate.unlocked(server, player)) ContentAccessDecision.Allowed
                else ContentAccessDecision.Denied(ERROR + "champion_required", "champion_required")
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { access?.close(); access = null; runs.clear(); nonces.clear(); PwtCatalogResources.clear() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server -> payOwed(server, handler.player.uuid) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val playerId = handler.player.uuid
            nonces.remove(playerId)
            // Leaving forfeits the tournament. A battle in progress is ended by MCC, and its result closes the run.
            if (runs[playerId]?.battleToken == null) runs.remove(playerId)
        }
    }

    /** The PWT tab of an open hub asks for its state. */
    private fun open(player: ServerPlayer): Boolean = guarded(player) {
        check(ServerPlayNetworking.canSend(player, PwtStatePayload.TYPE)) { "client_missing" }
        nonces[player.uuid] = UUID.randomUUID()
        payOwed(player.server, player.uuid)
        send(player)
    }

    private fun handle(player: ServerPlayer, intent: PwtIntentPayload) {
        guarded(player) {
            check(nonces[player.uuid] == intent.nonce) { "session_expired" }
            when (intent.action) {
                PwtAction.REFRESH -> Unit
                PwtAction.ENTER -> enter(player, intent.picked)
                PwtAction.FIGHT -> fight(player)
                PwtAction.LEAVE -> leave(player)
            }
            send(player)
        }
    }

    private fun enter(player: ServerPlayer, picked: List<UUID>) {
        check(PwtLeagueGate.unlocked(player.server, player.uuid)) { "champion_required" }
        check(runs[player.uuid] == null) { "run_active" }
        check(BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) == null) { "battle_active" }
        val catalog = checkNotNull(PwtCatalogResources.current) { "catalog_unavailable" }
        val party = Cobblemon.storage.getParty(player).toList()
        PwtRules.violations(party.map(::candidate), picked).firstOrNull()?.let { error(it) }
        val members = picked.map { id -> party.first { it.uuid == id } }
        check(members.none { it.isBattleClone() }) { "not_in_party" }
        runs[player.uuid] = Run(UUID.randomUUID(), PwtBracket.draw(catalog.trainers.keys, random),
            members.map { it.saveToJSON(player.registryAccess()).toString() },
            members.map { it.species.resourceIdentifier.toString() })
    }

    private fun fight(player: ServerPlayer) {
        val run = checkNotNull(runs[player.uuid]) { "no_run" }
        check(run.battleToken == null) { "battle_active" }
        val slot = checkNotNull(run.bracket.opponentSlot()) { "run_over" }
        val trainer = checkNotNull(PwtCatalogResources.current?.trainers?.get(run.bracket.entrants[slot])) { "catalog_unavailable" }
        val round = run.bracket.round
        val token = UUID.randomUUID()
        run.battleToken = token
        val request = ManagedPveBattles.Request(token, Mod.CONTENT, trainer.id, trainer.nameKey, run.team, trainer.drawTeam(random),
            skill = PwtRules.ROUND_SKILLS[round], appearance = TrainerResourceSkin(trainer.skin, trainer.slim), stage = STAGES[round])
        val battle = try {
            ManagedPveBattles.start(player, request, PwtRules.BATTLE_LEVEL) { outcome -> finish(player.server, player.uuid, token, outcome) }
        } catch (failure: RuntimeException) {
            run.battleToken = null
            throw failure
        }
        if (battle == null) {
            run.battleToken = null
            error("battle_unavailable")
        }
    }

    /** Giving up before a battle ends the run as a loss; a finished run is simply cleared. */
    private fun leave(player: ServerPlayer) {
        val run = runs[player.uuid] ?: return
        check(run.battleToken == null) { "battle_active" }
        runs.remove(player.uuid)
    }

    /** The battle's result moves the bracket; a cancelled battle (a disconnect, the server stopping) counts as a loss. */
    private fun finish(server: MinecraftServer, playerId: UUID, token: UUID, outcome: ManagedPveBattles.Outcome) {
        val run = runs[playerId]?.takeIf { it.battleToken == token } ?: return
        run.battleToken = null
        val slot = checkNotNull(run.bracket.opponentSlot())
        val trainer = PwtCatalogResources.current?.trainers?.get(run.bracket.entrants[slot])
        val won = outcome == ManagedPveBattles.Outcome.WIN
        run.bracket = run.bracket.settle(won, random)
        val player = server.playerList.getPlayer(playerId)
        if (player == null) {
            runs.remove(playerId)
            return
        }
        if (run.bracket.playerWon) crown(player, run)
        val opponent = trainer?.let { Component.translatable(it.nameKey) } ?: Component.literal(run.bracket.entrants[slot])
        when (outcome) {
            ManagedPveBattles.Outcome.WIN -> BattleResultNotices.victory(player, opponent, if (run.bracket.playerWon) PwtRules.WINNER_BP else 0)
            ManagedPveBattles.Outcome.LOSS -> BattleResultNotices.defeat(player, opponent)
            ManagedPveBattles.Outcome.CANCELLED -> Unit
        }
        if (nonces.containsKey(playerId)) send(player, openScreen = true)
    }

    /** The title: counted and owed in one save, then paid, announced to Discord and told to everyone online. */
    private fun crown(player: ServerPlayer, run: Run) {
        val server = player.server
        PwtSavedData.get(server).recordTitle(player.uuid, run.token)
        payOwed(server, player.uuid)
        MccNews.publish(server, MccNewsEvent("${Mod.MOD_ID}:title", player.uuid,
            Component.translatable("news.${Mod.MOD_ID}.title", MccNews.playerName(server, player.uuid))))
        // The display name carries a Styled Nicknames nickname.
        server.playerList.broadcastSystemMessage(Component.translatable("chat.${Mod.MOD_ID}.title", player.displayName ?: player.name), false)
    }

    private fun payOwed(server: MinecraftServer, playerId: UUID) {
        val saved = PwtSavedData.get(server)
        saved.unpaid(playerId).forEach { token ->
            val result = BattlePointRewards.award(server, playerId, token, PwtRules.WINNER_BP, Mod.CONTENT, "pwt_title")
            if (result.accepted) saved.paid(token)
        }
    }

    private fun send(player: ServerPlayer, openScreen: Boolean = false, errorKey: String? = null) {
        val nonce = nonces[player.uuid] ?: return
        val run = runs[player.uuid]
        val catalog = PwtCatalogResources.current
        val view = PwtView(nonce.toString(), PwtLeagueGate.unlocked(player.server, player.uuid),
            Cobblemon.storage.getParty(player).toList().map(::partyView),
            run?.let { r ->
                PwtRunView(r.bracket.entrants.map { id ->
                    if (id == PwtBracket.PLAYER) PwtEntrantView(player.gameProfile.name, null, 0, null, true)
                    else catalog?.trainers?.get(id).let { t -> PwtEntrantView(t?.nameKey ?: id, t?.skin, t?.generation ?: 0, t?.ace, false) }
                }, r.bracket.winners, r.bracket.round, r.bracket.finished, r.bracket.playerWon, r.teamSpecies, r.battleToken != null)
            },
            PwtSavedData.get(player.server).titles(player.uuid), PwtRules.WINNER_BP, errorKey, openScreen)
        ServerPlayNetworking.send(player, PwtStatePayload(gson.toJson(view)))
    }

    private fun guarded(player: ServerPlayer, action: () -> Unit): Boolean = try {
        action()
        true
    } catch (failure: RuntimeException) {
        val reason = failure.message?.takeIf { it.matches(Regex("[a-z_]+")) }
        if (reason == null) Mod.LOGGER.error("PWT request failed for {}", player.uuid, failure)
        try { send(player, errorKey = ERROR + (reason ?: "unexpected")) } catch (_: RuntimeException) {}
        false
    }

    private fun candidate(pokemon: Pokemon) = PwtRules.Candidate(pokemon.uuid, pokemon.species.resourceIdentifier.toString(),
        heldItemId(pokemon), legendaryClass(pokemon))

    private fun partyView(pokemon: Pokemon) = PwtPartyView(pokemon.uuid.toString(), pokemon.species.resourceIdentifier.toString(),
        pokemon.form.name.takeUnless { pokemon.form == pokemon.species.standardForm }, pokemon.level, heldItemId(pokemon), legendaryClass(pokemon))

    private fun heldItemId(pokemon: Pokemon): String? =
        pokemon.heldItem().takeUnless { it.isEmpty }?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }

    private fun legendaryClass(pokemon: Pokemon): Boolean =
        LegendaryClassPolicy.categoryFor(pokemon.species.resourceIdentifier.toString(), pokemon.species.labels) != null

    private val STAGES = listOf("quarterfinal", "semifinal", "final")
}
