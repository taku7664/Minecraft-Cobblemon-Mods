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

    @Test
    fun `managed battle entity cleanup runs after feature settlement handlers`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/morebattlecontent/MoreBattleContent.kt"),
        )

        val towerRegistration = source.indexOf("TowerPlayNetworking.registerServer()")
        val factoryRegistration = source.indexOf("FactoryCommandRuntime.registerServer()")
        val lifecycleRegistration = source.indexOf("ManagedBattleLifecycleEvents.registerServer()")

        assertTrue(towerRegistration >= 0)
        assertTrue(factoryRegistration >= 0)
        assertTrue(lifecycleRegistration > towerRegistration)
        assertTrue(lifecycleRegistration > factoryRegistration)
    }
}
