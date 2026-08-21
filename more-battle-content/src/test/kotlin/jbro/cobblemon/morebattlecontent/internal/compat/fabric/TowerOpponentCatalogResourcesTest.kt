package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourcesTest {
    @Test
    fun `uses an independent tower server data directory and listener identifier`() {
        assertEquals("mbc-battle-tower/trainers", TowerOpponentCatalogResources.trainerDirectory)
        assertEquals("mbc-battle-tower/pools", TowerOpponentCatalogResources.poolDirectory)
        assertEquals("mbc-battle-tower/encounters", TowerOpponentCatalogResources.encounterDirectory)
        assertEquals("mbc-battle-tower/pokemon-sets", TowerOpponentCatalogResources.pokemonSetDirectory)
        assertEquals(
            "cobblemon_more_battle_content:tower_opponent_catalog",
            TowerOpponentCatalogResources.listenerId.toString(),
        )
    }
}
