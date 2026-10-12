package jbro.cobblemon.mcc.client

import com.cobblemon.mod.common.client.CobblemonClient
import com.mojang.authlib.GameProfile
import java.util.EnumSet
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.internal.pvp.PvpBattleFormat
import jbro.cobblemon.mcc.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSettings
import jbro.cobblemon.mcc.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomMemberView
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionOpponentSlot
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionPartySlot
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionSpectator
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionViewState
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.BackupConfirmScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.level.GameType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Development-only visual check of the PvP room and team preview without a second player. Set `MCC_PVP_CAPTURE` to
 * a comma list of steps (`room`, `selection`, `selection_picked`); optionally `MCC_PVP_CAPTURE_GUI_SCALES` (for
 * example `3,4`). Launch with `--quickPlaySingleplayer <world>`: once the world is loaded the harness tops the party
 * up to six, adds a made-up opponent and spectators to the client's player list, feeds each step's state to
 * [PvpHubClient] as the server would, captures `screenshots/mcc-pvp-<step>-<gui>.png` at every scale, and stops.
 * Nothing reaches the server: the fake room and match exist only on this client.
 */
internal object PvpCaptureHarness {
    private val logger = MoreCobblemonContents.LOGGER

    private val OPPONENT = PvpRoomMemberView(UUID.fromString("6b2c8d9e-1f00-4a55-9c3b-000000000001"), "PvpRival")
    private val SPECTATORS = listOf("Watcher_Mina", "Spectator02", "LongNamedSpectator", "Kiwi", "ObserverFive")
        .mapIndexed { index, name -> PvpRoomMemberView(UUID.fromString("6b2c8d9e-1f00-4a55-9c3b-00000000001$index"), name) }
    private val PARTY = listOf("garchomp", "rotom", "gholdengo", "kingambit", "dragonite", "amoonguss")
    private val OPPONENT_PARTY = listOf(
        PvpSelectionOpponentSlot("cobblemon:incineroar"), PvpSelectionOpponentSlot("cobblemon:rillaboom"),
        PvpSelectionOpponentSlot("cobblemon:urshifu"), PvpSelectionOpponentSlot("cobblemon:fluttermane"),
        PvpSelectionOpponentSlot("cobblemon:tornadus"), PvpSelectionOpponentSlot("cobblemon:ninetales"),
    )
    private val ROOM_ID: UUID = UUID.fromString("6b2c8d9e-1f00-4a55-9c3b-0000000000aa")
    private val MATCH_ID: UUID = UUID.fromString("6b2c8d9e-1f00-4a55-9c3b-0000000000bb")

    fun installFromEnvironment() {
        val steps = ArrayDeque(System.getenv("MCC_PVP_CAPTURE")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty())
        if (steps.isEmpty() || !FabricLoader.getInstance().isDevelopmentEnvironment) return
        val scales = System.getenv("MCC_PVP_CAPTURE_GUI_SCALES")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in 1..6 }?.takeIf { it.isNotEmpty() } ?: listOf(3)
        var languageRequested = false
        val languageReady = AtomicBoolean(false)
        var partyRequested = false
        var partyWait = 0
        var step: String? = null
        var scaleQueue = ArrayDeque<Int>()
        var wait = 0
        val captured = AtomicBoolean(true)
        var ticks = 0

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            (client.screen as? BackupConfirmScreen)?.let { warning ->
                val skip = Component.translatable("selectWorld.backupJoinSkipButton").string
                warning.children().filterIsInstance<AbstractButton>().firstOrNull { it.message.string == skip }?.onPress()
                return@EndTick
            }
            if (!languageRequested && client.overlay == null && (client.screen != null || client.level != null)) {
                languageRequested = true
                client.options.pauseOnLostFocus = false
                client.options.languageCode = "ko_kr"
                client.languageManager.setSelected("ko_kr")
                client.reloadResourcePacks().whenComplete { _, error ->
                    if (error != null) logger.error("PvP capture language reload failed", error)
                    languageReady.set(true)
                }
                return@EndTick
            }
            if (!languageReady.get()) return@EndTick
            val player = client.player ?: return@EndTick
            val connection = client.connection ?: return@EndTick
            if (client.overlay != null) return@EndTick
            if (++ticks > 6000) error("PvP capture timed out")
            client.toasts.clear()

            if (!partyRequested) {
                val server = checkNotNull(client.singleplayerServer) { "The PvP capture needs a singleplayer world" }
                val have = CobblemonClient.storage.party.count { it != null }
                PARTY.drop(have).forEach { species ->
                    server.execute {
                        server.commands.performPrefixedCommand(server.createCommandSourceStack(),
                            "givepokemonother ${player.gameProfile.name} $species level=50")
                    }
                }
                addFakePlayers(client)
                partyRequested = true
                return@EndTick
            }
            if (partyWait++ < 60) return@EndTick
            // A world pause screen or the like would sit over the hub.
            if (step == null && client.screen != null && client.screen !is MccHubScreen) client.setScreen(null)

            if (!captured.get()) return@EndTick
            if (wait > 0) {
                if (wait == 15) step?.let { hover(client, it) }
                if (--wait == 0) {
                    val name = "mcc-pvp-$step-gui${client.options.guiScale().get()}-" +
                        "${client.window.guiScaledWidth}x${client.window.guiScaledHeight}.png"
                    captured.set(false)
                    Screenshot.grab(client.gameDirectory, name, client.mainRenderTarget) { result ->
                        logger.info("PvP capture {}: {}", name, result.string)
                        captured.set(true)
                    }
                }
                return@EndTick
            }
            val nextScale = scaleQueue.removeFirstOrNull()
            if (nextScale != null) {
                client.options.guiScale().set(nextScale)
                client.resizeDisplay()
                // The server may have answered the hub with its own (empty) PvP state; put the fixture back.
                step?.let { show(it, player.uuid, player.gameProfile.name) }
                wait = 40
                return@EndTick
            }
            val next = steps.removeFirstOrNull()
            if (next == null) {
                client.setScreen(null)
                client.stop()
                return@EndTick
            }
            step = next
            show(next, player.uuid, player.gameProfile.name)
            scaleQueue = ArrayDeque(scales)
            logger.info("PvP capture step {}", next)
        })
    }

    /** Puts the made-up opponent and spectators into the client's player list, so seats and faces find a profile. */
    private fun addFakePlayers(client: Minecraft) {
        val entries = (listOf(OPPONENT) + SPECTATORS).map { member ->
            ClientboundPlayerInfoUpdatePacket.Entry(member.playerId, GameProfile(member.playerId, member.name), false, 0,
                GameType.SURVIVAL, null, null)
        }
        val packet = ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), emptyList())
        // The packet's public constructors take server players; set the entries the client reads directly.
        val field = ClientboundPlayerInfoUpdatePacket::class.java.declaredFields.first { it.type == List::class.java }
        field.isAccessible = true
        field.set(packet, entries)
        checkNotNull(client.connection).handlePlayerInfoUpdate(packet)
    }

    /**
     * Moves the mouse over the widget a hover step looks at, by setting where the client thinks the cursor is; other
     * steps park it in the top-left corner so nothing shows hovered.
     */
    private fun hover(client: Minecraft, step: String) {
        val target = when (step) {
            "room_hover_face" -> "Faces"
            "room_hover_count" -> "SpectatorCount"
            else -> null
        }
        val widget = target?.let { name ->
            client.screen?.children()?.filterIsInstance<net.minecraft.client.gui.components.AbstractWidget>()
                ?.firstOrNull { it.javaClass.simpleName == name }
        }
        val scale = client.window.guiScale
        val selection = client.screen as? PvpSelectionScreen
        if (selection != null && step == "selection_keys") {
            // Down three times: the first press only takes over from the mouse, the next two move to the third card.
            repeat(3) { selection.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0) }
        }
        val card = when (step) {
            "selection_hover_opponent" -> selection?.cardCenter(true, 2)
            "selection_spectator" -> selection?.cardCenter(true, 0)
            else -> null
        }
        val (x, y) = when {
            card != null -> card.first * scale to card.second * scale
            widget == null -> 0.0 to 0.0
            // The grid centres its columns, so the first face's row is at the widget's middle.
            target == "Faces" -> (widget.x + widget.width / 2) * scale to (widget.y + 4) * scale
            else -> (widget.x + widget.width / 2) * scale to (widget.y + widget.height / 2) * scale
        }
        val handler = client.mouseHandler
        listOf("xpos" to x, "ypos" to y).forEach { (name, value) ->
            val field = handler.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.setDouble(handler, value)
        }
    }

    private fun show(step: String, me: UUID, myName: String) {
        when (step) {
            "room", "room_hover_face", "room_hover_count" -> PvpHubClient.acceptRoom(null, PvpRoomClientView(
                ROOM_ID, me,
                PvpRoomSettings(PvpRoomVisibility.PUBLIC, PvpBattleFormat.SINGLE, setOf(PvpBattleMechanic.MEGA)),
                PvpRoomPhase.LOBBY, PvpRoomMemberView(me, myName), OPPONENT, SPECTATORS, emptyList(),
            ), reopen = true)
            "selection_spectator" -> PvpHubClient.acceptSelection(null, PvpSelectionViewState(
                MATCH_ID, PvpBattleFormat.SINGLE, OPPONENT.name, emptyList(), emptyList(), emptyList(),
                System.currentTimeMillis() + 7_000, waitingForOpponent = false,
                playerOnLeft = true, leftPlayerName = "Watcher_Mina", rightPlayerName = OPPONENT.name,
                spectators = SPECTATORS.map { PvpSelectionSpectator(it.playerId, it.name) }, spectatorMode = true,
                spectatorLeftParty = PARTY.map { PvpSelectionOpponentSlot("cobblemon:$it") }, spectatorRightParty = OPPONENT_PARTY,
            ))
            "selection", "selection_picked", "selection_hover_opponent", "selection_keys" -> {
                val party = CobblemonClient.storage.party.filterNotNull().take(6).map { pokemon ->
                    PvpSelectionPartySlot(pokemon.uuid, pokemon.species.resourceIdentifier.toString(),
                        pokemon.heldItem().takeUnless { it.isEmpty }?.let { net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.item).toString() },
                        pokemon.level, 50)
                }
                val picked = if (step != "selection") party.take(2).map(PvpSelectionPartySlot::pokemonId) else emptyList()
                PvpHubClient.acceptSelection(null, PvpSelectionViewState(
                    MATCH_ID, PvpBattleFormat.SINGLE, OPPONENT.name, party, OPPONENT_PARTY, emptyList(),
                    System.currentTimeMillis() + 90_000, waitingForOpponent = false,
                    playerOnLeft = true, leftPlayerName = myName, rightPlayerName = OPPONENT.name,
                    spectators = SPECTATORS.map { PvpSelectionSpectator(it.playerId, it.name) },
                ))
                picked.forEach { PvpHubClient.selection?.toggle(it) }
                MccHubScreen.refresh(PvpHubClient.CONTENT)
            }
            else -> error("Unknown PvP capture step $step")
        }
    }
}
