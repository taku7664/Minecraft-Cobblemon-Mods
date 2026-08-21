package jbro.cobblemon.morebattlecontent.internal.factory

import java.io.Reader
import jbro.cobblemon.morebattlecontent.internal.catalog.CatalogResourceInput

internal sealed interface FactoryCatalogReloadOutcome {
    data class Applied(val catalog: FactoryCatalog) : FactoryCatalogReloadOutcome
    data object MissingResource : FactoryCatalogReloadOutcome
    data class Rejected(val issues: List<FactoryCatalogIssue>) : FactoryCatalogReloadOutcome
    data class ReadFailed(val cause: Exception) : FactoryCatalogReloadOutcome
}

internal data class FactoryCatalogResourceBundle(
    val trainers: List<CatalogResourceInput>,
    val rentalSets: List<CatalogResourceInput>,
)

internal class FactoryCatalogResourceReloader(
    private val store: FactoryCatalogStore,
) {
    fun reload(bundle: FactoryCatalogResourceBundle): FactoryCatalogReloadOutcome {
        if (bundle.trainers.isEmpty() || bundle.rentalSets.isEmpty()) return FactoryCatalogReloadOutcome.MissingResource
        val readers = ArrayList<Reader>(bundle.trainers.size + bundle.rentalSets.size)
        return try {
            fun open(resources: List<CatalogResourceInput>): List<Pair<String, Reader>> = resources.map { resource ->
                val reader = resource.openReader()
                readers += reader
                resource.resourceId to reader
            }
            when (val result = store.reloadSeparated(open(bundle.trainers), open(bundle.rentalSets))) {
                is FactoryCatalogLoadResult.Loaded -> FactoryCatalogReloadOutcome.Applied(result.catalog)
                is FactoryCatalogLoadResult.Rejected -> FactoryCatalogReloadOutcome.Rejected(result.issues)
            }
        } catch (exception: Exception) {
            FactoryCatalogReloadOutcome.ReadFailed(exception)
        } finally {
            readers.forEach { reader -> runCatching(reader::close) }
        }
    }

    fun reload(resources: List<CatalogResourceInput>): FactoryCatalogReloadOutcome {
        if (resources.isEmpty()) return FactoryCatalogReloadOutcome.MissingResource
        val readers = ArrayList<Pair<String, Reader>>(resources.size)
        return try {
            resources.forEach { resource -> readers += resource.resourceId to resource.openReader() }
            when (val result = store.reloadFragments(readers)) {
                is FactoryCatalogLoadResult.Loaded -> FactoryCatalogReloadOutcome.Applied(result.catalog)
                is FactoryCatalogLoadResult.Rejected -> FactoryCatalogReloadOutcome.Rejected(result.issues)
            }
        } catch (exception: Exception) {
            FactoryCatalogReloadOutcome.ReadFailed(exception)
        } finally {
            readers.forEach { (_, reader) -> runCatching(reader::close) }
        }
    }

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
