package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveRequirementKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173ShowdownMoveEffectsTest {
    @Test
    fun `recover exposes deterministic half hp healing`() {
        val effects = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("cobblemon:recover"))
        val heal = effects.effects.single { it.kind == BattleMoveEffectKind.HEAL_FRACTION }

        assertEquals(BattleMoveEffectTarget.USER, heal.target)
        assertEquals(1.0, heal.probability)
        assertEquals(0.5, heal.fractionRange?.minimum)
        assertEquals(0.5, heal.fractionRange?.maximum)
        assertFalse(effects.scriptedBehavior)
    }

    @Test
    fun `declarative status boosts and secondary chances remain mechanical facts`() {
        val burn = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("willowisp"))
            .effects.single { it.kind == BattleMoveEffectKind.STATUS }
        val drop = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("shadowball"))
            .effects.single { it.kind == BattleMoveEffectKind.STAT_STAGE }

        assertEquals("brn", burn.valueId)
        assertEquals(1.0, burn.probability)
        assertEquals(BattleMoveEffectTarget.SELECTED_TARGET, burn.target)
        assertEquals(mapOf("special_defence" to -1), drop.statStages)
        assertEquals(0.2, drop.probability)
    }

    @Test
    fun `pivot multihit and fixed level damage are distinguished`() {
        val pivot = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("u_turn"))
        val hits = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("bulletseed"))
            .effects.single { it.kind == BattleMoveEffectKind.MULTI_HIT }
        val fixed = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("seismictoss"))
            .effects.single { it.kind == BattleMoveEffectKind.FIXED_DAMAGE_LEVEL }

        assertTrue(pivot.effects.any { it.kind == BattleMoveEffectKind.SWITCH_USER })
        assertEquals(2, hits.amountRange?.minimum)
        assertEquals(5, hits.amountRange?.maximum)
        assertEquals(BattleMoveEffectTarget.SELECTED_TARGET, fixed.target)
    }

    @Test
    fun `protection side condition and room effects retain scripted caveat`() {
        val protect = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("protect"))
        val rocks = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("stealthrock"))
        val room = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("trickroom"))

        assertTrue(protect.effects.any { it.kind == BattleMoveEffectKind.PROTECT_USER })
        assertTrue(protect.scriptedBehavior)
        assertTrue(rocks.effects.any {
            it.kind == BattleMoveEffectKind.SIDE_CONDITION &&
                it.target == BattleMoveEffectTarget.TARGET_SIDE && it.valueId == "stealthrock"
        })
        assertTrue(room.effects.any {
            it.kind == BattleMoveEffectKind.FIELD_CONDITION && it.valueId == "trickroom"
        })
        assertTrue(rocks.scriptedBehavior)
        assertTrue(room.scriptedBehavior)
    }

    @Test
    fun `first active turn restrictions are exposed as mechanical facts`() {
        listOf("fakeout", "firstimpression", "matblock").forEach { moveId ->
            val effects = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve(moveId))

            assertTrue(
                effects.effects.any {
                    it.kind == BattleMoveEffectKind.FIRST_ACTIVE_TURN_ONLY &&
                        it.target == BattleMoveEffectTarget.USER
                },
                moveId,
            )
        }
    }

    @Test
    fun `team screens are exposed as distinct ally side conditions`() {
        mapOf(
            "reflect" to "reflect",
            "lightscreen" to "lightscreen",
            "auroraveil" to "auroraveil",
        ).forEach { (moveId, effectId) ->
            val effects = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve(moveId))

            assertTrue(
                effects.effects.any {
                    it.kind == BattleMoveEffectKind.SIDE_CONDITION &&
                        it.target == BattleMoveEffectTarget.USER_SIDE &&
                        it.valueId == effectId
                },
                moveId,
            )
        }
    }

    @Test
    fun `publicly decidable move requirements are extracted by mechanic family`() {
        val cases = listOf(
            Triple("auroraveil", BattleMoveRequirementKind.WEATHER_ANY_OF, setOf("hail", "snow")),
            Triple("steelroller", BattleMoveRequirementKind.TERRAIN_PRESENT, emptySet()),
            Triple("sleeptalk", BattleMoveRequirementKind.USER_STATUS_ANY_OF, setOf("slp")),
            Triple("dreameater", BattleMoveRequirementKind.TARGET_STATUS_ANY_OF, setOf("slp")),
            Triple("burnup", BattleMoveRequirementKind.USER_TYPE_ANY_OF, setOf("fire")),
            Triple("poltergeist", BattleMoveRequirementKind.TARGET_HELD_ITEM_PRESENT, emptySet()),
            Triple("revivalblessing", BattleMoveRequirementKind.FAINTED_ALLY_PRESENT, emptySet()),
            Triple("healingwish", BattleMoveRequirementKind.RESERVE_ALLY_PRESENT, emptySet()),
            Triple("metalburst", BattleMoveRequirementKind.PRIOR_DAMAGE_THIS_TURN, emptySet()),
            Triple("lastresort", BattleMoveRequirementKind.OTHER_MOVES_USED, emptySet()),
            Triple("darkvoid", BattleMoveRequirementKind.USER_SPECIES_ANY_OF, setOf("darkrai")),
        )

        cases.forEach { (moveId, kind, acceptedValues) ->
            val effects = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve(moveId))
            val requirement = effects.requirements.firstOrNull { it.kind == kind }

            assertNotNull(requirement, "$moveId requirements=${effects.requirements.map { it.kind }}")
            assertEquals(acceptedValues, requireNotNull(requirement).acceptedValueIds, moveId)
        }
    }

    @Test
    fun `hp and relative hp requirements retain their thresholds`() {
        val substitute = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("substitute"))
            .requirements.single { it.kind == BattleMoveRequirementKind.USER_HP_ABOVE_FRACTION }
        val endeavor = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("endeavor"))
            .requirements.single { it.kind == BattleMoveRequirementKind.TARGET_HP_ABOVE_USER }

        assertEquals(0.25, substitute.threshold)
        assertNull(endeavor.threshold)
    }

    @Test
    fun `stockpile consumers require stockpile without making stockpile require itself`() {
        val stockpile = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("stockpile"))
        val spitUp = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("spitup"))

        assertFalse(stockpile.requirements.any {
            it.kind == BattleMoveRequirementKind.USER_VOLATILE_PRESENT
        })
        assertEquals(
            setOf("stockpile"),
            spitUp.requirements.single {
                it.kind == BattleMoveRequirementKind.USER_VOLATILE_PRESENT
            }.acceptedValueIds,
        )
    }

    @Test
    fun `all declared showdown mechanic flags remain available as public facts`() {
        val flags = requireNotNull(Cobblemon173ShowdownMoveEffects.resolve("bloodmoon")).mechanicFlags

        assertTrue("cantusetwice" in flags)
        assertTrue("protect" in flags)
        assertTrue("mirror" in flags)
    }

    @Test
    fun `simple showdown mechanic flags are not silently discarded`() {
        val cases = mapOf(
            "fissure" to BattleMoveEffectKind.ONE_HIT_KO,
            "mindblown" to BattleMoveEffectKind.MAX_HP_RECOIL,
            "feint" to BattleMoveEffectKind.BREAKS_PROTECTION,
            "flowertrick" to BattleMoveEffectKind.ALWAYS_CRITICAL,
            "spectralthief" to BattleMoveEffectKind.STEALS_STAT_STAGES,
            "revivalblessing" to BattleMoveEffectKind.SLOT_CONDITION,
        )

        cases.forEach { (moveId, kind) ->
            assertTrue(
                requireNotNull(Cobblemon173ShowdownMoveEffects.resolve(moveId)).effects.any { it.kind == kind },
                moveId,
            )
        }
    }

    @Test
    fun `unknown or custom move remains unknown instead of being guessed`() {
        assertNull(Cobblemon173ShowdownMoveEffects.resolve("custom:not_in_showdown"))
    }
}
