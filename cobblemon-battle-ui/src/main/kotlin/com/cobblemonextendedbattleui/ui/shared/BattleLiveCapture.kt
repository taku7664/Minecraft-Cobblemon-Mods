package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.battles.ErroredBattleStart
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.ForfeitConfirmationSelection
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.util.isPartyBusy
import jbro.cobblemon.battleui.extended.BattleDialogue
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI
import jbro.cobblemon.battleui.extended.navigation.KeyboardTileFocus
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.ScreenshotRecorder
import org.lwjgl.glfw.GLFW
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** An opt-in, disposable-world fixture that starts a genuine Cobblemon battle. */
internal object BattleLiveCapture {
    private var started = false
    private var ticks = 0
    private var elapsedTicks = 0
    private var screenshotPending = false
    private var rootSeen = false
    private var pageOpened = false
    private var navigationChecked = false
    private var backPending = false
    private val screenshotSaved = AtomicBoolean(false)
    private var pendingOpponent: PokemonEntity? = null
    private var pendingPlayerId: UUID? = null
    private var battleAttemptTicks = 0

    fun install() {
        val page = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_SCREEN") ?: "command"
        require(page in setOf("command", "moves", "switch", "forfeit"))
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!started || client.world == null || client.player == null) return@register
            if (++elapsedTicks > 600) {
                CobblemonExtendedBattleUI.LOGGER.error("Live battle fixture timed out before command capture")
                client.scheduleStop()
                return@register
            }
            if (screenshotPending) {
                if (!screenshotSaved.get()) return@register
                val screen = client.currentScreen as? BattleGUI
                if (screen != null && page != "command") {
                    if (!backPending) {
                        if (page == "forfeit") {
                            val back = BattleScreenGeometry.forfeitCancel(client.window.scaledWidth,
                                client.window.scaledHeight)
                            check(screen.mouseClicked((back.x() + back.width() / 2).toDouble(),
                                (back.y() + back.height() / 2).toDouble(), 0))
                        } else {
                            check(screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0))
                        }
                        backPending = true
                        ticks = 0
                        return@register
                    }
                    if (screen.getCurrentActionSelection() !is BattleGeneralActionSelection) {
                        check(++ticks < 40) { "$page did not return to root within 40 ticks" }
                        return@register
                    }
                    CobblemonExtendedBattleUI.LOGGER.info("Live battle '{}' back/cancel verified", page)
                }
                CobblemonExtendedBattleUI.LOGGER.info("Live battle '{}' capture saved", page)
                screenshotPending = false
                client.scheduleStop()
                return@register
            }
            if (client.currentScreen == null && CobblemonClient.battle?.mustChoose == true) {
                client.setScreen(BattleGUI())
                CobblemonExtendedBattleUI.LOGGER.info("Live battle GUI opened with Cobblemon request")
            }
            val screen = client.currentScreen as? BattleGUI ?: return@register
            if (BattleDialogue.hasPending()) {
                if (elapsedTicks % 5 == 0) {
                    BattleDialogue.confirm(GLFW.GLFW_KEY_Z, 0)
                    BattleDialogue.releaseConfirm(GLFW.GLFW_KEY_Z, 0)
                }
                return@register
            }
            val selection = screen.getCurrentActionSelection() ?: return@register
            if (!rootSeen && selection is BattleGeneralActionSelection) {
                rootSeen = true
                ticks = 0
                CobblemonExtendedBattleUI.LOGGER.info("Live battle root command selection became visible")
                selection.tiles.forEachIndexed { index, tile ->
                    CobblemonExtendedBattleUI.LOGGER.info(
                        "Live command tile {}: resource={}, text='{}', x={}, y={}",
                        index, tile.resource, tile.text.string, tile.x, tile.y)
                }
            }
            if (!rootSeen) return@register
            if (page != "command" && !pageOpened) {
                val root = selection as? BattleGeneralActionSelection ?: return@register
                if (++ticks < 20) return@register
                when (page) {
                    "moves", "switch" -> {
                        val tile = root.tiles[if (page == "moves") 0 else 1]
                        val clicked = screen.mouseClicked(
                            (tile.x + BattleOptionTile.OPTION_WIDTH / 2).toDouble(),
                            (tile.y + BattleOptionTile.OPTION_HEIGHT / 2).toDouble(), 0)
                        check(clicked) { "Native $page command did not accept its visible center click" }
                    }
                    "forfeit" -> screen.changeActionSelection(ForfeitConfirmationSelection(screen, root.request))
                }
                pageOpened = true
                ticks = 0
                CobblemonExtendedBattleUI.LOGGER.info("Live battle page '{}' opened via {}", page,
                    if (page == "forfeit") "native selection constructor" else "mouse click")
                return@register
            }
            if (page != "command") {
                val matches = when (page) {
                    "moves" -> selection is BattleMoveSelection
                    "switch" -> selection is BattleSwitchPokemonSelection
                    else -> selection is ForfeitConfirmationSelection
                }
                if (!matches) return@register
            }
            if (!navigationChecked && ticks >= 5) {
                check(screen.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0)) { "Down navigation not handled on $page" }
                when (selection) {
                    is BattleGeneralActionSelection -> check(selection.tiles.any { it.isFocused })
                    is BattleMoveSelection -> check(KeyboardTileFocus.focusedIndex(selection.moveTiles) >= 0)
                    is BattleSwitchPokemonSelection -> check(KeyboardTileFocus.focusedIndex(selection.tiles) >= 0)
                    is ForfeitConfirmationSelection -> {
                        check(screen.keyPressed(GLFW.GLFW_KEY_LEFT, 0, 0))
                        check(screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0))
                    }
                }
                navigationChecked = true
                CobblemonExtendedBattleUI.LOGGER.info("Live battle keyboard navigation verified on '{}'", page)
            }
            if (++ticks < 80) return@register
            screenshotPending = true
            val label = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_LABEL") ?: "live"
            require(label.matches(Regex("[a-z0-9-]+")))
            ScreenshotRecorder.saveScreenshot(client.runDirectory,
                "battle-ui-$label-live-$page-${client.options.language}.png", client.framebuffer) {
                CobblemonExtendedBattleUI.LOGGER.info("Live battle capture: {}", it.string)
                screenshotSaved.set(true)
            }
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            val opponent = pendingOpponent ?: return@register
            val player = pendingPlayerId?.let(server.playerManager::getPlayer) ?: return@register
            if (!opponent.canBattle(player)) {
                if (++battleAttemptTicks % 40 == 0) {
                    CobblemonExtendedBattleUI.LOGGER.warn(
                        "Live battle opponent not ready after {} ticks: busy={}, health={}, dead={}, partyBusy={}",
                        battleAttemptTicks, opponent.isBusy, opponent.health, opponent.isDead, player.isPartyBusy())
                }
                if (battleAttemptTicks >= 200) {
                    CobblemonExtendedBattleUI.LOGGER.error("Live battle opponent did not become battleable")
                    pendingOpponent = null
                    pendingPlayerId = null
                }
                return@register
            }
            pendingOpponent = null
            pendingPlayerId = null
            when (val result = BattleBuilder.pve(player, opponent)) {
                is SuccessfulBattleStart -> CobblemonExtendedBattleUI.LOGGER.info(
                    "Live battle started in fixture world for {}: {}", player.uuid, result.battle.battleId)
                is ErroredBattleStart -> CobblemonExtendedBattleUI.LOGGER.error(
                    "Live battle rejected by Cobblemon: general={}, participant={}",
                    result.generalErrors, result.participantErrors)
                else -> CobblemonExtendedBattleUI.LOGGER.error("Unexpected live battle result: {}", result)
            }
        }
    }

    fun start(client: MinecraftClient) {
        val server = checkNotNull(client.server) { "Live battle fixture requires the generated singleplayer world" }
        val playerId = checkNotNull(client.player).uuid
        server.execute {
            val player = checkNotNull(server.playerManager.getPlayer(playerId))
            val party = Cobblemon.storage.getParty(player)
            if (party.toList().size < 2) {
                listOf("pikachu", "bulbasaur", "eevee").forEach { species ->
                    if (party.toList().size < 3) {
                        val pokemon = PokemonProperties().apply {
                            this.species = species
                            level = 50
                        }.create(player)
                        check(party.add(pokemon)) { "Could not add $species to disposable fixture party" }
                    }
                }
            }
            CobblemonExtendedBattleUI.LOGGER.info("Live fixture party count: {}", party.toList().size)
            val world = player.serverWorld
            val opponent = PokemonProperties().apply {
                species = "charizard"
                level = 50
            }.createEntity(world)
            opponent.setPosition(player.x + 3.0, player.y, player.z + 3.0)
            check(world.spawnEntity(opponent)) { "Could not spawn disposable battle opponent" }
            pendingOpponent = opponent
            pendingPlayerId = playerId
            battleAttemptTicks = 0
            started = true
        }
    }
}
