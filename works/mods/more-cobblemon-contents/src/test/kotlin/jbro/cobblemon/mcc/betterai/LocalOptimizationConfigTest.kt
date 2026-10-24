package jbro.cobblemon.mcc.betterai

import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.mcc.betterai.config.*
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalOptimizationConfigTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("betterai-optimizations.json")
    private val base get() = LocalDecisionTuning.CURRENT

    @Test fun `missing and empty configs keep all formats disabled`() {
        val config=LocalOptimizationConfigStore.loadOrCreate(path)
        for(format in BattleFormat.entries)assertEquals(base,config.applyTo(base,format))
        Files.writeString(path,"{}")
        assertEquals(LocalOptimizationConfig(),LocalOptimizationConfigStore.loadOrCreate(path))
    }
    @Test fun `each option and battle format are independent`() {
        val config=LocalOptimizationConfig(
            single=LocalOptimizationSettings(simplePoisonEvaluation=true,poisonEvaluationTurns=3),
            double=LocalOptimizationSettings(simpleHpMatchups=true,simpleFixedHpReactions=true,simpleForecastMatchups=true,skipUnusedStatusMatchups=true,firstHitActingChance=true,simpleStatusMatchups=true,simpleActingChance=true))
        LocalOptimizationConfigStore.save(path,config)
        val loaded=LocalOptimizationConfigStore.loadOrCreate(path)
        assertEquals(config,loaded)
        val single=loaded.applyTo(base,BattleFormat.SINGLE);val double=loaded.applyTo(base,BattleFormat.DOUBLE)
        assertEquals(3,single.poisonEvaluationTurns);assertFalse(single.simpleHpMatchups);assertFalse(single.simpleFixedHpReactions)
        assertFalse(single.simpleForecastMatchups);assertTrue(double.simpleForecastMatchups)
        assertFalse(single.skipUnusedStatusMatchups);assertTrue(double.skipUnusedStatusMatchups)
        assertFalse(single.firstHitActingChance);assertTrue(double.firstHitActingChance)
        assertFalse(single.simpleStatusMatchups);assertTrue(double.simpleStatusMatchups)
        assertFalse(single.simpleActingChance);assertTrue(double.simpleActingChance)
        assertNull(double.poisonEvaluationTurns);assertTrue(double.simpleHpMatchups);assertTrue(double.simpleFixedHpReactions)
        LocalOptimizationConfigStore.save(path,LocalOptimizationConfig())
        assertTrue(double.simpleHpMatchups)
        assertFalse(LocalOptimizationConfigStore.loadOrCreate(path).applyTo(base,BattleFormat.DOUBLE).simpleHpMatchups)
    }
    @Test fun `legacy flat options preserve both formats until separated`() {
        Files.writeString(path,"""{"simpleFixedHpReactions":true,"simpleHpMatchups":true,"single":{}}""")
        val loaded=LocalOptimizationConfigStore.loadOrCreate(path)
        assertEquals(LocalOptimizationSettings(),loaded.single)
        assertTrue(loaded.double.simpleFixedHpReactions);assertTrue(loaded.double.simpleHpMatchups)
        LocalOptimizationConfigStore.save(path,loaded)
        assertEquals(loaded,LocalOptimizationConfigStore.loadOrCreate(path))
    }
    @Test fun `saving preserves unknown root and format fields`() {
        Files.writeString(path,"""{"futureOption":{"keep":true},"single":{"other":4}}""")
        LocalOptimizationConfigStore.save(path,LocalOptimizationConfig())
        val saved=Files.readString(path)
        assertTrue(saved.contains("futureOption"));assertTrue(saved.contains("other"))
    }
    @Test fun `malformed options and formats are rejected without overwriting`() {
        for(invalid in listOf("""{"double":{"simpleStatusMatchups":"true"}}""", """{"single":{"simpleActingChance":1}}""", """{"double":{"firstHitActingChance":"true"}}""", """{"double":{"skipUnusedStatusMatchups":"true"}}""", """{"double":{"simpleForecastMatchups":"true"}}""", """{"single":{"simpleHpMatchups":"true"}}""", """{"double":false}""",
            """{"poisonEvaluationTurns":0}""","""{"double":{"poisonEvaluationTurns":2.5}}""")) {
            Files.writeString(path,invalid)
            assertThrows(Exception::class.java) {LocalOptimizationConfigStore.loadOrCreate(path)}
            assertEquals(invalid,Files.readString(path))
        }
    }
}
