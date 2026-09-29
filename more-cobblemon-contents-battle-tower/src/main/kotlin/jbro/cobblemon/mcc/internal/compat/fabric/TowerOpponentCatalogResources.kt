package jbro.cobblemon.mcc.internal.compat.fabric

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.catalog.CatalogResourceInput
import jbro.cobblemon.mcc.internal.catalog.runCatalogReloadSafely
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentCatalogReloadOutcome
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentCatalogResourceBundle
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentCatalogResourceReloader
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentCatalogStore
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

internal object TowerOpponentCatalogResources {
    const val trainerDirectory = "mcc-battle-tower/trainers"
    const val poolDirectory = "mcc-battle-tower/pools"
    const val encounterDirectory = "mcc-battle-tower/encounters"
    const val pokemonSetDirectory = "mcc-battle-tower/pokemon-sets"
    val listenerId: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
        MoreCobblemonContents.MOD_ID,
        "tower_opponent_catalog",
    )
    val store = TowerOpponentCatalogStore()

    private val listener = FabricTowerOpponentCatalogReloadListener(
        listenerId,
        TowerOpponentCatalogResourceReloader(store),
    )

    fun register() {
        ManagedServerEphemeralStateCleanup.register(store::clear)
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(listener)
    }
}

private class FabricTowerOpponentCatalogReloadListener(
    private val id: ResourceLocation,
    private val reloader: TowerOpponentCatalogResourceReloader,
) : SimpleSynchronousResourceReloadListener {
    override fun getFabricId(): ResourceLocation = id

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        runCatalogReloadSafely(
            reload = { reload(resourceManager) },
            reportFailure = { failure ->
                MoreCobblemonContents.LOGGER.error(
                    "Battle Tower catalog reload handling failed. The last successfully applied catalog remains usable.",
                    failure,
                )
            },
        )
    }

    private fun reload(resourceManager: ResourceManager) {
        fun resources(directory: String) = resourceManager.listResources(directory) { location ->
            location.path.endsWith(".json")
        }.entries.sortedBy { it.key.toString() }
        fun inputs(resources: List<Map.Entry<ResourceLocation, net.minecraft.server.packs.resources.Resource>>) =
            resources.map { (resourceId, resource) -> CatalogResourceInput(resourceId.toString(), resource::openAsReader) }
        val trainers = resources(TowerOpponentCatalogResources.trainerDirectory)
        val pools = resources(TowerOpponentCatalogResources.poolDirectory)
        val encounters = resources(TowerOpponentCatalogResources.encounterDirectory)
        val pokemonSets = resources(TowerOpponentCatalogResources.pokemonSetDirectory)
        when (
            val outcome = reloader.reload(
                TowerOpponentCatalogResourceBundle(inputs(trainers), inputs(pools), inputs(encounters), inputs(pokemonSets)),
            )
        ) {
            is TowerOpponentCatalogReloadOutcome.Applied ->
                MoreCobblemonContents.LOGGER.info(
                    "Loaded Battle Tower catalog {} from {}/{}/{}/{} trainer/pool/encounter/set JSON files",
                    outcome.catalog.catalogId,
                    trainers.size,
                    pools.size,
                    encounters.size,
                    pokemonSets.size,
                )

            TowerOpponentCatalogReloadOutcome.MissingResource ->
                MoreCobblemonContents.LOGGER.error(
                    "Battle Tower trainers, pools, encounters, or Pokemon sets are missing. Keeping the previous catalog.",
                )

            is TowerOpponentCatalogReloadOutcome.Rejected -> outcome.issues.forEach { issue ->
                MoreCobblemonContents.LOGGER.error(
                    "Rejected Battle Tower catalogs at {}: {} ({})",
                    issue.path,
                    issue.message,
                    issue.code,
                )
            }

            is TowerOpponentCatalogReloadOutcome.ReadFailed ->
                MoreCobblemonContents.LOGGER.error(
                    "Failed to read Battle Tower catalogs. Keeping the previous catalog.",
                    outcome.cause,
                )
        }
    }
}
