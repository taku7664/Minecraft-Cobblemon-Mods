package jbro.cobblemon.mcc.internal.compat.cobblemon173

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedBattleLifecycleWiringTest {
    @Test
    fun `the shared engine owns the central lifecycle registration`() {
        val engine = source("Cobblemon173ManagedAiBattleEngine.kt")

        assertTrue(engine.contains("Cobblemon173ManagedBattleLifecycles.register("))
        assertTrue(engine.contains("Cobblemon173ManagedBattleLifecycles.battleEnded("))
        assertTrue(engine.contains("Cobblemon173ManagedBattleLifecycles.abortAndForceRelease("))
        assertTrue(engine.contains("unboundedDecisionTime = prepared.unboundedBrainDecision"))
        assertTrue(engine.contains("prepared.appearance"))
    }

    @Test
    fun `every PvE content starts battles through the shared engine`() {
        listOf(
            "Cobblemon173TowerPveBattleRuntime.kt",
            "Cobblemon173FactoryPveBattleRuntime.kt",
            "ManagedPveBattleRuntime.kt",
            "Cobblemon173AiTestBattleRuntime.kt",
        ).forEach { fileName ->
            val adapter = source(fileName)
            assertTrue(adapter.contains("Cobblemon173ManagedAiBattleEngine("), fileName)
            assertTrue(adapter.contains("engine.start("), fileName)
        }
    }

    private fun source(fileName: String): String = Files.readString(
        Path.of(
            "src/main/kotlin/jbro/cobblemon/mcc/internal/compat/cobblemon173",
            fileName,
        ),
    )
}
