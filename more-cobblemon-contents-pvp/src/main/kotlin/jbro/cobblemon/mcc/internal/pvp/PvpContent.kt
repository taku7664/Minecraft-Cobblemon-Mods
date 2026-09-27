package jbro.cobblemon.mcc.internal.pvp

import net.fabricmc.api.ModInitializer
import jbro.cobblemon.mcc.internal.compat.fabric.PvpLoungeProtection
import jbro.cobblemon.mcc.internal.pvp.network.PvpPlayNetworking

/** PvP server initialization. */
object PvpContent : ModInitializer {
    override fun onInitialize() {
        PvpPlayNetworking.registerServer()
        PvpLoungeProtection.registerServer()
    }
}
