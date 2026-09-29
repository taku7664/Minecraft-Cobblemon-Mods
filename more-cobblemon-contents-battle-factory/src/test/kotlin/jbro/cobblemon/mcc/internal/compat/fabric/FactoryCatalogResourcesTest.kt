package jbro.cobblemon.mcc.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FactoryCatalogResourcesTest {
    @Test
    fun `uses an independent factory server data directory`() {
        assertEquals("mcc-battle-factory/trainers", FactoryCatalogResources.trainerDirectory)
        assertEquals("mcc-battle-factory/rental-sets", FactoryCatalogResources.rentalSetDirectory)
    }
}
