package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource
import com.cobblemon.mod.common.api.pokemon.status.Statuses
import com.cobblemon.mod.common.battles.BattleBuilder
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.ErroredBattleStart
import com.cobblemon.mod.common.battles.ForfeitActionResponse
import com.cobblemon.mod.common.battles.MoveActionResponse
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.SwitchActionResponse
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore
import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.ForfeitConfirmationSelection
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile
import com.cobblemon.mod.common.client.gui.party.PartyTutorialToasts
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.util.isPartyBusy
import jbro.cobblemon.battleui.extended.BattleDialogue
import jbro.cobblemon.battleui.extended.BattleInfoPanel
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI
import jbro.cobblemon.battleui.extended.ui.transcript.BattleTranscriptOverlay
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
    private var targetOpened = false
    private var targetReturnedToMoves = false
    private val screenshotSaved = AtomicBoolean(false)
    private var pendingOpponent: PokemonEntity? = null
    private var pendingTrainer: NPCEntity? = null
    private var pendingPlayerId: UUID? = null
    private var battleAttemptTicks = 0

    fun install() {
        val page = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_SCREEN") ?: "command"
        require(page in setOf("command", "moves", "target", "switch", "forfeit", "log", "info"))
        val trainerBattle = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_TRAINER") == "1"
        val acceptForfeit = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_FORFEIT_ACCEPT") == "1"
        val targetSubmit = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_TARGET_SUBMIT")?.takeIf { it.isNotBlank() }
        val switchSubmit = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_SWITCH_SUBMIT")?.takeIf { it.isNotBlank() }
        require(!acceptForfeit || (trainerBattle && page == "forfeit"))
        require(targetSubmit == null || (page == "target" && targetSubmit in setOf("mouse", "keyboard", "back")))
        require(switchSubmit == null || (page == "switch" && switchSubmit in setOf("mouse", "keyboard")))
        val battleFormatName = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_BATTLE_FORMAT") ?: "single"
        val battleFormat = when (battleFormatName) {
            "single" -> BattleFormat.GEN_9_SINGLES
            "double" -> BattleFormat.GEN_9_DOUBLES
            "triple" -> BattleFormat.GEN_9_TRIPLES
            else -> error("Unsupported live capture battle format")
        }
        require(trainerBattle || battleFormat == BattleFormat.GEN_9_SINGLES)
        require(page != "target" || (trainerBattle && battleFormat.battleType.slotsPerActor > 1))
        val captureWaitTicks = System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_WAIT_TICKS")?.toInt() ?: 80
        require(captureWaitTicks in 20..400)
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
                if (switchSubmit != null) {
                    val selection = checkNotNull(screen?.getCurrentActionSelection() as? BattleSwitchPokemonSelection)
                    val chosen = selection.tiles.indexOfFirst { !it.isFainted && !it.isCurrentlyInBattle }
                    check(chosen >= 0) { "No selectable switch target in live fixture" }
                    if (switchSubmit == "mouse") {
                        val bound = BattleScreenGeometry.switchTiles(client.window.scaledWidth,
                            client.window.scaledHeight, selection.tiles.size)[chosen]
                        check(screen.mouseClicked((bound.x() + bound.width() / 2).toDouble(),
                            (bound.y() + bound.height() / 2).toDouble(), 0)) {
                            "Visible switch row did not accept a mouse click"
                        }
                    } else {
                        check(screen.keyPressed(GLFW.GLFW_KEY_Z, 0, 0)) {
                            "Focused switch row did not accept keyboard confirmation"
                        }
                    }
                    check(selection.request.response is SwitchActionResponse) {
                        "Switch choice did not become Cobblemon's native switch response"
                    }
                    CobblemonExtendedBattleUI.LOGGER.info("Live switch {} submission verified", switchSubmit)
                    screenshotPending = false
                    client.scheduleStop()
                    return@register
                }
                if (targetSubmit != null) {
                    if (targetSubmit == "back" && backPending) {
                        if (screen?.getCurrentActionSelection() !is BattleMoveSelection) {
                            check(++ticks < 40) { "Visible target Back did not return to move selection" }
                            return@register
                        }
                        CobblemonExtendedBattleUI.LOGGER.info("Live target Back mouse navigation verified")
                        screenshotPending = false
                        client.scheduleStop()
                        return@register
                    }
                    val targetSelection = checkNotNull(screen?.getCurrentActionSelection() as? BattleTargetSelection)
                    val cards = BattleTargetRenderer.bounds(targetSelection,
                        client.window.scaledWidth, client.window.scaledHeight)
                    if (targetSubmit == "back") {
                        val slots = targetSelection.request.activePokemon.getSidePokemon().count()
                        val back = BattleScreenGeometry.targetBack(client.window.scaledWidth,
                            client.window.scaledHeight, slots)
                        check(screen.mouseClicked((back.x() + back.width() / 2).toDouble(),
                            (back.y() + back.height() / 2).toDouble(), 0))
                        backPending = true
                        ticks = 0
                        return@register
                    }
                    if (targetSubmit == "mouse") {
                        val disabled = targetSelection.targetTiles.indexOfFirst { !it.selectable }
                        if (disabled >= 0) {
                            val bound = cards[disabled]
                            check(screen.mouseClicked((bound.x() + bound.width() / 2).toDouble(),
                                (bound.y() + bound.height() / 2).toDouble(), 0))
                            check(targetSelection.request.response == null) {
                                "Disabled visual target submitted a battle response"
                            }
                        }
                        val chosen = targetSelection.targetTiles.indexOfFirst { tile ->
                            tile.selectable && !tile.target.isAllied(targetSelection.request.activePokemon)
                        }
                        check(chosen >= 0) { "No selectable opponent target in live fixture" }
                        val bound = cards[chosen]
                        check(screen.mouseClicked((bound.x() + bound.width() / 2).toDouble(),
                            (bound.y() + bound.height() / 2).toDouble(), 0)) {
                            "Visible target card center did not accept a mouse click"
                        }
                    } else {
                        val focused = KeyboardTileFocus.focusedIndex(targetSelection.targetTiles)
                        check(focused >= 0 && targetSelection.targetTiles[focused].selectable)
                        check(screen.keyPressed(GLFW.GLFW_KEY_Z, 0, 0)) {
                            "Focused target did not accept keyboard confirmation"
                        }
                    }
                    check(targetSelection.request.response is MoveActionResponse) {
                        "Target choice did not become Cobblemon's native move response"
                    }
                    CobblemonExtendedBattleUI.LOGGER.info("Live target {} submission verified", targetSubmit)
                    screenshotPending = false
                    client.scheduleStop()
                    return@register
                }
                if (acceptForfeit) {
                    if (!backPending) {
                        val confirmation = checkNotNull(screen?.getCurrentActionSelection() as? ForfeitConfirmationSelection)
                        val accept = BattleScreenGeometry.forfeitAccept(client.window.scaledWidth,
                            client.window.scaledHeight)
                        check(screen.mouseClicked((accept.x() + accept.width() / 2).toDouble(),
                            (accept.y() + accept.height() / 2).toDouble(), 0))
                        check(confirmation.request.response is ForfeitActionResponse) {
                            "Trainer forfeit visual center did not submit Cobblemon's native response"
                        }
                        backPending = true
                        ticks = 0
                        return@register
                    }
                    if (CobblemonClient.battle != null) {
                        check(++ticks < 200) { "Trainer battle did not end after forfeit response" }
                        return@register
                    }
                    CobblemonExtendedBattleUI.LOGGER.info("Live trainer forfeit accept ended the battle")
                }
                if (!acceptForfeit && screen != null && page != "command") {
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
                    if (page == "target" && !targetReturnedToMoves) {
                        if (screen.getCurrentActionSelection() !is BattleMoveSelection) {
                            check(++ticks < 40) { "Target cancel did not return to moves" }
                            return@register
                        }
                        check(screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0))
                        targetReturnedToMoves = true
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
                if (System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_COMPLETE_PARTY_TUTORIAL") == "1") {
                    // Only the opt-in fixture: compare the stable battle layout after Cobblemon's onboarding.
                    PartyTutorialToasts.onArrowKeySwitchedSlot()
                }
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
                    "log" -> {
                        check(screen.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0))
                        check(BattleTranscriptOverlay.isOpen)
                    }
                    "info" -> {
                        // Tab's registered binding is polled by BattleInfoPanel during the HUD pass.
                        BattleInfoPanel.toggle()
                        check(BattleInfoPanel.isExpanded)
                    }
                    "moves", "target", "switch" -> {
                        val tile = root.tiles[if (page == "switch") 1 else 0]
                        val clicked = screen.mouseClicked(
                            (tile.x + BattleOptionTile.OPTION_WIDTH / 2).toDouble(),
                            (tile.y + BattleOptionTile.OPTION_HEIGHT / 2).toDouble(), 0)
                        check(clicked) { "Native $page command did not accept its visible center click" }
                    }
                    "forfeit" -> {
                        if (trainerBattle) {
                            val tile = root.tiles.single { it.resource == BattleGUI.forfeitResource }
                            check(screen.mouseClicked((tile.x + BattleOptionTile.OPTION_WIDTH / 2).toDouble(),
                                (tile.y + BattleOptionTile.OPTION_HEIGHT / 2).toDouble(), 0)) {
                                "Native trainer forfeit command did not accept its visible center click"
                            }
                        } else {
                            screen.changeActionSelection(ForfeitConfirmationSelection(screen, root.request))
                        }
                    }
                }
                pageOpened = true
                ticks = 0
                val opener = when {
                    page == "log" -> "Shift key"
                    page == "info" -> "panel toggle (Tab input not simulated)"
                    page == "forfeit" && !trainerBattle -> "native selection constructor"
                    else -> "mouse click"
                }
                CobblemonExtendedBattleUI.LOGGER.info("Live battle page '{}' opened via {}", page, opener)
                return@register
            }
            if (page == "target" && !targetOpened) {
                val moves = selection as? BattleMoveSelection ?: return@register
                if (++ticks < 5) return@register
                check(screen.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0))
                check(KeyboardTileFocus.focusedIndex(moves.moveTiles) >= 0)
                check(screen.keyPressed(GLFW.GLFW_KEY_Z, 0, 0)) {
                    "Keyboard move confirm was not handled in multi battle"
                }
                targetOpened = true
                ticks = 0
                CobblemonExtendedBattleUI.LOGGER.info("Live multi-battle move confirmed by keyboard")
                return@register
            }
            if (page != "command") {
                val matches = when (page) {
                    "log" -> BattleTranscriptOverlay.isOpen
                    "info" -> BattleInfoPanel.isExpanded
                    "moves" -> selection is BattleMoveSelection
                    "target" -> selection is BattleTargetSelection
                    "switch" -> selection is BattleSwitchPokemonSelection
                    else -> selection is ForfeitConfirmationSelection
                }
                if (!matches) return@register
            }
            if (!navigationChecked && ticks >= 5 && page !in setOf("log", "info")) {
                check(screen.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0)) { "Down navigation not handled on $page" }
                when (selection) {
                    is BattleGeneralActionSelection -> check(selection.tiles.any { it.isFocused })
                    is BattleMoveSelection -> check(KeyboardTileFocus.focusedIndex(selection.moveTiles) >= 0)
                    is BattleTargetSelection -> {
                        val cards = BattleTargetRenderer.bounds(selection,
                            client.window.scaledWidth, client.window.scaledHeight)
                        val initial = KeyboardTileFocus.focusedIndex(selection.targetTiles)
                        check(initial >= 0)
                        check(screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0))
                        val right = KeyboardTileFocus.focusedIndex(selection.targetTiles)
                        check(right >= 0 && cards[right].x() > cards[initial].x()) {
                            "Right navigation did not reach the visible opponent column"
                        }
                    }
                    is BattleSwitchPokemonSelection -> check(KeyboardTileFocus.focusedIndex(selection.tiles) >= 0)
                    is ForfeitConfirmationSelection -> {
                        check(screen.keyPressed(GLFW.GLFW_KEY_LEFT, 0, 0))
                        check(screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0))
                    }
                }
                navigationChecked = true
                CobblemonExtendedBattleUI.LOGGER.info("Live battle keyboard navigation verified on '{}'", page)
            }
            if (++ticks < captureWaitTicks) return@register
            if (battleFormat.battleType.slotsPerActor > 1) {
                val battle = checkNotNull(CobblemonClient.battle)
                val selfSlots = battle.side1.activeClientBattlePokemon.count { it.battlePokemon != null }
                val opponentSlots = battle.side2.activeClientBattlePokemon.count { it.battlePokemon != null }
                check(selfSlots == battleFormat.battleType.slotsPerActor &&
                    opponentSlots == battleFormat.battleType.slotsPerActor) {
                    "Expected ${battleFormat.battleType.slotsPerActor} HUD slots per side, got $selfSlots/$opponentSlots"
                }
                CobblemonExtendedBattleUI.LOGGER.info("Live {} battle has {} / {} active HUD slots",
                    battleFormatName, selfSlots, opponentSlots)
            }
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
            val player = pendingPlayerId?.let(server.playerManager::getPlayer) ?: return@register
            val trainer = pendingTrainer
            if (trainer != null) {
                if (++battleAttemptTicks < 10) return@register
                pendingTrainer = null
                pendingPlayerId = null
                when (val result = BattleBuilder.pvn(player, trainer, battleFormat = battleFormat)) {
                    is SuccessfulBattleStart -> CobblemonExtendedBattleUI.LOGGER.info(
                        "Live trainer battle started in fixture world: {}", result.battle.battleId)
                    is ErroredBattleStart -> CobblemonExtendedBattleUI.LOGGER.error(
                        "Live trainer battle rejected by Cobblemon: general={}, participant={}",
                        result.generalErrors, result.participantErrors)
                }
                return@register
            }
            val opponent = pendingOpponent ?: return@register
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
            if (System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_STATUS") == "burn") {
                // Player parties are not auto-healed by this fixture's trainer battle.
                checkNotNull(party.toList().firstOrNull()).applyStatus(Statuses.BURN)
            }
            if (System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_EXPERIENCE") == "half") {
                val pokemon = checkNotNull(party.toList().firstOrNull())
                val next = pokemon.experienceGroup.getExperience(pokemon.level + 1)
                val start = pokemon.experienceGroup.getExperience(pokemon.level)
                val target = start + (next - start) / 2
                pokemon.addExperience(SidemodExperienceSource("battle_ui_fixture"),
                    (target - pokemon.experience).coerceAtLeast(0))
            }
            CobblemonExtendedBattleUI.LOGGER.info("Live fixture party count: {}", party.toList().size)
            val world = player.serverWorld
            if (System.getenv("COBBLEMON_BATTLE_UI_CAPTURE_TRAINER") == "1") {
                val trainer = NPCEntity(world)
                trainer.npc = NPCClasses.classes.sortedBy { it.id.toString() }.first()
                val party = NPCPartyStore(trainer)
                listOf("charizard", "squirtle", "meowth").forEach { species ->
                    check(party.add(PokemonProperties().apply {
                        this.species = species
                        level = 50
                    }.create())) { "Could not add trainer's $species" }
                }
                party.initialize()
                trainer.party = party
                trainer.setPosition(player.x + 3.0, player.y, player.z + 3.0)
                check(world.spawnEntity(trainer)) { "Could not spawn disposable trainer" }
                pendingTrainer = trainer
            } else {
                val opponent = PokemonProperties().apply {
                    species = "charizard"
                    level = 50
                }.createEntity(world)
                opponent.setPosition(player.x + 3.0, player.y, player.z + 3.0)
                check(world.spawnEntity(opponent)) { "Could not spawn disposable battle opponent" }
                pendingOpponent = opponent
            }
            pendingPlayerId = playerId
            battleAttemptTicks = 0
            started = true
        }
    }
}
