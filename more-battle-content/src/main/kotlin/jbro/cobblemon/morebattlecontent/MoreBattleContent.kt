package jbro.cobblemon.morebattlecontent

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import jbro.cobblemon.morebattlecontent.internal.application.DefaultBattleContentApplicationService
import jbro.cobblemon.morebattlecontent.internal.command.BattleContentCommands
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.HoloBattleTerminalContent
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.FactoryCatalogResources
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.FactoryCommandRuntime
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.PvpLoungeProtection
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.TowerOpponentCatalogResources
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.BattlePointShopCatalogResources
import jbro.cobblemon.morebattlecontent.internal.tower.application.BattleTowerContentApplication
import jbro.cobblemon.morebattlecontent.internal.tower.network.TowerPlayNetworking
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayEntryContext
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpPlayNetworking
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubNetworking
import jbro.cobblemon.morebattlecontent.internal.shadow.ShadowTrainerProjectionNetworking
import jbro.cobblemon.morebattlecontent.internal.presentation.BattleArenaHologramNetworking
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleMechanicVisibilityNetworking
import jbro.cobblemon.morebattlecontent.internal.battle.ManagedBattleContentNetworking
import jbro.cobblemon.morebattlecontent.internal.bp.shop.ShopPlayNetworking

object MoreBattleContent : ModInitializer {
    const val MOD_ID: String = "cobblemon_more_battle_content"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    internal val CONTENTS: DefaultBattleContentApplicationService by lazy {
        DefaultBattleContentApplicationService(listOf(BattleTowerContentApplication(TowerPlayNetworking)))
    }

    override fun onInitialize() {
        HoloBattleTerminalContent.register { player, verification ->
            BattleHubNetworking.open(
                player,
                TowerPlayEntryContext.VerifiedTerminal(
                    entryContextId = verification.entryContextId,
                    terminalId = verification.terminalId,
                    dimensionId = verification.dimensionId,
                    x = verification.x,
                    y = verification.y,
                    z = verification.z,
                ),
            )
        }
        TowerOpponentCatalogResources.register()
        FactoryCatalogResources.register()
        BattlePointShopCatalogResources.register()
        TowerPlayNetworking.registerServer()
        FactoryCommandRuntime.registerServer()
        PvpPlayNetworking.registerServer()
        PvpLoungeProtection.registerServer()
        ShopPlayNetworking.registerServer()
        BattleHubNetworking.registerServer()
        ShadowTrainerProjectionNetworking.registerServer()
        BattleArenaHologramNetworking.registerServer()
        ManagedBattleMechanicVisibilityNetworking.registerServer()
        ManagedBattleContentNetworking.registerServer()
        BattleContentCommands.register(
            CONTENTS,
            openScreen = BattleHubNetworking::open,
        )
    }
}
