package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourcesTest {
    @Test
    fun `uses stable server data resource and listener identifiers`() {
        assertEquals(
            "cobblemon_more_battle_content:battle_tower/opponents/mbc_core.json",
            TowerOpponentCatalogResources.catalogResourceId.toString(),
        )
        assertEquals(
            "cobblemon_more_battle_content:tower_opponent_catalog",
            TowerOpponentCatalogResources.listenerId.toString(),
        )
    }
}
