package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourcesTest {
    @Test
    fun `uses an independent tower server data directory and listener identifier`() {
        assertEquals(
            "battle_tower/opponents",
            TowerOpponentCatalogResources.catalogDirectory,
        )
        assertEquals(
            "cobblemon_more_battle_content:tower_opponent_catalog",
            TowerOpponentCatalogResources.listenerId.toString(),
        )
    }
}
