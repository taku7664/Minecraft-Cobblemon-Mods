package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import java.io.Reader

internal sealed interface TowerOpponentCatalogReloadOutcome {
    data class Applied(val catalog: TowerOpponentCatalog) : TowerOpponentCatalogReloadOutcome
    data class Rejected(val issues: List<TowerOpponentCatalogIssue>) : TowerOpponentCatalogReloadOutcome
    data object MissingResource : TowerOpponentCatalogReloadOutcome
    data class ReadFailed(val cause: Exception) : TowerOpponentCatalogReloadOutcome
}

internal class TowerOpponentCatalogResourceReloader(
    private val store: TowerOpponentCatalogStore,
) {
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
