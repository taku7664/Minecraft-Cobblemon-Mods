package jbro.cobblemon.mcc.internal.compat.fabric

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.catalog.CatalogResourceInput
import jbro.cobblemon.mcc.internal.catalog.runCatalogReloadSafely
import jbro.cobblemon.mcc.internal.factory.FactoryCatalogReloadOutcome
import jbro.cobblemon.mcc.internal.factory.FactoryCatalogResourceBundle
import jbro.cobblemon.mcc.internal.factory.FactoryCatalogResourceReloader
import jbro.cobblemon.mcc.internal.factory.FactoryCatalogStore
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

internal object FactoryCatalogResources {
    const val trainerDirectory = "mcc-battle-factory/trainers"
    const val rentalSetDirectory = "mcc-battle-factory/rental-sets"
    private val listenerId = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "factory_catalog")
    val store = FactoryCatalogStore()
    private val reloader = FactoryCatalogResourceReloader(store)

    fun register() {
        ManagedServerEphemeralStateCleanup.register(store::clear)
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): ResourceLocation = listenerId

                override fun onResourceManagerReload(resourceManager: ResourceManager) {
                    runCatalogReloadSafely(
                        reload = { reload(resourceManager) },
                        reportFailure = { failure ->
                            MoreCobblemonContents.LOGGER.error(
                                "Battle Factory catalog reload handling failed. The last successfully applied catalog remains usable.",
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
                        resources.map { (id, resource) -> CatalogResourceInput(id.toString(), resource::openAsReader) }
                    val trainers = resources(trainerDirectory)
                    val rentalSets = resources(rentalSetDirectory)
                    when (val outcome = reloader.reload(FactoryCatalogResourceBundle(inputs(trainers), inputs(rentalSets)))) {
                        is FactoryCatalogReloadOutcome.Applied -> MoreCobblemonContents.LOGGER.info(
                            "Loaded Battle Factory catalog {} from {} trainer and {} rental-set JSON files",
                            outcome.catalog.catalogId,
                            trainers.size,
                            rentalSets.size,
                        )
                        FactoryCatalogReloadOutcome.MissingResource -> MoreCobblemonContents.LOGGER.error(
                            "Battle Factory trainers or rental sets are missing under {} and {}. Keeping the previous catalog.",
                            trainerDirectory,
                            rentalSetDirectory,
                        )
                        is FactoryCatalogReloadOutcome.Rejected -> outcome.issues.forEach { issue ->
                            MoreCobblemonContents.LOGGER.error(
                                "Rejected Battle Factory catalogs at {}: {} ({})",
                                issue.path,
                                issue.message,
                                issue.code,
                            )
                        }
                        is FactoryCatalogReloadOutcome.ReadFailed -> MoreCobblemonContents.LOGGER.error(
                            "Failed to read Battle Factory catalogs. Keeping the previous catalog.",
                            outcome.cause,
                        )
                    }
                }
            },
        )
    }
}
