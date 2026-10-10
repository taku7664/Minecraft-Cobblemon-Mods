package jbro.cobblemon.mcc.internal.bp.shop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import jbro.cobblemon.mcc.internal.catalog.CatalogResourceInput

/** The external directory owns the whole shop. Defaults only seed a new directory. */
internal class BattlePointShopCatalogFiles(private val root: Path) {
    fun load(defaults: () -> BattlePointShopCatalogResourceBundle): BattlePointShopCatalogResourceBundle {
        if (!Files.exists(root)) initialize(defaults())
        return BattlePointShopCatalogResourceBundle(read("rules"), read("entries"))
    }

    private fun read(directory: String): List<CatalogResourceInput> {
        val path = root.resolve(directory)
        if (!Files.exists(path)) return emptyList()
        return Files.walk(path).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted().map { file ->
                    CatalogResourceInput(file.toString()) { Files.newBufferedReader(file) }
                }.toList()
        }
    }

    private fun initialize(defaults: BattlePointShopCatalogResourceBundle) {
        val parent = root.toAbsolutePath().parent
        Files.createDirectories(parent)
        val staging = Files.createTempDirectory(parent, ".bp-shop-initializing-")
        try {
            for (resource in defaults.rules + defaults.entries) {
                val relative = resource.resourceId.substringAfter("mcc-bp-shop/", "")
                require(relative.isNotEmpty()) { "Invalid shop resource: ${resource.resourceId}" }
                val target = staging.resolve(relative).normalize()
                require(target.startsWith(staging) && !Files.exists(target)) { "Conflicting shop resource: ${resource.resourceId}" }
                Files.createDirectories(target.parent)
                resource.openReader().use { reader -> Files.writeString(target, reader.readText()) }
            }
            Files.createDirectories(staging.resolve("entries"))
            Files.move(staging, root.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            // A failed export never publishes an incomplete authoritative directory.
            if (Files.exists(staging)) Files.walk(staging).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
