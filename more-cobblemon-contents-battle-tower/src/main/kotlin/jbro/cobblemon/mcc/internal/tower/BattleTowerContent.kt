package jbro.cobblemon.mcc.internal.tower

import net.fabricmc.api.ModInitializer
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.terminal.HoloTerminalPalette
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.hub.BattleHubIds
import jbro.cobblemon.mcc.internal.command.AiTestCommands
import jbro.cobblemon.mcc.internal.command.MccCommandContributors
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173AiTestBattleRuntime
import jbro.cobblemon.mcc.internal.compat.fabric.TowerOpponentCatalogResources
import jbro.cobblemon.mcc.internal.tower.application.BattleTowerContentApplication
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayNetworking
import net.minecraft.resources.ResourceLocation

/** Battle Tower server initialization, including the Cynthia AI test that runs on Tower sets. */
object BattleTowerContent : ModInitializer {
    override fun onInitialize() {
        TowerOpponentCatalogResources.register()
        TowerPlayNetworking.registerServer()
        MoreCobblemonContents.CONTENTS.register(BattleTowerContentApplication(TowerPlayNetworking))
        MccCommandContributors.register { AiTestCommands.build(Cobblemon173AiTestBattleRuntime) }
        HoloTerminals.register(ResourceLocation.fromNamespaceAndPath(MOD_ID, "battle_tower_terminal"),
            listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP, ManagedBattleContentIds.BATTLE_TOWER),
            // Tower crimson with an ember crystal.
            HoloTerminalPalette(0x5A1620, 0xA8283A, 0xFF5A4E, 0xFF9A7A, 0xFFB38A, 0xE0483C, 0xFF7A60))
    }

    const val MOD_ID: String = "more_cobblemon_contents_battle_tower"
}
