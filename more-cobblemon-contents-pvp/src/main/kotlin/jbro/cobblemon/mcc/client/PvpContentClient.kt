package jbro.cobblemon.mcc.client

import net.fabricmc.api.ClientModInitializer
object PvpContentClient : ClientModInitializer {
    override fun onInitializeClient() {
        PvpLoungeSpectatorControls.register()
        PvpRoomHudOverlay.register()
        PvpPlayClientNetworking.register()
    }
}
