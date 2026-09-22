package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.io.Reader
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput
import jbro.cobblemon.morebattlecontent.internal.catalog.closeCatalogResourcesSafely

internal sealed interface BattlePointShopCatalogReloadOutcome {
    data class Applied(val catalog: BattlePointShopCatalog) : BattlePointShopCatalogReloadOutcome
    data object MissingResource : BattlePointShopCatalogReloadOutcome
    data class Rejected(val issues: List<BattlePointShopCatalogIssue>) : BattlePointShopCatalogReloadOutcome
    data class ReadFailed(val cause: Throwable) : BattlePointShopCatalogReloadOutcome
}

internal data class BattlePointShopCatalogResourceBundle(
    val rules: List<CatalogResourceInput>,
    val entries: List<CatalogResourceInput>,
)

internal class BattlePointShopCatalogResourceReloader(
    private val store: BattlePointShopCatalogStore,
) {
    fun reload(bundle: BattlePointShopCatalogResourceBundle): BattlePointShopCatalogReloadOutcome {
        if (bundle.rules.isEmpty() || bundle.entries.isEmpty()) {
            return BattlePointShopCatalogReloadOutcome.MissingResource
        }
        val readers = ArrayList<Reader>(bundle.rules.size + bundle.entries.size)
        return try {
            fun open(resources: List<CatalogResourceInput>): List<Pair<String, Reader>> = resources.map { resource ->
                val reader = resource.openReader()
                readers += reader
                resource.resourceId to reader
            }
            when (val result = store.reloadSeparated(open(bundle.rules), open(bundle.entries))) {
                is BattlePointShopCatalogLoadResult.Loaded -> BattlePointShopCatalogReloadOutcome.Applied(result.catalog)
                is BattlePointShopCatalogLoadResult.Rejected -> BattlePointShopCatalogReloadOutcome.Rejected(result.issues)
            }
        } catch (failure: Exception) {
            BattlePointShopCatalogReloadOutcome.ReadFailed(failure)
        } catch (failure: LinkageError) {
            BattlePointShopCatalogReloadOutcome.ReadFailed(failure)
        } finally {
            closeCatalogResourcesSafely(readers)
        }
    }
}
