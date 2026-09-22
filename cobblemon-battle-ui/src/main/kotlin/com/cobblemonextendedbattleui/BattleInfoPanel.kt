package jbro.cobblemon.battleui.extended

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.battleui.extended.ui.champions.ChampionsBattleInfoOverlay
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import java.util.UUID

/**
 * Battle-info lifecycle and public battle-state synchronization.
 * The old draggable Extended Battle UI window has intentionally been retired;
 * presentation is owned by [ChampionsBattleInfoOverlay].
 */
object BattleInfoPanel {
    var isExpanded: Boolean = false
        private set

    private var wasToggleKeyPressed = false
    private var wasInBattle = false
    private var previouslyActiveUUIDs: Set<UUID> = emptySet()

    fun initialize() {
        PanelConfig.load()
        isExpanded = false
        ChampionsBattleInfoOverlay.clear()
    }

    fun toggle() {
        isExpanded = !isExpanded
        if (isExpanded) ChampionsBattleInfoOverlay.onOpened()
    }

    fun handleKeyPressed(keyCode: Int, scanCode: Int): Boolean =
        ChampionsBattleInfoOverlay.handleKeyPressed(keyCode, scanCode)

    fun clearBattleState() {
        previouslyActiveUUIDs = emptySet()
        ChampionsBattleInfoOverlay.clear()
    }

    /** The fixed Champions screen has no scrollable legacy panel. */
    fun onScroll(mouseX: Double, mouseY: Double, deltaY: Double): Boolean = false

    /** Updates battle state during the HUD pass without drawing the modal. */
    fun update(syncOverlay: Boolean = true) {
        val battle = CobblemonClient.battle
        if (battle == null) {
            if (wasInBattle) clearOnBattleExit()
            return
        }

        wasInBattle = true
        BattleStateTracker.checkBattleChanged(battle.battleId)

        if (battle.minimised) {
            isExpanded = false
            return
        }

        if (syncOverlay) {
            handleToggleInput(MinecraftClient.getInstance())
        } else {
            isExpanded = false
        }

        val mc = MinecraftClient.getInstance()
        val playerUuid = mc.player?.uuid ?: return
        val playerInSide1 = battle.side1.actors.any { it.uuid == playerUuid }
        val playerInSide2 = battle.side2.actors.any { it.uuid == playerUuid }
        val isSpectating = !playerInSide1 && !playerInSide2
        val playerSide = when {
            playerInSide1 -> battle.side1
            playerInSide2 -> battle.side2
            else -> battle.side2
        }
        val opponentSide = if (playerSide == battle.side1) battle.side2 else battle.side1

        BattleStateTracker.setSpectating(isSpectating)
        BattleStateTracker.setPlayerNames(
            playerSide.actors.firstOrNull()?.displayName?.string.orEmpty(),
            opponentSide.actors.firstOrNull()?.displayName?.string.orEmpty()
        )

        val allyActive = playerSide.activeClientBattlePokemon.mapNotNull { it.battlePokemon }
        val opponentActive = opponentSide.activeClientBattlePokemon.mapNotNull { it.battlePokemon }
        val activeUuids = (allyActive + opponentActive).map { it.uuid }.toSet()
        clearSwitchedPokemonState(activeUuids)

        allyActive.forEach { registerActivePokemon(it.uuid, it.displayName.string, it.properties.species, true) }
        opponentActive.forEach { registerActivePokemon(it.uuid, it.displayName.string, it.properties.species, false) }
        allyActive.forEach { BattleStateTracker.applyBatonPassIfPending(it.uuid) }
        opponentActive.forEach { BattleStateTracker.applyBatonPassIfPending(it.uuid) }

        if (syncOverlay) {
            ChampionsBattleInfoOverlay.sync(playerSide, opponentSide, playerUuid, isSpectating)
        }
    }

    /** Drawn from BattleGUI.render RETURN so the modal owns the final GUI layer. */
    fun renderForeground(context: DrawContext) {
        val mc = MinecraftClient.getInstance()
        if (!isExpanded || mc.options.hudHidden || CobblemonClient.battle?.minimised != false) return
        ChampionsBattleInfoOverlay.render(context)
    }

    private fun handleToggleInput(mc: MinecraftClient) {
        val key = InputUtil.fromTranslationKey(CobblemonExtendedBattleUIClient.togglePanelKey.boundKeyTranslationKey)
        val down = UIUtils.isKeyOrButtonPressed(mc.window.handle, key)
        if (down && !wasToggleKeyPressed) toggle()
        wasToggleKeyPressed = down
    }

    private fun clearSwitchedPokemonState(currentActiveUuids: Set<UUID>) {
        previouslyActiveUUIDs.filterNot(currentActiveUuids::contains).forEach { uuid ->
            if (!BattleStateTracker.prepareBatonPassIfUsed(uuid)) {
                BattleStateTracker.clearPokemonStats(uuid)
                BattleStateTracker.clearPokemonVolatiles(uuid)
            }
            BattleStateTracker.restoreOriginalTypes(uuid)
        }
        previouslyActiveUUIDs = currentActiveUuids
    }

    private fun registerActivePokemon(uuid: UUID, displayName: String, speciesName: String?, isAlly: Boolean) {
        BattleStateTracker.registerPokemon(uuid, displayName, isAlly)
        speciesName?.let {
            BattleStateTracker.registerPokemon(uuid, it, isAlly)
            BattleStateTracker.registerSpeciesId(uuid, Identifier.of("cobblemon", it))
        }
    }

    private fun clearOnBattleExit() {
        BattleStateTracker.clear()
        TeamIndicatorUI.clear()
        BattleLog.clear()
        BattleLogWidget.clear()
        clearBattleState()
        isExpanded = false
        wasInBattle = false
        wasToggleKeyPressed = false
    }
}
