package jbro.cobblemon.morebattlecontent.client

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import java.util.WeakHashMap
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpSpectatorInputPolicy
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal object PvpLoungeSpectatorControls {
    private var active = false
    private val exitButtons = WeakHashMap<Screen, MbcStyledButton>()
    private var exitPending = false

    fun register() {
        ClientTickEvents.START_CLIENT_TICK.register(::enforce)
        ClientTickEvents.END_CLIENT_TICK.register(::enforce)
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ -> installExitButton(screen) }
        MbcClientSessionReset.onReset("PvP spectator controls") {
            setActive(false)
            PvpPlayClientNetworking.resetLoungeExitRequest()
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) {
            val client = Minecraft.getInstance()
            enforce(client)
            client.screen?.let(::installExitButton)
        } else {
            exitButtons.values.forEach { button ->
                button.active = false
                button.visible = false
            }
            exitButtons.clear()
            exitPending = false
        }
    }

    fun setExitPending(value: Boolean) {
        exitPending = value
        exitButtons.values.forEach { button -> button.active = active && !value }
    }

    @JvmStatic
    fun hidesNativeBackButton(): Boolean = PvpSpectatorBattleUiPolicy.hidesNativeBackButton(
        loungeActive = active,
        battleSpectating = CobblemonClient.battle?.spectating == true,
    )

    @JvmStatic
    fun restoreDetailedView(nativeBindingCanApplyChange: Boolean): Boolean {
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        val battle = CobblemonClient.battle ?: return false
        if (!PvpSpectatorBattleUiPolicy.restoresDetailedView(
                loungeActive = active,
                nativeBindingCanApplyChange = nativeBindingCanApplyChange,
                localPlayerIsSpectator = player.isSpectator,
                battleSpectating = battle.spectating,
                battleMinimised = battle.minimised,
                screenOpen = client.screen != null,
                guiHidden = client.options.hideGui,
            )
        ) {
            return false
        }
        battle.minimised = false
        client.setScreen(BattleGUI())
        return true
    }

    private fun enforce(client: Minecraft) {
        if (!active) return
        val allowBattleToggle = CobblemonClient.battle?.spectating == true
        client.options.keyMappings
            .asSequence()
            .filter { PvpSpectatorInputPolicy.blocks(it.name, allowBattleToggle) }
            .forEach { mapping ->
                mapping.isDown = false
                while (mapping.consumeClick()) Unit
            }
    }

    private fun installExitButton(screen: Screen) {
        if (!active || screen !is BattleGUI) return
        exitButtons.remove(screen)?.let { previous ->
            previous.active = false
            previous.visible = false
        }
        val button = MbcStyledButton(
            PvpLoungeExitButtonLayout.bounds(screen.height),
            Component.translatable("screen.cobblemon_more_battle_content.pvp.return"),
            MbcButtonTone.SECONDARY,
        ) { PvpPlayClientNetworking.exitLoungeSpectator() }
        button.active = !exitPending
        Screens.getButtons(screen).add(button)
        exitButtons[screen] = button
    }
}

internal object PvpLoungeExitButtonLayout {
    fun bounds(screenHeight: Int) = TowerPlayRect(
        left = 8,
        top = (screenHeight - 56).coerceAtLeast(8),
        width = 104,
        height = 20,
    )
}
