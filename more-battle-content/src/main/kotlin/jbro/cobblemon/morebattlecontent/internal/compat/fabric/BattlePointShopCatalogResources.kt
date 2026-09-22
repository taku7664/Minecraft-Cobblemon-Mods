package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopCatalogReloadOutcome
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopCatalogResourceBundle
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopCatalogResourceReloader
import jbro.cobblemon.morebattlecontent.internal.bp.shop.BattlePointShopCatalogStore
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput
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
    private val reloader = BattlePointShopCatalogResourceReloader(store)

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): ResourceLocation = listenerId

                override fun onResourceManagerReload(resourceManager: ResourceManager) {
                    val report = try {
                        reload(resourceManager)
                    } catch (failure: RuntimeException) {
                        reportReloadFailureSafely(failure)
                        return
                    } catch (failure: LinkageError) {
                        reportReloadFailureSafely(failure)
                        return
                    }
                    reportOutcomeSafely(report)
                }
            },
        )
    }

    private fun reload(resourceManager: ResourceManager): ReloadReport {
        fun resources(directory: String): List<CatalogResourceInput> =
            resourceManager.listResources(directory) { location -> location.path.endsWith(".json") }
                .entries
                .sortedBy { it.key.toString() }
                .map { (id, resource) -> CatalogResourceInput(id.toString(), resource::openAsReader) }
        val rules = resources(ruleDirectory)
        val entries = resources(entryDirectory)
        return ReloadReport(
            reloader.reload(BattlePointShopCatalogResourceBundle(rules, entries)),
            rules.size,
            entries.size,
        )
    }

    private fun reportOutcomeSafely(report: ReloadReport) {
        try {
            when (val outcome = report.outcome) {
                BattlePointShopCatalogReloadOutcome.MissingResource -> MoreBattleContent.LOGGER.error(
                    "BP shop rules or entries are missing under {} and {}. Keeping the previous catalog.",
                    ruleDirectory,
                    entryDirectory,
                )
                is BattlePointShopCatalogReloadOutcome.ReadFailed -> reportReloadFailureSafely(outcome.cause)
                is BattlePointShopCatalogReloadOutcome.Applied -> MoreBattleContent.LOGGER.info(
                    "Loaded BP shop catalog {} with {} entries from {} rules and {} entry JSON files",
                    outcome.catalog.catalogId,
                    outcome.catalog.entries().size,
                    report.ruleCount,
                    report.entryCount,
                )
                is BattlePointShopCatalogReloadOutcome.Rejected -> outcome.issues.forEach { issue ->
                    MoreBattleContent.LOGGER.error(
                        "Rejected BP shop catalogs at {}: {} ({})",
                        issue.path,
                        issue.message,
                        issue.code,
                    )
                }
            }
        } catch (_: RuntimeException) {
            // Reporting cannot change or roll back the already-decided catalog outcome.
        } catch (_: LinkageError) {
            // Compatibility logging is best-effort during resource reload.
        }
    }

    private fun reportReloadFailureSafely(failure: Throwable) {
        try {
            MoreBattleContent.LOGGER.error(
                "Failed to read BP shop rules or entries. Keeping the previous catalog.",
                failure,
            )
        } catch (_: RuntimeException) {
            // Logging cannot replace the previous valid catalog.
        } catch (_: LinkageError) {
            // Compatibility logging is best-effort during resource reload.
        }
    }

    private fun itemExists(value: String): Boolean {
        val id = ResourceLocation.tryParse(value) ?: return false
        return BuiltInRegistries.ITEM.getOptional(id).isPresent
    }

    private data class ReloadReport(
        val outcome: BattlePointShopCatalogReloadOutcome,
        val ruleCount: Int,
        val entryCount: Int,
    )
}
