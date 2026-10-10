package jbro.cobblemon.mcc.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BattlePointShopCatalogResourcesTest {
    @Test
    fun `uses a single BP shop catalog`() {
        assertEquals("mcc-bp-shop.json", BattlePointShopCatalogResources.catalogPath)
    }
}
