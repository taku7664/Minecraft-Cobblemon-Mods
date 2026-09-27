package jbro.cobblemon.mcc.internal.pvp

import jbro.cobblemon.mcc.internal.compat.fabric.PvpLoungeProtection
import jbro.cobblemon.mcc.internal.pvp.network.PvpPlayNetworking

/** PvP server initialization. */
internal object PvpContent {
    fun initialize() {
        PvpPlayNetworking.registerServer()
        PvpLoungeProtection.registerServer()
    }
}
