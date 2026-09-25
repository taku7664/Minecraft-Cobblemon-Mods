package jbro.cobblemon.battleui.extended

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon
import com.cobblemon.mod.common.client.battle.ClientBattleSide
import jbro.cobblemon.battleui.extended.state.ActiveSlotTracker
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
    private var trackedBattleId: UUID? = null
    private val activeSlots = ActiveSlotTracker<UUID>()
    private val pendingBatonPassBySlot = mutableMapOf<String, BattleStateTracker.BatonPassData>()

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
        trackedBattleId = null
        activeSlots.clear()
        pendingBatonPassBySlot.clear()
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
        if (trackedBattleId != battle.battleId) {
            trackedBattleId = battle.battleId
            activeSlots.clear()
            pendingBatonPassBySlot.clear()
        }
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
            playerSide.actors.map { it.displayName.string },
            opponentSide.actors.map { it.displayName.string }
        )

        val allySlots = collectActiveSlots(playerSide)
        val opponentSlots = collectActiveSlots(opponentSide)
        handleActiveSlotChanges((allySlots + opponentSlots).associate { it.first to it.second.uuid })

        allySlots.forEach { registerActivePokemon(it.second, true, it.third) }
        opponentSlots.forEach { registerActivePokemon(it.second, false, it.third) }

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

    private fun handleActiveSlotChanges(currentSlots: Map<String, UUID>) {
        activeSlots.update(currentSlots).forEach { change ->
            change.outgoing()?.let { outgoing ->
                BattleStateTracker.clearPokemonAfterSwitch(outgoing)?.let { batonData ->
                    pendingBatonPassBySlot[change.slot()] = batonData
                }
                TeamIndicatorUI.clearTransformStatus(outgoing)
            }
            change.incoming()?.let { incoming ->
                pendingBatonPassBySlot.remove(change.slot())?.let { batonData ->
                    BattleStateTracker.applyBatonPass(incoming, batonData)
                }
            }
        }
    }

    private fun collectActiveSlots(side: ClientBattleSide): List<Triple<String, ClientBattlePokemon, String>> =
        side.actors.flatMap { actor ->
            actor.activePokemon.mapNotNull { active ->
                val pokemon = active.battlePokemon ?: return@mapNotNull null
                Triple(active.getPNX(), pokemon, actor.displayName.string)
            }
        }

    private fun registerActivePokemon(pokemon: ClientBattlePokemon, isAlly: Boolean, ownerName: String) {
        val uuid = pokemon.uuid
        BattleStateTracker.registerPokemon(uuid, pokemon.displayName.string, isAlly, ownerName)
        val speciesName = pokemon.properties.species
        speciesName?.let {
            BattleStateTracker.registerPokemon(uuid, it, isAlly, ownerName)
            BattleStateTracker.registerSpeciesId(uuid, Identifier.of("cobblemon", it))
        }
    }

    private fun clearOnBattleExit() {
        BattleStateTracker.clear()
        TeamIndicatorUI.clear()
        BattleLog.clear()
        BattleDialogue.clear()
        BattleLogWidget.clear()
        clearBattleState()
        isExpanded = false
        wasInBattle = false
        wasToggleKeyPressed = false
    }
}
