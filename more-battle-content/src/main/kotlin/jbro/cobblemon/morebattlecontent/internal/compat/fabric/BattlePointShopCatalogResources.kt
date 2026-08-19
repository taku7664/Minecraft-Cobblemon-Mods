package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopCatalogLoadResult
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopCatalogStore
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

internal object BattlePointShopCatalogResources {
    val catalogResourceId: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
        MoreBattleContent.MOD_ID,
        "bp_shop/catalog/mbc_core.json",
    )
    private val listenerId = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, "bp_shop_catalog")
    val store = BattlePointShopCatalogStore(::itemExists)

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): ResourceLocation = listenerId

                override fun onResourceManagerReload(resourceManager: ResourceManager) {
                    val resource = resourceManager.getResource(catalogResourceId).orElse(null)
                    if (resource == null) {
                        MoreBattleContent.LOGGER.error(
                            "BP shop catalog is missing: {}. Keeping the previous catalog.",
                            catalogResourceId,
                        )
                        return
                    }
                    val result = try {
                        resource.openAsReader().use(store::reload)
                    } catch (exception: RuntimeException) {
                        MoreBattleContent.LOGGER.error(
                            "Failed to read BP shop catalog {}. Keeping the previous catalog.",
                            catalogResourceId,
                            exception,
                        )
                        return
                    }
                    when (result) {
                        is BattlePointShopCatalogLoadResult.Loaded -> MoreBattleContent.LOGGER.info(
                            "Loaded BP shop catalog {} with {} entries from {}",
                            result.catalog.catalogId,
                            result.catalog.entries().size,
                            catalogResourceId,
                        )
                        is BattlePointShopCatalogLoadResult.Rejected -> result.issues.forEach { issue ->
                            MoreBattleContent.LOGGER.error(
                                "Rejected BP shop catalog {} at {}: {} ({})",
                                catalogResourceId,
                                issue.path,
                                issue.message,
                                issue.code,
                            )
                        }
                    }
                }
            },
        )
    }

    private fun itemExists(value: String): Boolean {
        val id = ResourceLocation.tryParse(value) ?: return false
        return BuiltInRegistries.ITEM.getOptional(id).isPresent
    }
}
