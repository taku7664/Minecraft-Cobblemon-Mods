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
    const val ruleDirectory = "mbc-bp-shop/rules"
    const val entryDirectory = "mbc-bp-shop/entries"
    private val listenerId = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, "bp_shop_catalog")
    val store = BattlePointShopCatalogStore(::itemExists)

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): ResourceLocation = listenerId

                override fun onResourceManagerReload(resourceManager: ResourceManager) {
                    fun resources(directory: String) = resourceManager.listResources(directory) { location ->
                        location.path.endsWith(".json")
                    }.entries.sortedBy { it.key.toString() }
                    val rules = resources(ruleDirectory)
                    val entries = resources(entryDirectory)
                    if (rules.isEmpty() || entries.isEmpty()) {
                        MoreBattleContent.LOGGER.error(
                            "BP shop rules or entries are missing under {} and {}. Keeping the previous catalog.",
                            ruleDirectory,
                            entryDirectory,
                        )
                        return
                    }
                    val readers = ArrayList<java.io.Reader>(rules.size + entries.size)
                    val result = try {
                        fun open(resources: List<Map.Entry<ResourceLocation, net.minecraft.server.packs.resources.Resource>>) =
                            resources.map { (id, resource) ->
                                val reader = resource.openAsReader()
                                readers += reader
                                id.toString() to reader
                            }
                        store.reloadSeparated(open(rules), open(entries))
                    } catch (exception: RuntimeException) {
                        MoreBattleContent.LOGGER.error(
                            "Failed to read BP shop rules or entries. Keeping the previous catalog.",
                            exception,
                        )
                        return
                    } finally {
                        readers.forEach { reader -> runCatching(reader::close) }
                    }
                    when (result) {
                        is BattlePointShopCatalogLoadResult.Loaded -> MoreBattleContent.LOGGER.info(
                            "Loaded BP shop catalog {} with {} entries from {} rules and {} entry JSON files",
                            result.catalog.catalogId,
                            result.catalog.entries().size,
                            rules.size,
                            entries.size,
                        )
                        is BattlePointShopCatalogLoadResult.Rejected -> result.issues.forEach { issue ->
                            MoreBattleContent.LOGGER.error(
                                "Rejected BP shop catalogs at {}: {} ({})",
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
