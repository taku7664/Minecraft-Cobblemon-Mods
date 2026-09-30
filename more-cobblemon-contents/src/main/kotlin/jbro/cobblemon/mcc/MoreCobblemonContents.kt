package jbro.cobblemon.mcc

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import jbro.cobblemon.mcc.internal.application.DefaultBattleContentApplicationService
import jbro.cobblemon.mcc.internal.command.BattleContentCommands
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.terminal.HoloTerminalPalette
import jbro.cobblemon.mcc.api.terminal.HoloTerminals
import jbro.cobblemon.mcc.internal.compat.fabric.HoloBattleTerminalIds
import jbro.cobblemon.mcc.internal.compat.fabric.HoloTerminalInteractions
import jbro.cobblemon.mcc.internal.compat.fabric.ManagedServerEphemeralStateCleanup
import jbro.cobblemon.mcc.internal.compat.fabric.ManagedBattleLifecycleEvents
import jbro.cobblemon.mcc.internal.compat.fabric.BattlePointShopCatalogResources
import jbro.cobblemon.mcc.internal.hub.BattleHubIds
import jbro.cobblemon.mcc.internal.hub.BattleHubNetworking
import jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile
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
        // The general battle terminal reaches every content; each content mod adds a terminal of its own.
        HoloTerminals.register(HoloBattleTerminalIds.id, listOf(BattleHubIds.DASHBOARD, BattleHubIds.SHOP,
            ManagedBattleContentIds.PVP, ManagedBattleContentIds.LEAGUE_CHALLENGE, ManagedBattleContentIds.BATTLE_TOWER,
            ManagedBattleContentIds.BATTLE_FACTORY), HoloTerminalPalette.MCC)
        HoloTerminalInteractions.install(BattleHubNetworking::openTerminal)
        BattleHubTabConfigFile.register()
        BattlePointShopCatalogResources.register()
        ShopPlayNetworking.registerServer()
        BattleHubNetworking.registerServer()
        ShadowTrainerProjectionNetworking.registerServer()
        BattleArenaHologramNetworking.registerServer()
        ManagedBattleMechanicVisibilityNetworking.registerServer()
        jbro.cobblemon.mcc.internal.battle.GimmickLockedBattles.register()
        ManagedBattleContentNetworking.registerServer()
        ManagedBattleLifecycleEvents.registerServer()
        jbro.cobblemon.mcc.internal.command.MccAdminCommands.register()
        jbro.cobblemon.mcc.internal.wiki.WikiServer.register()
        jbro.cobblemon.mcc.internal.wiki.WikiCommands.register()
        jbro.cobblemon.mcc.internal.command.MccAdminSources.register(jbro.cobblemon.mcc.api.battle.ManagedPveBattles.adminSource)
        BattleContentCommands.register(
            CONTENTS,
            openScreen = BattleHubNetworking::openCommand,
        )
        ManagedServerEphemeralStateCleanup.registerServer()
        MoreCobblemonContentsBetterAi.initialize()
    }
}
