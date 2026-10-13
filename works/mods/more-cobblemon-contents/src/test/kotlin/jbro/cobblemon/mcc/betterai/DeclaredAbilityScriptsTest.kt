package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import jbro.cobblemon.mcc.betterai.mechanics.DeclaredAbilityScripts
import jbro.cobblemon.mcc.betterai.simulation.RuntimeAbilityScripts
import jbro.cobblemon.mcc.betterai.mechanics.DeclaredAbilityScripts.MoveFacts
import jbro.cobblemon.mcc.betterai.mechanics.DeclaredAbilityScripts.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Damage modifiers read from ability scripts: a datapack's single ability and Showdown's whole `abilities.js`. */
class DeclaredAbilityScriptsTest {
    private val contact = MoveFacts("Psychic", "Physical", setOf("contact", "protect"))
    private val noContact = MoveFacts("Steel", "Special", setOf("protect"))

    @Test
    fun `a datapack ability script halving contact moves is read for the Pokemon it protects`() {
        val ability = DeclaredAbilityScripts.parse(DATAPACK_SCRIPT, singleId = "auraguard").getValue("auraguard")
        assertEquals("Aura Guard", ability.name)
        assertEquals(mapOf("breakable" to 1), ability.flags)
        assertEquals(Step.SOURCE_MODIFY_DAMAGE, ability.handlers.single().step)
        assertEquals(0.5, ability.damageFactor(defender = true, contact), 1e-9)
        assertEquals(1.0, ability.damageFactor(defender = true, noContact), 1e-9)
        // It is the hit Pokemon's: its own attacks are not touched.
        assertEquals(1.0, ability.damageFactor(defender = false, contact), 1e-9)
    }

    @Test
    fun `Showdown's abilities file is read for its plain modifiers and leaves out what it cannot read`() {
        val abilities = DeclaredAbilityScripts.parse(SHOWDOWN_ABILITIES)
        assertTrue(abilities.size > 250, "abilities read: ${abilities.size}")
        // A category test on the hit Pokemon's damage step.
        assertEquals(0.5, abilities.getValue("icescales").damageFactor(true, noContact), 1e-9)
        assertEquals(1.0, abilities.getValue("icescales").damageFactor(true, contact), 1e-9)
        // A type test on both attacking stats, the debug call inside the block skipped.
        assertEquals(1.5, abilities.getValue("steelworker").damageFactor(false, noContact), 1e-9)
        assertEquals(1.0, abilities.getValue("steelworker").damageFactor(false, contact), 1e-9)
        // A running product in a local variable is not a shape this reader takes on.
        assertTrue(abilities.getValue("fluffy").handlers.isEmpty())
    }

    @Test
    fun `the engine takes a script's damage step only where it has no port`() {
        val scripts = DeclaredAbilityScripts.parse(SHOWDOWN_ABILITIES) +
            DeclaredAbilityScripts.parse(DATAPACK_SCRIPT, singleId = "auraguard")
        val unported = RuntimeAbilityScripts.unported(scripts)
        // Ported in the engine: the script is not used for it.
        assertNull(unported["icescales"])
        assertNull(unported["steelworker"])
        assertEquals(Step.SOURCE_MODIFY_DAMAGE, unported.getValue("auraguard").handlers.single().step)
        val dex = EngineDex.bundled().withAbilities(unported.values)
        val effect = requireNotNull(dex.abilityOrNull("auraguard"))
        assertEquals("Aura Guard", effect.name)
        assertTrue(effect.handler("onSourceModifyDamage") is Function1<*, *>)
    }

    private companion object {
        val DATAPACK_SCRIPT = """
            ({
              onSourceModifyDamage(damage, source, target, move) {
                if (move.flags["contact"]) return this.chainModify(0.5);
              },
              flags: { breakable: 1 },
              name: "Aura Guard",
              rating: 3.5,
              num: -218
            })
        """.trimIndent()

        val SHOWDOWN_ABILITIES: String by lazy {
            java.util.zip.ZipInputStream(requireNotNull(DeclaredAbilityScriptsTest::class.java.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "data/abilities.js" }
                zip.readBytes().toString(Charsets.UTF_8)
            }
        }
    }
}
