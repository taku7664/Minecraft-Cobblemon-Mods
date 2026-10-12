package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
import jbro.cobblemon.mcc.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleDecision
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The searchless Boss decision on recorded positions where the one-turn ranking alone went wrong. */
class LocalQuickDeciderTest {
    @Test
    fun `a switch-in that also hits the opponent hard comes before one that only walls it`() {
        // Burned Milotic in front of Ferrothorn: every Pokemon behind it reads as not losing, Roserade by a hair the
        // most. Roserade has nothing that hurts Ferrothorn; Lucario's Close Combat does.
        val (context, decision) = decide("milotic-ferrothorn-switch-in")
        assertTrue(label(context, decision) in setOf("lucario", "giratina"), "${label(context, decision)} ${decision.tags}")
    }

    @Test
    fun `a Pokemon with no damage against the last opponent gives way to one that has some`() {
        // Roserade's Sludge Bomb cannot touch the sleeping Corviknight and hazards do nothing against a last
        // Pokemon; Milotic at 9% is the only one left that can win.
        val (context, decision) = decide("roserade-no-damage-sleeping-corviknight")
        assertEquals("milotic", label(context, decision), "${decision.tags}")
        assertTrue("quick_switch_no_damage" in decision.tags, "${decision.tags}")
    }

    @Test
    fun `a status move still to land keeps the Pokemon in`() {
        // The same Corviknight awake: Sleep Powder first.
        val (context, decision) = decide("roserade-no-damage-awake-corviknight")
        assertEquals("sleeppowder", label(context, decision), "${decision.tags}")
    }

    @Test
    fun `a low Pokemon whose heal outpaces the hit heals instead of attacking`() {
        // Togekiss at 14% in front of Koraidon, whose Breaking Swipe cannot touch a Fairy: Roost, not Air Slash.
        val (context, decision) = decide("togekiss-low-heals-against-koraidon")
        assertEquals("roost", label(context, decision), "${decision.tags}")
    }

    @Test
    fun `a doomed Pokemon is not sacrificed when a switch-in takes the hit well`() {
        // Milotic at 38% falls to Ferrothorn's Power Whip, but Lucario takes it resisted: switch, not a sacrifice.
        val (context, decision) = decide("milotic-low-switches-rather-than-sacked")
        assertTrue(label(context, decision) in setOf("lucario", "giratina"), "${label(context, decision)} ${decision.tags}")
    }

    @Test
    fun `a move drawn without Mega Evolution takes it when the Mega version ranked higher`() {
        // Garchomp's Mega Earthquake ranked first by 70 points; the draw's 6% roll took plain Earthquake.
        val (context, decision) = decide("garchomp-mega-earthquake-drawn-plain")
        val chosen = context.candidates.first { it.actionId == decision.actionId }
        assertEquals("earthquake", chosen.moveId, "${decision.tags}")
        assertEquals("mega", chosen.mechanic?.mechanicId, "${decision.tags}")
    }

    @Test
    fun `an attack aimed at the expected switch-in is not swapped for the stronger one on the Pokemon in front`() {
        // Lucario in front of Furfrou, Tapu Bulu behind it: the ranking reads the switch, so Close Combat - stronger on
        // Furfrou but resisted by the Fairy - must not replace what it chose. Meteor Mash or a Swords Dance both pass;
        // whether to set up here is the setup gate's call, not this test's.
        val (context, decision) = decide("lucario-aims-at-expected-tapu-bulu")
        assertNotEquals("closecombat", label(context, decision), "${decision.tags}")
    }

    private fun decide(name: String): Pair<BattleDecisionContext, BattleDecision> {
        val snapshot = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/$name.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .let(AiTestDecisionSnapshot::fromJson)
        val context = snapshot.context.copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L)
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, _, _ -> error("the native search must not run") },
            searchless = true,
        )
        val session = brain.openSession(BattleBrainOpenContext(
            battleId = context.state.battleId,
            format = context.state.format,
            trainerProfile = BattleTrainerProfile.boss(),
        ))
        return context to brain.decide(session, context).toCompletableFuture().get()
    }

    private fun label(context: BattleDecisionContext, decision: BattleDecision): String {
        val candidate = context.candidates.first { it.actionId == decision.actionId }
        val part = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions.first() else candidate
        return part.switchPokemonId?.let { id -> context.state.pokemon.first { it.battlePokemonId == id }.speciesId.substringAfter(':') }
            ?: part.moveId.orEmpty()
    }
}
