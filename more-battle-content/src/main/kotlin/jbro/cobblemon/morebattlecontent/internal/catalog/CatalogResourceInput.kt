package jbro.cobblemon.morebattlecontent.internal.catalog

import java.io.Reader

internal data class CatalogResourceInput(
    val resourceId: String,
    val openReader: () -> Reader,
)
