package jbro.cobblemon.mcc.internal.compat.fabric

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedServerCatalogCleanupRegistrationTest {
    @Test
    fun `server stop backstop clears every process wide catalog`() {
        assertTrue(source("ManagedServerEphemeralStateCleanup.kt").contains("BattlePointShopCatalogResources.store::clear"))
        assertTrue(source("TowerOpponentCatalogResources.kt").contains("ManagedServerEphemeralStateCleanup.register(store::clear)"))
    }

    @Test
    fun `managed battle entity cleanup runs in a phase after feature settlement handlers`() {
        val source = source("ManagedBattleLifecycleEvents.kt")

        listOf("DISCONNECT", "SERVER_STOPPING", "SERVER_STOPPED").forEach { event ->
            assertTrue(source.contains("$event.addPhaseOrdering(Event.DEFAULT_PHASE, BACKSTOP_PHASE)"), event)
            assertTrue(source.contains("$event.register(BACKSTOP_PHASE)"), event)
        }
    }

    private fun source(fileName: String): String = Files.readString(
        Path.of("src/main/kotlin/jbro/cobblemon/mcc/internal/compat/fabric", fileName),
    )
}
