package jbro.cobblemon.mcc.internal.bp.shop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import jbro.cobblemon.mcc.internal.catalog.CatalogResourceInput

/** A single editable server file owns the entire shop, including an empty entries list. */
internal class BattlePointShopCatalogFile(private val path: Path) {
    fun load(defaults: () -> CatalogResourceInput): CatalogResourceInput {
        if (!Files.exists(path)) {
            val parent = path.toAbsolutePath().parent
            Files.createDirectories(parent)
            val staging = Files.createTempFile(parent, ".bp-shop-", ".tmp")
            try {
                defaults().openReader().use { reader -> Files.writeString(staging, reader.readText()) }
                Files.move(staging, path.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(staging)
            }
        }
        return CatalogResourceInput(path.toString()) { Files.newBufferedReader(path) }
    }
}
