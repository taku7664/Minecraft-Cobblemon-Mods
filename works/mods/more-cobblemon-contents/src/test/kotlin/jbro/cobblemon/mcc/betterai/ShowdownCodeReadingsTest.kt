package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.mechanics.LocalMoveCode
import jbro.cobblemon.mcc.internal.ai.BattleDeclarativeMoveEffects
import jbro.cobblemon.mcc.internal.ai.BattleShowdownHeldEffects
import jbro.cobblemon.mcc.internal.ai.BattleShowdownMoveEffects
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The move, item and ability readings the brain takes from the Showdown bundle's code instead of naming them (user
 * decision 2026-10-20): each must find exactly what the named checks it replaced found.
 */
class ShowdownCodeReadingsTest {
    @Test
    fun `move code gives Rest's counter, Sleep Talk, Yawn, Glaive Rush, terrains and Max Guard`() {
        assertEquals(3, LocalMoveCode.userSleepTurns("rest"))
        assertNull(LocalMoveCode.userSleepTurns("spore"))
        assertTrue(LocalMoveCode.callsOwnMoveAsleep("sleeptalk"))
        // Snore is usable asleep too, but calls no other move.
        assertFalse(LocalMoveCode.callsOwnMoveAsleep("snore"))
        assertEquals("yawn", LocalMoveCode.delayedSleepVolatile("yawn"))
        assertNull(LocalMoveCode.delayedSleepVolatile("taunt"))
        assertEquals("glaiverush", LocalMoveCode.exposingVolatile("glaiverush"))
        assertNull(LocalMoveCode.exposingVolatile("outrage"))
        assertTrue(LocalMoveCode.setsTerrain("grassyterrain"))
        assertFalse(LocalMoveCode.setsTerrain("raindance"))
        assertEquals("maxguard", LocalMoveCode.maxProtectMoveId)
    }

    @Test
    fun `the field moves that still try each Pokemon are Perish Song, Flower Shield and Teatime`() {
        // The named list this replaced held Rototiller, which does not, and missed Teatime.
        assertEquals(setOf("perishsong", "flowershield", "teatime"),
            BattleShowdownMoveEffects.idsWithFlag(BattleDeclarativeMoveEffects.TRIES_EACH_POKEMON_FLAG).toSet())
    }

    @Test
    fun `item code gives the Choice boosts and Covert Cloak`() {
        assertEquals(BattleShowdownHeldEffects.ChoiceBoost("atk", 1.5, true), BattleShowdownHeldEffects.choiceBoost("choiceband"))
        assertEquals(BattleShowdownHeldEffects.ChoiceBoost("spa", 1.5, true), BattleShowdownHeldEffects.choiceBoost("choicespecs"))
        assertEquals(BattleShowdownHeldEffects.ChoiceBoost("spe", 1.5, true), BattleShowdownHeldEffects.choiceBoost("choicescarf"))
        assertNull(BattleShowdownHeldEffects.choiceBoost("lifeorb"))
        assertTrue(BattleShowdownHeldEffects.isChoice("choicescarf"))
        assertFalse(BattleShowdownHeldEffects.isChoice("leftovers"))
        assertTrue(BattleShowdownHeldEffects.shieldsSecondaries("covertcloak"))
        assertFalse(BattleShowdownHeldEffects.shieldsSecondaries("leftovers"))
    }

    @Test
    fun `ability code gives Stance Change's forms and Disguise's broken form`() {
        assertEquals(BattleShowdownHeldEffects.StanceForms("", "blade", "kingsshield"), BattleShowdownHeldEffects.stanceForms("stancechange"))
        assertNull(BattleShowdownHeldEffects.stanceForms("disguise"))
        assertEquals(BattleShowdownHeldEffects.FirstHitShield("busted", 0.125), BattleShowdownHeldEffects.firstHitShield("disguise"))
        assertNull(BattleShowdownHeldEffects.firstHitShield("stancechange"))
        assertNull(BattleShowdownHeldEffects.firstHitShield("multiscale"))
    }
}
