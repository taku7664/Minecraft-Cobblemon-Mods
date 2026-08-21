package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentCatalogReloadOutcome
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentCatalogResourceReloader
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentCatalogStore
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

internal object TowerOpponentCatalogResources {
    const val catalogDirectory = "battle_tower/opponents"
    val listenerId: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
        MoreBattleContent.MOD_ID,
        "tower_opponent_catalog",
    )
    val store = TowerOpponentCatalogStore()

    private val listener = FabricTowerOpponentCatalogReloadListener(
        listenerId,
        catalogDirectory,
        TowerOpponentCatalogResourceReloader(store),
    )

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(listener)
    }
}

private class FabricTowerOpponentCatalogReloadListener(
    private val id: ResourceLocation,
    private val catalogDirectory: String,
    private val reloader: TowerOpponentCatalogResourceReloader,
) : SimpleSynchronousResourceReloadListener {
    override fun getFabricId(): ResourceLocation = id

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        val resources = resourceManager.listResources(catalogDirectory) { location ->
            location.path.endsWith(".json")
        }.entries.sortedBy { it.key.toString() }
        val inputs = resources.map { (resourceId, resource) ->
            CatalogResourceInput(resourceId.toString(), resource::openAsReader)
        }
        when (val outcome = reloader.reload(inputs)) {
            is TowerOpponentCatalogReloadOutcome.Applied ->
                MoreBattleContent.LOGGER.info(
                    "Loaded Battle Tower opponent catalog {} from {} JSON files under {}",
                    outcome.catalog.catalogId,
                    resources.size,
                    catalogDirectory,
                )

            TowerOpponentCatalogReloadOutcome.MissingResource ->
                MoreBattleContent.LOGGER.error(
                    "Battle Tower opponent directory has no JSON files: {}. Keeping the previous catalog.",
                    catalogDirectory,
                )

            is TowerOpponentCatalogReloadOutcome.Rejected -> outcome.issues.forEach { issue ->
                MoreBattleContent.LOGGER.error(
                    "Rejected Battle Tower opponent catalogs under {} at {}: {} ({})",
                    catalogDirectory,
                    issue.path,
                    issue.message,
                    issue.code,
                )
            }

            is TowerOpponentCatalogReloadOutcome.ReadFailed ->
                MoreBattleContent.LOGGER.error(
                    "Failed to read Battle Tower opponent catalogs under {}. Keeping the previous catalog.",
                    catalogDirectory,
                    outcome.cause,
                )
        }
    }
}
