package jbro.cobblemon.uikit.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component

object CobblemonUiKitClient : ClientModInitializer {
    override fun onInitializeClient() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment) return

        GalleryWorldCaptureHarness.installFromEnvironment()

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("cobblemon-ui-gallery").executes { context ->
                    context.source.client.setScreen(ComponentGalleryScreen())
                    context.source.sendFeedback(Component.translatable("command.cobblemon_ui_kit.gallery.opened"))
                    1
                }
            )
        }
    }
}
