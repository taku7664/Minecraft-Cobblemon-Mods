package jbro.cobblemon.policy.client

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.summary.Summary
import jbro.cobblemon.policy.JbroPolicy
import jbro.cobblemon.policy.pokemon.OpenSummaryPayload
import jbro.cobblemon.policy.menu.OpenLinkPayload
import jbro.cobblemon.policy.menu.OpenPichuPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.Util
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.network.chat.Component

object JbroPolicyClient : ClientModInitializer {
    private const val MENU_BUTTON_WIDTH = 100
    private const val MENU_BUTTON_HEIGHT = 20
    private const val MENU_MARGIN = 4

    /** Pichu's screen draws in Cobblemon UI's look, so it needs that mod. */
    private val pichuAvailable by lazy { FabricLoader.getInstance().isModLoaded("cobblemon_ui") }

    override fun onInitializeClient() {
        PokenavKey.register()
        MccHubTabRequest.register()
        if (pichuAvailable) InvMoveCompat.register()
        // The server's /피츄, run by an NPC talk, asks for the screen.
        if (pichuAvailable) ClientPlayNetworking.registerGlobalReceiver(OpenPichuPayload.TYPE) { _, context ->
            context.client().execute { context.client().setScreen(PichuScreen()) }
        }
        if (pichuAvailable) ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (name in listOf("피츄", "pichu")) dispatcher.register(ClientCommandManager.literal(name).executes { context ->
                // After the chat screen this command came from has closed.
                context.source.client.tell { context.source.client.setScreen(PichuScreen()) }
                1
            })
        }
        ClientPlayNetworking.registerGlobalReceiver(OpenLinkPayload.TYPE) { payload, context ->
            context.client().execute { Util.getPlatform().openUri(payload.url) }
        }
        // Pichu's helper is the pause menu's one extra, in its top-left corner; the plaza, wiki and Discord are inside it.
        if (pichuAvailable) ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (screen !is PauseScreen || !screen.showsPauseMenu()) return@register
            Screens.getButtons(screen).add(Button.builder(Component.translatableWithFallback("menu.jbro_policy.pichu", "피츄 도우미")) {
                client.setScreen(PichuScreen())
            }.bounds(MENU_MARGIN, MENU_MARGIN, MENU_BUTTON_WIDTH, MENU_BUTTON_HEIGHT).build())
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
