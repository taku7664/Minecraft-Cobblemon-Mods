package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LocalBaselineCaptureTest {
    @TempDir lateinit var temporary: Path

    private fun manifest() = LocalBaselineCapture.manifest(
        LocalBaselineOptions(2, 20260905, 2, BattleFormat.SINGLE, BattleDifficultyProfiles.INTRODUCTORY),
        LocalBaselineIdentity("test-revision", "source-digest", "runtime-digest"),
    )

    @Test
    fun `manifest keeps complete ordered teams and replays the recorded definitions`() {
        val original = manifest()
        val decoded = JsonParser.parseString(original.toString()).asJsonObject
        val definitions = LocalBaselineCapture.replayDefinitions(decoded, manifest())
        assertEquals(LocalSelfPlayMeasurement.definitions(2, 20260905), definitions)
        val teams = decoded.getAsJsonArray("teams")
        assertTrue(teams.size() >= 6)
        val entry = teams.first().asJsonObject
        for (field in listOf("setId", "abilityId", "heldItemId", "natureId", "evs", "ivs", "stats", "moves")) {
            assertTrue(entry.has(field), field)
        }
        assertEquals(4, entry.getAsJsonArray("moves").size())
        assertFalse(decoded.has("environment"))
        assertFalse(decoded.has("serverConfig"))
    }

    @Test
    fun `replay refuses changed mechanics settings or recorded teams`() {
        for (field in listOf("sourceSha256", "runtimeSha256")) {
            val changed = manifest()
            changed.getAsJsonObject("identity").addProperty(field, "changed")
            assertThrows(IllegalArgumentException::class.java) {
                LocalBaselineCapture.replayDefinitions(manifest(), changed)
            }
        }
        val changedTeam = manifest()
        changedTeam.getAsJsonArray("teams")[0].asJsonObject.addProperty("abilityId", "different")
        assertThrows(IllegalArgumentException::class.java) {
            LocalBaselineCapture.replayDefinitions(changedTeam, manifest())
        }
        val changedOptions = manifest()
        changedOptions.getAsJsonObject("options").addProperty("maximumTurns", 99)
        assertThrows(IllegalArgumentException::class.java) {
            LocalBaselineCapture.replayDefinitions(changedOptions, manifest())
        }
    }

    @Test
    fun `temporary manifest classpath carriers identify their actual inputs`() {
        val dependency = temporary.resolve("dependency.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(dependency)).use {
            it.putNextEntry(java.util.jar.JarEntry("rules.txt"))
            it.write(byteArrayOf(1, 2, 3))
            it.closeEntry()
        }
        fun carrier(name: String): Path {
            val path = temporary.resolve(name)
            val manifest = java.util.jar.Manifest().apply {
                mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[java.util.jar.Attributes.Name.CLASS_PATH] = dependency.toUri().toString()
            }
            java.util.jar.JarOutputStream(Files.newOutputStream(path), manifest).use { }
            return path
        }
        assertEquals(listOf(dependency), LocalBaselineProvenance.effectiveClasspath(listOf(carrier("first.jar"))))
        assertEquals(listOf(dependency), LocalBaselineProvenance.effectiveClasspath(listOf(carrier("second.jar"))))
    }

    @Test
    fun `capture refuses to overwrite an earlier run`() {
        val run = temporary.resolve("run")
        LocalBaselineCapture.createRun(run, manifest())
        val before = Files.readString(run.resolve("manifest.json"))
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            LocalBaselineCapture.createRun(run, manifest())
        }
        assertEquals(before, Files.readString(run.resolve("manifest.json")))
    }

    @Test
    fun `baseline records actual decisions without changing choices`() {
        val definition = LocalSelfPlayMeasurement.definitions(1, 20260905).single()
        val trace = mutableListOf<LocalScenarioDecisionTrace>()
        val plain = LocalTacticalScenarioBattle.run(definition, maximumTurns = 2,
            cycleDifficulty = BattleDifficultyProfiles.INTRODUCTORY.copy(lookaheadPlies = 0))
        val observed = LocalTacticalScenarioBattle.run(definition, maximumTurns = 2,
            cycleDifficulty = BattleDifficultyProfiles.INTRODUCTORY.copy(lookaheadPlies = 0),
            recordedDecisions = trace)
        assertEquals(plain, observed)
        assertTrue(trace.size >= 4)
        assertTrue(trace.all { it.elapsedNanos >= 0 && it.actionId.isNotBlank() })
        assertTrue(trace.all { it.source == "LOCAL_BRAIN_DIRECT" && it.tags.isNotEmpty() })
    }
}
