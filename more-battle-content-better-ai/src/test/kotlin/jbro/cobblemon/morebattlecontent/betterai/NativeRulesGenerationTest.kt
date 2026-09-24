package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRuleRegistry
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRuleRegistryDescriptor
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRulesUnavailableException
import jbro.cobblemon.morebattlecontent.betterai.simulation.MegaShowdownNativeRulesProvider
import jbro.cobblemon.morebattlecontent.betterai.simulation.ReflectiveNativeRulesProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeRulesGenerationTest {
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
