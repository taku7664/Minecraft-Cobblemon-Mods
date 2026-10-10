package jbro.cobblemon.mcc.internal.compat.fabric

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopCatalogFiles
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopCatalogReloadOutcome
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopCatalogResourceBundle
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopCatalogResourceReloader
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopCatalogStore
import jbro.cobblemon.mcc.internal.catalog.CatalogResourceInput
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager

internal object BattlePointShopCatalogResources {
    const val ruleDirectory = "mcc-bp-shop/rules"
    const val entryDirectory = "mcc-bp-shop/entries"
    private val listenerId = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "bp_shop_catalog")
    val store = BattlePointShopCatalogStore(::itemExists)
    private val reloader = BattlePointShopCatalogResourceReloader(store)
    private val files by lazy {
        BattlePointShopCatalogFiles(FabricLoader.getInstance().configDir.resolve("more-cobblemon-contents/bp-shop"))
    }

    fun register() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): ResourceLocation = listenerId

                override fun onResourceManagerReload(resourceManager: ResourceManager) {
                    val report = try {
                        reload(resourceManager)
                    } catch (failure: Exception) {
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
        val bundle = files.load { BattlePointShopCatalogResourceBundle(resources(ruleDirectory), resources(entryDirectory)) }
        val rules = bundle.rules
        val entries = bundle.entries
        return ReloadReport(
            reloader.reload(BattlePointShopCatalogResourceBundle(rules, entries)),
            rules.size,
            entries.size,
        )
    }

    private fun reportOutcomeSafely(report: ReloadReport) {
        try {
            when (val outcome = report.outcome) {
                BattlePointShopCatalogReloadOutcome.MissingResource -> MoreCobblemonContents.LOGGER.error(
                    "BP shop rules are missing under config/more-cobblemon-contents/bp-shop/rules. Keeping the previous catalog.",
                )
                is BattlePointShopCatalogReloadOutcome.ReadFailed -> reportReloadFailureSafely(outcome.cause)
                is BattlePointShopCatalogReloadOutcome.Applied -> MoreCobblemonContents.LOGGER.info(
                    "Loaded BP shop catalog {} with {} entries from {} rules and {} entry JSON files",
                    outcome.catalog.catalogId,
                    outcome.catalog.entries().size,
                    report.ruleCount,
                    report.entryCount,
                )
                is BattlePointShopCatalogReloadOutcome.Rejected -> outcome.issues.forEach { issue ->
                    MoreCobblemonContents.LOGGER.error(
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
            MoreCobblemonContents.LOGGER.error(
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
