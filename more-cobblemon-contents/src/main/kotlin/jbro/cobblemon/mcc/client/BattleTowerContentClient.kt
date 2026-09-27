package jbro.cobblemon.mcc.client

internal object BattleTowerContentClient {
    fun initialize() {
        TowerPlayClientNetworking.register()
    }
}
