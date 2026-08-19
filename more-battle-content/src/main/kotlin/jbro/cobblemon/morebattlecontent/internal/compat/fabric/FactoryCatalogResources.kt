package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryCatalogReloadOutcome
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryCatalogResourceReloader
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryCatalogStore
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

internal object FactoryCatalogResources {
    val catalogResourceId: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
        MoreBattleContent.MOD_ID,
        "battle_factory/catalog/mbc_core.json",
    )
    private val listenerId = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, "factory_catalog")
    val store = FactoryCatalogStore()
    private val reloader = FactoryCatalogResourceReloader(store)

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): ResourceLocation = listenerId

                override fun onResourceManagerReload(resourceManager: ResourceManager) {
                    when (val outcome = reloader.reload {
                        resourceManager.getResource(catalogResourceId).orElse(null)?.openAsReader()
                    }) {
                        is FactoryCatalogReloadOutcome.Applied -> MoreBattleContent.LOGGER.info(
                            "Loaded Battle Factory catalog {} from {}",
                            outcome.catalog.catalogId,
                            catalogResourceId,
                        )
                        FactoryCatalogReloadOutcome.MissingResource -> MoreBattleContent.LOGGER.error(
                            "Battle Factory catalog is missing: {}. Keeping the previous catalog.",
                            catalogResourceId,
                        )
                        is FactoryCatalogReloadOutcome.Rejected -> outcome.issues.forEach { issue ->
                            MoreBattleContent.LOGGER.error(
                                "Rejected Battle Factory catalog {} at {}: {} ({})",
                                catalogResourceId,
                                issue.path,
                                issue.message,
                                issue.code,
                            )
                        }
                        is FactoryCatalogReloadOutcome.ReadFailed -> MoreBattleContent.LOGGER.error(
                            "Failed to read Battle Factory catalog {}. Keeping the previous catalog.",
                            catalogResourceId,
                            outcome.cause,
                        )
                    }
                }
            },
        )
    }
}
