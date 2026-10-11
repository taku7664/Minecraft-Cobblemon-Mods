package kr.parkjh.pokefusion.client

import kr.parkjh.pokefusion.PokeFusionMenu
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.gui.screens.MenuScreens

class PokeFusionClient : ClientModInitializer {
    override fun onInitializeClient() {
        MenuScreens.register(PokeFusionMenu.TYPE, ::PokeFusionScreen)
        PokeFusionTooltip.register()
    }
}
