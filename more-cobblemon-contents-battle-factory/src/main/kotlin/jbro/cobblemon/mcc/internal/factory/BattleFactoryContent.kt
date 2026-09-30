package jbro.cobblemon.mcc.internal.factory

import net.fabricmc.api.ModInitializer
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.terminal.HoloTerminalPalette
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.hub.BattleHubIds
import jbro.cobblemon.mcc.internal.compat.fabric.FactoryCatalogResources
import jbro.cobblemon.mcc.internal.compat.fabric.FactoryCommandRuntime
import net.minecraft.resources.ResourceLocation

/** Battle Factory server initialization. */
object BattleFactoryContent : ModInitializer {
    override fun onInitialize() {
        FactoryCatalogResources.register()
        FactoryCommandRuntime.registerServer()
        HoloTerminals.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "battle_factory_terminal"), ManagedBattleContentIds.BATTLE_FACTORY,
            listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, ManagedBattleContentIds.BATTLE_FACTORY),
            // Factory green with an amber scan line.
            HoloTerminalPalette(0x173D24, 0x2A8A4A, 0x5CE68A, 0xA6F5B8, 0xC8FFD2, 0x3CB86A, 0xFFC857))
    }

    const val MOD_ID: String = "more_cobblemon_contents_battle_factory"
}
