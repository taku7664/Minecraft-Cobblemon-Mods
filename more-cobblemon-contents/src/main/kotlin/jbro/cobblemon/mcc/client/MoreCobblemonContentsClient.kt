package jbro.cobblemon.mcc.client

import net.fabricmc.api.ClientModInitializer

object MoreCobblemonContentsClient : ClientModInitializer {
    override fun onInitializeClient() {
        MccClientSessionReset.registerEvents()
        ShadowHologramShader.register()
        ShadowTerrainHologramShader.register()
        ShadowTerrainHologramRenderer.register()
        HoloBattleTerminalClientContent.register()
        BattleHubClientNetworking.register()
        ShopPlayClientNetworking.register()
        ShadowTrainerProjectionRenderer.register()
        ManagedBattleMechanicVisibilityClient.register()
        ManagedBattleContentClientNetworking.register()
    }
}
