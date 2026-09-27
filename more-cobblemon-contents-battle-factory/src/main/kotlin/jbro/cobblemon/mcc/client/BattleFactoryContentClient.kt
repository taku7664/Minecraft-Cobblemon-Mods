package jbro.cobblemon.mcc.client

import net.fabricmc.api.ClientModInitializer
object BattleFactoryContentClient : ClientModInitializer {
    override fun onInitializeClient() {
        FactoryPlayClientNetworking.register()
    }
}
