package jbro.cobblemon.mcc.internal.compat.fabric

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopCatalogFile
import jbro.cobblemon.mcc.internal.bp.shop.BattlePointShopCatalogReloadOutcome
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
    const val catalogPath = "mcc-bp-shop.json"
    private val listenerId = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, "bp_shop_catalog")
    val store = BattlePointShopCatalogStore(::itemExists)
    private val reloader = BattlePointShopCatalogResourceReloader(store)
    private val files by lazy {
        BattlePointShopCatalogFile(FabricLoader.getInstance().configDir.resolve("more-cobblemon-contents/bp-shop.json"))
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
        val resource = files.load {
            val id = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, catalogPath)
            val defaults = resourceManager.getResource(id).orElseThrow { IllegalStateException("Missing default BP shop: $id") }
            CatalogResourceInput(id.toString(), defaults::openAsReader)
        }
        return ReloadReport(
            reloader.reload(resource),
        )
    }

    private fun reportOutcomeSafely(report: ReloadReport) {
        try {
            when (val outcome = report.outcome) {
                BattlePointShopCatalogReloadOutcome.MissingResource -> MoreCobblemonContents.LOGGER.error(
                    "BP shop file is missing at config/more-cobblemon-contents/bp-shop.json. Keeping the previous catalog.",
                )
                is BattlePointShopCatalogReloadOutcome.ReadFailed -> reportReloadFailureSafely(outcome.cause)
                is BattlePointShopCatalogReloadOutcome.Applied -> MoreCobblemonContents.LOGGER.info(
                    "Loaded BP shop catalog {} with {} entries from config/more-cobblemon-contents/bp-shop.json",
                    outcome.catalog.catalogId,
                    outcome.catalog.entries().size,
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
    )
}
