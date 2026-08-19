package jbro.cobblemon.morebattlecontent.internal.factory

import java.io.Reader

internal sealed interface FactoryCatalogReloadOutcome {
    data class Applied(val catalog: FactoryCatalog) : FactoryCatalogReloadOutcome
    data object MissingResource : FactoryCatalogReloadOutcome
    data class Rejected(val issues: List<FactoryCatalogIssue>) : FactoryCatalogReloadOutcome
    data class ReadFailed(val cause: Exception) : FactoryCatalogReloadOutcome
}

internal class FactoryCatalogResourceReloader(
    private val store: FactoryCatalogStore,
) {
    fun reload(openReader: () -> Reader?): FactoryCatalogReloadOutcome {
        val reader = try {
            openReader()
        } catch (exception: Exception) {
            return FactoryCatalogReloadOutcome.ReadFailed(exception)
        } ?: return FactoryCatalogReloadOutcome.MissingResource

        return try {
            reader.use { source ->
                when (val result = store.reload(source)) {
                    is FactoryCatalogLoadResult.Loaded -> FactoryCatalogReloadOutcome.Applied(result.catalog)
                    is FactoryCatalogLoadResult.Rejected -> FactoryCatalogReloadOutcome.Rejected(result.issues)
                }
            }
        } catch (exception: Exception) {
            FactoryCatalogReloadOutcome.ReadFailed(exception)
        }
    }
}
