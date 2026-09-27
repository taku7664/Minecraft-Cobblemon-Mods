package jbro.cobblemon.mcc.internal.factory

import jbro.cobblemon.mcc.internal.compat.fabric.FactoryCatalogResources
import jbro.cobblemon.mcc.internal.compat.fabric.FactoryCommandRuntime

/** Battle Factory server initialization. */
internal object BattleFactoryContent {
    fun initialize() {
        FactoryCatalogResources.register()
        FactoryCommandRuntime.registerServer()
    }
}
