package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryCatalogReloadOutcome
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryCatalogResourceReloader
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryCatalogStore
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

internal object FactoryCatalogResources {
    const val catalogDirectory = "battle_factory/catalog"
    private val listenerId = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, "factory_catalog")
    val store = FactoryCatalogStore()
    private val reloader = FactoryCatalogResourceReloader(store)

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): ResourceLocation = listenerId

                override fun onResourceManagerReload(resourceManager: ResourceManager) {
                    val resources = resourceManager.listResources(catalogDirectory) { location ->
                        location.path.endsWith(".json")
                    }.entries.sortedBy { it.key.toString() }
                    val inputs = resources.map { (id, resource) ->
                        CatalogResourceInput(id.toString(), resource::openAsReader)
                    }
                    when (val outcome = reloader.reload(inputs)) {
                        is FactoryCatalogReloadOutcome.Applied -> MoreBattleContent.LOGGER.info(
                            "Loaded Battle Factory catalog {} from {} JSON files under {}",
                            outcome.catalog.catalogId,
                            resources.size,
                            catalogDirectory,
                        )
                        FactoryCatalogReloadOutcome.MissingResource -> MoreBattleContent.LOGGER.error(
                            "Battle Factory catalog directory has no JSON files: {}. Keeping the previous catalog.",
                            catalogDirectory,
                        )
                        is FactoryCatalogReloadOutcome.Rejected -> outcome.issues.forEach { issue ->
                            MoreBattleContent.LOGGER.error(
                                "Rejected Battle Factory catalogs under {} at {}: {} ({})",
                                catalogDirectory,
                                issue.path,
                                issue.message,
                                issue.code,
                            )
                        }
                        is FactoryCatalogReloadOutcome.ReadFailed -> MoreBattleContent.LOGGER.error(
                            "Failed to read Battle Factory catalogs under {}. Keeping the previous catalog.",
                            catalogDirectory,
                            outcome.cause,
                        )
                    }
                }
            },
        )
    }
}
