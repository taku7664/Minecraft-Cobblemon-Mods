package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FactoryCatalogResourcesTest {
    @Test
    fun `uses an independent factory server data directory`() {
        assertEquals("battle_factory/catalog", FactoryCatalogResources.catalogDirectory)
    }
}
