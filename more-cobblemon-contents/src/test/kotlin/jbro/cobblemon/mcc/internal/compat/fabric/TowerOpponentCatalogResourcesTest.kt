package jbro.cobblemon.mcc.internal.compat.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerOpponentCatalogResourcesTest {
    @Test
    fun `uses an independent tower server data directory and listener identifier`() {
        assertEquals("mcc-battle-tower/trainers", TowerOpponentCatalogResources.trainerDirectory)
        assertEquals("mcc-battle-tower/pools", TowerOpponentCatalogResources.poolDirectory)
        assertEquals("mcc-battle-tower/encounters", TowerOpponentCatalogResources.encounterDirectory)
        assertEquals("mcc-battle-tower/pokemon-sets", TowerOpponentCatalogResources.pokemonSetDirectory)
        assertEquals(
            "more_cobblemon_contents:tower_opponent_catalog",
            TowerOpponentCatalogResources.listenerId.toString(),
        )
    }
}
