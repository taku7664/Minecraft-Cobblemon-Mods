package jbro.cobblemon.mcc.client

internal object PvpContentClient {
    fun initialize() {
        PvpLoungeSpectatorControls.register()
        PvpRoomHudOverlay.register()
        PvpPlayClientNetworking.register()
    }
}
