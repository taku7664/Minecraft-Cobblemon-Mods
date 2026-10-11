package jbro.cobblemon.policy.client

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.summary.Summary
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.pokemon.OpenSummaryPayload
import jbro.cobblemon.policy.menu.OpenLinkPayload
import jbro.cobblemon.policy.menu.RequestLinkPayload
import jbro.cobblemon.policy.plaza.Plaza
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.Util
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.network.chat.Component

object JbroPolicyClient : ClientModInitializer {
    private const val MENU_BUTTON_WIDTH = 100
    private const val MENU_BUTTON_HEIGHT = 20
    private const val MENU_MARGIN = 4

    override fun onInitializeClient() {
        PokenavKey.register()
        ClientPlayNetworking.registerGlobalReceiver(OpenLinkPayload.TYPE) { payload, context ->
            context.client().execute { Util.getPlatform().openUri(payload.url) }
        }
        // The wiki, Discord and plaza buttons stack in the pause menu's top-left corner, one width for all.
        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (screen !is PauseScreen || !screen.showsPauseMenu()) return@register
            val inPlaza = client.level?.dimension() == Plaza.DIMENSION
            val entries = listOf<Triple<String, String, () -> Unit>>(
                Triple(RequestLinkPayload.WIKI, "빡켓몬 위키") { ClientPlayNetworking.send(RequestLinkPayload(RequestLinkPayload.WIKI)) },
                Triple(RequestLinkPayload.DISCORD, "빡켓몬 디스코드") { ClientPlayNetworking.send(RequestLinkPayload(RequestLinkPayload.DISCORD)) },
                // The same as typing /plaza enter or exit; the menu closes so the trip is seen.
                if (inPlaza) Triple("plaza_exit", "광장 나가기") { client.player?.connection?.sendCommand("plaza exit"); screen.onClose() }
                else Triple("plaza_enter", "광장 가기") { client.player?.connection?.sendCommand("plaza enter"); screen.onClose() },
            )
            // A server without this mod cannot answer.
            val serverHasPolicy = ClientPlayNetworking.canSend(RequestLinkPayload.TYPE)
            val buttons = Screens.getButtons(screen)
            entries.forEachIndexed { index, (key, fallback, action) ->
                val button = Button.builder(Component.translatableWithFallback("menu.jbro_policy.$key", fallback)) { action() }
                    .bounds(MENU_MARGIN, MENU_MARGIN + index * (MENU_BUTTON_HEIGHT + MENU_MARGIN), MENU_BUTTON_WIDTH, MENU_BUTTON_HEIGHT).build()
                button.active = serverHasPolicy
                buttons.add(button)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(OpenSummaryPayload.TYPE) { payload, context ->
            context.client().execute {
                if (context.client().screen != null) return@execute
                val slots = CobblemonClient.storage.party.slots
                val slot = slots.indexOfFirst { it?.uuid == payload.pokemonId }
                if (slot < 0) return@execute
                // Same call as Cobblemon's M key, so the summary can be edited the same way.
                try {
                    Summary.open(slots, true, slot)
                } catch (failure: Exception) {
                    JbroPolicy.LOGGER.debug("Failed to open the summary for a right-clicked Pokemon", failure)
                }
            }
        }
    }
}
