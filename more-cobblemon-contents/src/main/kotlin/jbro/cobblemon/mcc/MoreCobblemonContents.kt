package jbro.cobblemon.mcc

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import jbro.cobblemon.mcc.internal.application.DefaultBattleContentApplicationService
import jbro.cobblemon.mcc.internal.command.BattleContentCommands
import jbro.cobblemon.mcc.internal.compat.fabric.HoloBattleTerminalContent
import jbro.cobblemon.mcc.internal.compat.fabric.ManagedServerEphemeralStateCleanup
import jbro.cobblemon.mcc.internal.compat.fabric.ManagedBattleLifecycleEvents
import jbro.cobblemon.mcc.internal.compat.fabric.BattlePointShopCatalogResources
import jbro.cobblemon.mcc.internal.hub.BattleHubNetworking
import jbro.cobblemon.mcc.internal.shadow.ShadowTrainerProjectionNetworking
import jbro.cobblemon.mcc.internal.presentation.BattleArenaHologramNetworking
import jbro.cobblemon.mcc.internal.battle.ManagedBattleMechanicVisibilityNetworking
import jbro.cobblemon.mcc.internal.battle.ManagedBattleContentNetworking
import jbro.cobblemon.mcc.internal.bp.shop.ShopPlayNetworking
import jbro.cobblemon.mcc.betterai.MoreCobblemonContentsBetterAi

object MoreCobblemonContents : ModInitializer {
    const val MOD_ID: String = "more_cobblemon_contents"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    val CONTENTS: DefaultBattleContentApplicationService by lazy {
        DefaultBattleContentApplicationService(emptyList())
    }

    override fun onInitialize() {
        jbro.cobblemon.mcc.api.access.BattleContentAccess.registerLifecycle()
        jbro.cobblemon.mcc.api.battle.ManagedPveBattles.registerLifecycle()
        HoloBattleTerminalContent.register { player, verification -> BattleHubNetworking.open(player, verification) }
        BattlePointShopCatalogResources.register()
        ShopPlayNetworking.registerServer()
        BattleHubNetworking.registerServer()
        ShadowTrainerProjectionNetworking.registerServer()
        BattleArenaHologramNetworking.registerServer()
        ManagedBattleMechanicVisibilityNetworking.registerServer()
        jbro.cobblemon.mcc.internal.battle.GimmickLockedBattles.register()
        ManagedBattleContentNetworking.registerServer()
        ManagedBattleLifecycleEvents.registerServer()
        BattleContentCommands.register(
            CONTENTS,
            openScreen = BattleHubNetworking::open,
        )
        ManagedServerEphemeralStateCleanup.registerServer()
        MoreCobblemonContentsBetterAi.initialize()
    }
}
