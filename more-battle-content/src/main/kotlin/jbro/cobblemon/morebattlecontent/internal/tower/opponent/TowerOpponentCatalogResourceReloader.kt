package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import java.io.Reader
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput

internal sealed interface TowerOpponentCatalogReloadOutcome {
    data class Applied(val catalog: TowerOpponentCatalog) : TowerOpponentCatalogReloadOutcome
    data class Rejected(val issues: List<TowerOpponentCatalogIssue>) : TowerOpponentCatalogReloadOutcome
    data object MissingResource : TowerOpponentCatalogReloadOutcome
    data class ReadFailed(val cause: Exception) : TowerOpponentCatalogReloadOutcome
}

internal class TowerOpponentCatalogResourceReloader(
    private val store: TowerOpponentCatalogStore,
) {
    fun reload(resources: List<CatalogResourceInput>): TowerOpponentCatalogReloadOutcome {
        if (resources.isEmpty()) return TowerOpponentCatalogReloadOutcome.MissingResource
        val readers = ArrayList<Pair<String, Reader>>(resources.size)
        return try {
            resources.forEach { resource -> readers += resource.resourceId to resource.openReader() }
            when (val result = store.reloadFragments(readers)) {
                is TowerOpponentCatalogLoadResult.Loaded -> TowerOpponentCatalogReloadOutcome.Applied(result.catalog)
                is TowerOpponentCatalogLoadResult.Rejected -> TowerOpponentCatalogReloadOutcome.Rejected(result.issues)
            }
        } catch (error: Exception) {
            TowerOpponentCatalogReloadOutcome.ReadFailed(error)
        } finally {
            readers.forEach { (_, reader) -> runCatching(reader::close) }
        }
    }

    fun reload(openReader: () -> Reader?): TowerOpponentCatalogReloadOutcome {
        val reader = try {
            openReader()
        } catch (error: Exception) {
            return TowerOpponentCatalogReloadOutcome.ReadFailed(error)
        } ?: return TowerOpponentCatalogReloadOutcome.MissingResource

        val result = try {
            reader.use(store::reload)
        } catch (error: Exception) {
            return TowerOpponentCatalogReloadOutcome.ReadFailed(error)
        }
        return when (result) {
            is TowerOpponentCatalogLoadResult.Loaded -> TowerOpponentCatalogReloadOutcome.Applied(result.catalog)
            is TowerOpponentCatalogLoadResult.Rejected -> TowerOpponentCatalogReloadOutcome.Rejected(result.issues)
        }
    }
}
