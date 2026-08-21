package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BattlePointShopCatalogResourcesTest {
    @Test
    fun `uses prefixed independent BP shop directories`() {
        assertEquals("mbc-bp-shop/rules", BattlePointShopCatalogResources.ruleDirectory)
        assertEquals("mbc-bp-shop/entries", BattlePointShopCatalogResources.entryDirectory)
    }
}
