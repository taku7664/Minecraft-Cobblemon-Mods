package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedBattleLifecycleWiringTest {
    @Test
    fun `AI test uses the same lifecycle registered runtime as tower battles`() {
        val aiTestSource = source("Cobblemon173AiTestBattleRuntime.kt")
        val towerSource = source("Cobblemon173TowerPveBattleRuntime.kt")

        assertTrue(aiTestSource.contains("runtime.startManaged("))
        assertTrue(towerSource.contains("Cobblemon173ManagedBattleLifecycles.register("))
        assertTrue(towerSource.contains("Cobblemon173ManagedBattleLifecycles.battleEnded("))
    }

    @Test
    fun `factory battles register the central lifecycle too`() {
        val factorySource = source("Cobblemon173FactoryPveBattleRuntime.kt")

        assertTrue(factorySource.contains("Cobblemon173ManagedBattleLifecycles.register("))
        assertTrue(factorySource.contains("Cobblemon173ManagedBattleLifecycles.battleEnded("))
    }

    private fun source(fileName: String): String = Files.readString(
        Path.of(
            "src/main/kotlin/jbro/cobblemon/morebattlecontent/internal/compat/cobblemon173",
            fileName,
        ),
    )
}
