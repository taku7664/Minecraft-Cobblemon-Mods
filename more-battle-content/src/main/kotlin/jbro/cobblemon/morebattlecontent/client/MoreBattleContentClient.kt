package jbro.cobblemon.morebattlecontent.client

import net.fabricmc.api.ClientModInitializer

object MoreBattleContentClient : ClientModInitializer {
    override fun onInitializeClient() {
        ShadowHologramShader.register()
        ShadowPokemonHologramShader.register()
        ShadowTerrainHologramShader.register()
        ShadowTerrainHologramRenderer.register()
        HoloBattleTerminalClientContent.register()
        BattleHubClientNetworking.register()
        ShopPlayClientNetworking.register()
        TowerPlayClientNetworking.register()
        FactoryPlayClientNetworking.register()
        PvpLoungeSpectatorControls.register()
        PvpRoomHudOverlay.register()
        PvpPlayClientNetworking.register()
        ShadowTrainerProjectionRenderer.register()
        ManagedBattleMechanicVisibilityClient.register()
        ManagedBattleContentClientNetworking.register()
    }
}
