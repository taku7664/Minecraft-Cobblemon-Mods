package jbro.cobblemon.mcc.internal.tower

import net.fabricmc.api.ModInitializer
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.command.AiTestCommands
import jbro.cobblemon.mcc.internal.command.MccCommandContributors
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173AiTestBattleRuntime
import jbro.cobblemon.mcc.internal.compat.fabric.TowerOpponentCatalogResources
import jbro.cobblemon.mcc.internal.tower.application.BattleTowerContentApplication
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayNetworking

/** Battle Tower server initialization, including the Cynthia AI test that runs on Tower sets. */
object BattleTowerContent : ModInitializer {
    override fun onInitialize() {
        TowerOpponentCatalogResources.register()
        TowerPlayNetworking.registerServer()
        MoreCobblemonContents.CONTENTS.register(BattleTowerContentApplication(TowerPlayNetworking))
        MccCommandContributors.register { AiTestCommands.build(Cobblemon173AiTestBattleRuntime) }
    }
}
