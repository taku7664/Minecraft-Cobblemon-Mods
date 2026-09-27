package jbro.cobblemon.mcc.client

import net.fabricmc.api.ClientModInitializer
object BattleTowerContentClient : ClientModInitializer {
    override fun onInitializeClient() {
        TowerPlayClientNetworking.register()
    }
}
