package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Invoke the existing JVM descriptors directly, including Kotlin's old default-argument bridge. */
class BattlePokemonBaseAbilityAbiRegressionTest {
    @Test
    fun `the existing 21 argument JVM constructor still builds a public Pokemon view`() {
        val view = BattlePokemonStateView::class.java.getDeclaredConstructor(*legacyTypes()).newInstance(*legacyArguments()) as BattlePokemonStateView
        assertEquals("trace", view.knownAbilityId)
        assertEquals(setOf("normal"), view.knownBaseStabTypeIds)
        assertNull(view.knownTeraTypeId)
        assertNull(view.knownBaseAbilityId)
    }

    @Test
    fun `the existing Kotlin default constructor bridge keeps its descriptor and mask meaning`() {
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val signature = legacyTypes().toMutableList().apply { add(Int::class.javaPrimitiveType!!); add(marker) }.toTypedArray()
        val arguments = legacyArguments().toMutableList()
        // Original optional inputs: types, stats, forms, constraints, base STAB, Tera and Stellar types.
        val defaultInputs = listOf(13, 14, 15, 16, 18, 19, 20)
        defaultInputs.forEach { arguments[it] = null }
        arguments += defaultInputs.fold(0) { mask, index -> mask or (1 shl index) }
        arguments += null
        val view = BattlePokemonStateView::class.java.getDeclaredConstructor(*signature).newInstance(*arguments.toTypedArray()) as BattlePokemonStateView
        assertEquals("trace", view.knownAbilityId)
        assertEquals(emptySet<String>(), view.knownTypeIds)
        assertEquals(emptySet<String>(), view.knownBaseStabTypeIds)
        assertEquals(emptySet<String>(), view.knownVolatileEffectIds)
        assertNull(view.combatStats)
        assertNull(view.knownTeraTypeId)
        assertNull(view.knownBaseAbilityId)
    }

    @Test
    fun `the older 17 18 and 20 argument JVM constructors retain their descriptors`() {
        for (count in listOf(17, 18, 20)) {
            val view = BattlePokemonStateView::class.java.getDeclaredConstructor(*legacyTypes().take(count).toTypedArray())
                .newInstance(*legacyArguments().take(count).toTypedArray()) as BattlePokemonStateView
            assertEquals("trace", view.knownAbilityId)
            assertNull(view.knownBaseAbilityId)
        }
    }

    @Test
    fun `the original 17 argument Kotlin default bridge also remains callable`() {
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val signature = legacyTypes().take(17).toMutableList().apply { add(Int::class.javaPrimitiveType!!); add(marker) }.toTypedArray()
        val arguments = legacyArguments().take(17).toMutableList()
        val defaults = listOf(13, 14, 15, 16)
        defaults.forEach { arguments[it] = null }
        arguments += defaults.fold(0) { mask, index -> mask or (1 shl index) }
        arguments += null
        val view = BattlePokemonStateView::class.java.getDeclaredConstructor(*signature).newInstance(*arguments.toTypedArray()) as BattlePokemonStateView
        assertEquals("trace", view.knownAbilityId)
        assertEquals(emptySet<String>(), view.knownTypeIds)
        assertNull(view.knownBaseAbilityId)
    }

    @Test
    fun `the new explicit base ability input is nullable by default and survives a structural copy`() {
        val legacy = BattlePokemonStateView::class.java.getDeclaredConstructor(*legacyTypes()).newInstance(*legacyArguments()) as BattlePokemonStateView
        assertNull(legacy.knownBaseAbilityId)
        val copied = legacy.copyState(knownAbilityId = "levitate", knownBaseAbilityId = "trace")
        assertEquals("trace", copied.knownBaseAbilityId)
        assertEquals("trace", copied.copyState(hpFraction = 0.5).knownBaseAbilityId)
        assertEquals("levitate", copied.knownAbilityId)
    }

    private fun legacyTypes() = arrayOf(
        UUID::class.java, BattleSide::class.java, Int::class.javaObjectType, String::class.java, String::class.java,
        Int::class.javaObjectType, Double::class.javaPrimitiveType!!, String::class.java, Map::class.java, Set::class.java,
        String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!, Set::class.java,
        BattleCombatStatRangesView::class.java, Map::class.java, BattlePokemonActionConstraintView::class.java,
        Set::class.java, Set::class.java, String::class.java, Set::class.java,
    )

    private fun legacyArguments(): Array<Any?> = arrayOf(
        UUID.randomUUID(), BattleSide.ALLY, 0, "showdown:probe", null, 100, 1.0, null, emptyMap<String, Int>(), emptySet<String>(),
        "trace", null, false, setOf("normal"), null, emptyMap<String, Any>(), BattlePokemonActionConstraintView.empty(),
        emptySet<String>(), setOf("normal"), null, null,
    )
}
