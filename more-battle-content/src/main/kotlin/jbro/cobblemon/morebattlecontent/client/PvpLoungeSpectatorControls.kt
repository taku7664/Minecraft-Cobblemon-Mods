package jbro.cobblemon.morebattlecontent.client

import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import java.util.WeakHashMap
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpSpectatorInputPolicy
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal object PvpLoungeSpectatorControls {
    private var active = false
    private val exitButtons = WeakHashMap<Screen, MbcStyledButton>()
    private var previousCycleKeyDown = false
    private var previousBattleCamMode: String? = null

    fun register() {
        ClientTickEvents.START_CLIENT_TICK.register(::enforce)
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            enforce(client)
            recoverBattleCameraCycle(client)
        }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ -> installExitButton(screen) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> setActive(false) }
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) {
            val client = Minecraft.getInstance()
            previousBattleCamMode = OptionalBattleCamAccess.modeName()
            enforce(client)
            client.screen?.let(::installExitButton)
        } else {
            exitButtons.values.forEach { button ->
                button.active = false
                button.visible = false
            }
            exitButtons.clear()
            previousCycleKeyDown = false
            previousBattleCamMode = null
        }
    }

    private fun enforce(client: Minecraft) {
        if (!active) return
        client.options.keyMappings
            .asSequence()
            .filter { PvpSpectatorInputPolicy.blocks(it.name) }
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
            TowerPlayRect(8, (screen.height - 28).coerceAtLeast(8), 104, 20),
            Component.translatable("screen.cobblemon_more_battle_content.pvp.return"),
            MbcButtonTone.SECONDARY,
        ) { PvpPlayClientNetworking.exitLoungeSpectator() }
        Screens.getButtons(screen).add(button)
        exitButtons[screen] = button
    }

    private fun recoverBattleCameraCycle(client: Minecraft) {
        if (!active) return
        val mapping = client.options.keyMappings.firstOrNull { it.name == BATTLE_CAM_CYCLE_KEY } ?: return
        val currentMode = OptionalBattleCamAccess.modeName()
        if (PvpBattleCamCycleRecovery.shouldRecover(
                mapping.isDown,
                previousCycleKeyDown,
                previousBattleCamMode,
                currentMode,
            ) && OptionalBattleCamAccess.cycleMode()
        ) {
            while (mapping.consumeClick()) Unit
        }
        previousCycleKeyDown = mapping.isDown
        previousBattleCamMode = OptionalBattleCamAccess.modeName()
    }

    private const val BATTLE_CAM_CYCLE_KEY = "key.battlecam.cycle_mode"
}
