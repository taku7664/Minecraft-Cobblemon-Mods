package jbro.cobblemon.mcc.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BattlePointShopCatalogResourcesTest {
    @Test
    fun `uses prefixed independent BP shop directories`() {
        assertEquals("mcc-bp-shop/rules", BattlePointShopCatalogResources.ruleDirectory)
        assertEquals("mcc-bp-shop/entries", BattlePointShopCatalogResources.entryDirectory)
    }
}
