package jbro.cobblemon.mcc.internal.factory

import net.fabricmc.api.ModInitializer
import jbro.cobblemon.mcc.internal.compat.fabric.FactoryCatalogResources
import jbro.cobblemon.mcc.internal.compat.fabric.FactoryCommandRuntime

/** Battle Factory server initialization. */
object BattleFactoryContent : ModInitializer {
    override fun onInitialize() {
        FactoryCatalogResources.register()
        FactoryCommandRuntime.registerServer()
    }
}
