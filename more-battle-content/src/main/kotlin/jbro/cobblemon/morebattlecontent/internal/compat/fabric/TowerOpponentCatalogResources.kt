package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentCatalogReloadOutcome
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentCatalogResourceReloader
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentCatalogStore
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

internal object TowerOpponentCatalogResources {
    val catalogResourceId: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
        MoreBattleContent.MOD_ID,
        "battle_tower/opponents/mbc_core.json",
    )
    val listenerId: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
        MoreBattleContent.MOD_ID,
        "tower_opponent_catalog",
    )
    val store = TowerOpponentCatalogStore()

    private val listener = FabricTowerOpponentCatalogReloadListener(
        listenerId,
        catalogResourceId,
        TowerOpponentCatalogResourceReloader(store),
    )

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(listener)
    }
}

private class FabricTowerOpponentCatalogReloadListener(
    private val id: ResourceLocation,
    private val resourceId: ResourceLocation,
    private val reloader: TowerOpponentCatalogResourceReloader,
) : SimpleSynchronousResourceReloadListener {
    override fun getFabricId(): ResourceLocation = id

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        when (val outcome = reloader.reload {
            resourceManager.getResource(resourceId).orElse(null)?.openAsReader()
        }) {
            is TowerOpponentCatalogReloadOutcome.Applied ->
                MoreBattleContent.LOGGER.info(
                    "Loaded Battle Tower opponent catalog {} from {}",
                    outcome.catalog.catalogId,
                    resourceId,
                )

            TowerOpponentCatalogReloadOutcome.MissingResource ->
                MoreBattleContent.LOGGER.error(
                    "Battle Tower opponent catalog resource is missing: {}. Keeping the previous catalog.",
                    resourceId,
                )

            is TowerOpponentCatalogReloadOutcome.Rejected -> outcome.issues.forEach { issue ->
                MoreBattleContent.LOGGER.error(
                    "Rejected Battle Tower opponent catalog {} at {}: {} ({})",
                    resourceId,
                    issue.path,
                    issue.message,
                    issue.code,
                )
            }

            is TowerOpponentCatalogReloadOutcome.ReadFailed ->
                MoreBattleContent.LOGGER.error(
                    "Failed to read Battle Tower opponent catalog {}. Keeping the previous catalog.",
                    resourceId,
                    outcome.cause,
                )
        }
    }
}
