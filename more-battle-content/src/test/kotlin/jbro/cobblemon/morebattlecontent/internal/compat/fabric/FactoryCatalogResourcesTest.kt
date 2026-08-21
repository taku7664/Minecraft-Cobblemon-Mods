package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FactoryCatalogResourcesTest {
    @Test
    fun `uses an independent factory server data directory`() {
        assertEquals("mbc-battle-factory/trainers", FactoryCatalogResources.trainerDirectory)
        assertEquals("mbc-battle-factory/rental-sets", FactoryCatalogResources.rentalSetDirectory)
    }
}
