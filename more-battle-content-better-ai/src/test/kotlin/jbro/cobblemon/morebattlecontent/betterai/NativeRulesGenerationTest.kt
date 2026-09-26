package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRuleRegistry
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRuleRegistryDescriptor
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRulesGeneration
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRulesUnavailableException
import jbro.cobblemon.morebattlecontent.betterai.simulation.MegaShowdownNativeRulesProvider
import jbro.cobblemon.morebattlecontent.betterai.simulation.ReflectiveNativeRulesProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeRulesGenerationTest {
    @Test
    fun `worker rejects an engine changed after rules capture`(@TempDir directory: Path) {
        Files.createDirectories(directory.resolve("sim"))
        Files.writeString(directory.resolve("index.js"), "module.exports = true;")
        Files.writeString(directory.resolve("sim/battle.js"), "module.exports = {};")
        NativeRulesGeneration.capture(directory).use { rules ->
            Files.writeString(directory.resolve("index.js"), "throw new Error('mutated source root');")
            val failure = assertThrows(IllegalStateException::class.java) {
                NativeShowdownBranchEngine.open(directory, rules).close()
            }
            assertTrue(failure.message.orEmpty().contains("changed after capture"))
        }
    }

    @Test
    fun `generation reads the original tree without copying debug maps`(@TempDir directory: Path) {
        Files.createDirectories(directory.resolve("sim"))
        Files.writeString(directory.resolve("index.js"), "module.exports = true;")
        Files.writeString(directory.resolve("sim/battle.js"), "module.exports = {};")
        Files.writeString(directory.resolve("sim/battle.js.map"), "large debug map")

        NativeRulesGeneration.capture(directory).use { first ->
            assertEquals(directory.toRealPath(), first.engineRoot)
            val fingerprint = first.fingerprint
            Files.writeString(directory.resolve("sim/battle.js.map"), "changed debug map")
            NativeRulesGeneration.capture(directory).use { changedMap ->
                assertEquals(fingerprint, changedMap.fingerprint)
            }
            Files.writeString(directory.resolve("sim/battle.js"), "module.exports = { changed: true };")
            NativeRulesGeneration.capture(directory).use { changedCode ->
                assertNotEquals(fingerprint, changedCode.fingerprint)
            }
        }
        assertTrue(Files.exists(directory.resolve("index.js")))
    }

    @Test
    fun `reflection captures raw callback sources deterministically`(@TempDir directory: Path) {
        Files.createDirectories(directory.resolve("sim"))
        Files.writeString(directory.resolve("index.js"), "globalThis.test = true;")
        Files.writeString(directory.resolve("sim/battle.js"), "module.exports = {};")
        val provider = ReflectiveNativeRulesProvider(
            listOf(
                NativeRuleRegistryDescriptor(
                    NativeRuleRegistry.ABILITY,
                    FakeAbilityRegistry::class.java.name,
                    "getAbilityScripts",
                ),
                NativeRuleRegistryDescriptor(
                    NativeRuleRegistry.MOVE,
                    FakeMoveRegistry::class.java.name,
                    "getMoveScripts",
                ),
            ),
        )

        provider.capture(directory, javaClass.classLoader).use { first ->
            provider.capture(directory, javaClass.classLoader).use { second ->
                assertEquals(first.fingerprint, second.fingerprint)
                assertEquals(
                    listOf("ABILITY:callbackability", "MOVE:callbackmove"),
                    first.sources.map { "${it.registry}:${it.id}" },
                )
                Files.writeString(directory.resolve("index.js"), "globalThis.test = false;")
                provider.capture(directory, javaClass.classLoader).use { changed ->
                    assertNotEquals(first.fingerprint, changed.fingerprint)
                }
            }
        }
    }

    @Test
    fun `Mega provider requires every declared runtime registry`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("index.js"), "globalThis.test = true;")

        assertEquals(NativeRuleRegistry.entries.toSet(), MegaShowdownNativeRulesProvider.descriptors.map { it.registry }.toSet())
        assertThrows(NativeRulesUnavailableException::class.java) {
            MegaShowdownNativeRulesProvider.capture(directory, object : ClassLoader(null) {})
        }
    }

    object FakeAbilityRegistry {
        @JvmStatic fun getAbilityScripts(): Map<String, String> =
            linkedMapOf("callbackability" to "({ name: 'Callback Ability', flags: {} })")
    }

    object FakeMoveRegistry {
        @JvmStatic fun getMoveScripts(): Map<String, String> =
            linkedMapOf("callbackmove" to "({ name: 'Callback Move', type: 'Normal' })")
    }
}
