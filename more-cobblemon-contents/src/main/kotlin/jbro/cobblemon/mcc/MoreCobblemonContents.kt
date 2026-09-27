package jbro.cobblemon.mcc

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import jbro.cobblemon.mcc.internal.application.DefaultBattleContentApplicationService
import jbro.cobblemon.mcc.internal.command.BattleContentCommands
import jbro.cobblemon.mcc.internal.compat.fabric.HoloBattleTerminalContent
import jbro.cobblemon.mcc.internal.compat.fabric.FactoryCatalogResources
import jbro.cobblemon.mcc.internal.compat.fabric.FactoryCommandRuntime
import jbro.cobblemon.mcc.internal.compat.fabric.PvpLoungeProtection
import jbro.cobblemon.mcc.internal.compat.fabric.ManagedServerEphemeralStateCleanup
import jbro.cobblemon.mcc.internal.compat.fabric.ManagedBattleLifecycleEvents
import jbro.cobblemon.mcc.internal.compat.fabric.TowerOpponentCatalogResources
import jbro.cobblemon.mcc.internal.compat.fabric.BattlePointShopCatalogResources
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173AiTestBattleRuntime
import jbro.cobblemon.mcc.internal.tower.application.BattleTowerContentApplication
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayNetworking
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayEntryContext
import jbro.cobblemon.mcc.internal.pvp.network.PvpPlayNetworking
import jbro.cobblemon.mcc.internal.hub.BattleHubNetworking
import jbro.cobblemon.mcc.internal.shadow.ShadowTrainerProjectionNetworking
import jbro.cobblemon.mcc.internal.presentation.BattleArenaHologramNetworking
import jbro.cobblemon.mcc.internal.battle.ManagedBattleMechanicVisibilityNetworking
import jbro.cobblemon.mcc.internal.battle.ManagedBattleContentNetworking
import jbro.cobblemon.mcc.internal.bp.shop.ShopPlayNetworking

object MoreCobblemonContents : ModInitializer {
    const val MOD_ID: String = "more_cobblemon_contents"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    internal val CONTENTS: DefaultBattleContentApplicationService by lazy {
        DefaultBattleContentApplicationService(listOf(BattleTowerContentApplication(TowerPlayNetworking)))
    }

    override fun onInitialize() {
        jbro.cobblemon.mcc.api.access.BattleContentAccess.registerLifecycle()
        jbro.cobblemon.mcc.api.battle.ManagedPveBattles.registerLifecycle()
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
        // Register after feature handlers so their result settlement runs before the final entity backstop.
        ManagedBattleLifecycleEvents.registerServer()
        BattleContentCommands.register(
            CONTENTS,
            openScreen = BattleHubNetworking::open,
            aiTest = Cobblemon173AiTestBattleRuntime,
        )
        ManagedServerEphemeralStateCleanup.registerServer()
    }
}
