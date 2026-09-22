package jbro.cobblemon.morebattlecontent.internal.catalog

import java.io.Reader

internal data class CatalogResourceInput(
    val resourceId: String,
    val openReader: () -> Reader,
)

internal fun closeCatalogResourcesSafely(resources: Iterable<AutoCloseable>) {
    resources.forEach { resource ->
        try {
            resource.close()
        } catch (_: Exception) {
            // Closing one catalog input must not leave later inputs open.
        } catch (_: LinkageError) {
            // Resource implementations from optional integrations may drift independently.
        }
    }
}
