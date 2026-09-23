package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedServerCatalogCleanupRegistrationTest {
    @Test
    fun `server stop backstop clears every process wide catalog`() {
        val source = Files.readString(
            Path.of(
                "src/main/kotlin/jbro/cobblemon/morebattlecontent/internal/compat/fabric/" +
                    "ManagedServerEphemeralStateCleanup.kt",
            ),
        )

        assertTrue(source.contains("FactoryCatalogResources.store::clear"))
        assertTrue(source.contains("TowerOpponentCatalogResources.store::clear"))
        assertTrue(source.contains("BattlePointShopCatalogResources.store::clear"))
    }
}
