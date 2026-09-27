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
        // Content mods call these from their own client initializers once they are split out.
        BattleTowerContentClient.initialize()
        ShadowTrainerProjectionRenderer.register()
        ManagedBattleMechanicVisibilityClient.register()
        ManagedBattleContentClientNetworking.register()
    }
}
