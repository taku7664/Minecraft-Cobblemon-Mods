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

/**
 * The searchless Boss decision on recorded positions where the one-turn ranking alone went wrong. The game decides with
 * the engine (status pricing, ability checks, setup stop probes), so these decide with one too: without it the class
 * passed or failed by which test class ran before it and left its engine in the global lease.
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class LocalQuickDeciderTest {
    private val worker = jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker()
    private var previousLease: jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.Lease? = null

    @org.junit.jupiter.api.BeforeAll
    fun engine() {
        previousLease = jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.lease
        jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.lease = object : jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.Lease {
            override fun <T> withWorker(deadlineNanos: Long, action: (jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker) -> T): T? = action(worker)
        }
    }

    @org.junit.jupiter.api.AfterAll
    fun releaseEngine() {
        previousLease?.let { jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.lease = it }
    }

    @Test
    fun `a one-on-one already won is kept rather than handed to a faster finisher`() {
        // Burned Milotic in front of a 78% burned Ferrothorn wins its one-on-one (exchange 0.75, win ~100%, knockout in
        // four turns); Lucario's Close Combat would finish it in one (0.93). With no reason to leave, a switch must clear
        // the margin (0.9 here, a switch last turn): the gain is 0.18 plus 0.15 for three turns sooner. This expected
        // Lucario while the switch's offensive pressure counted damage past Ferrothorn's HP (85 points; 55 capped,
        // 2026-10-19). The draw's seed follows the board, so this reads the likeliest choice rather than one draw.
        val (context, decision) = decide("milotic-ferrothorn-switch-in")
        val stages = requireNotNull(lastStages)
        val likeliest = stages.quick ?: stages.drawProbabilities.maxBy { it.value }.key
        assertTrue(label(context, likeliest) in setOf("scald", "icebeam"),
            "${label(context, likeliest)} ${stages.drawProbabilities} ${decision.tags}")
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
    fun `a low Pokemon a faster hit knocks out does not heal`() {
        // Togekiss at 14% in front of Koraidon. It has Terastallized into a pure Flying type, so Breaking Swipe hits it
        // and knocks it out, and Koraidon moves first: Roost would never land. The test first read it as a Fairy that
        // Breaking Swipe cannot touch and expected Roost, which held only while the heal check ignored move order
        // (corrected 2026-10-13, league mock battle).
        val (context, decision) = decide("togekiss-low-heals-against-koraidon")
        assertNotEquals("roost", label(context, decision), "${decision.tags}")
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
    fun `a sure knockout is taken when a switch is not expected`() {
        // Lucario in front of Furfrou, Tapu Bulu behind it. Read at Boss's exact spread (no EVs), Close Combat knocks
        // Furfrou out for sure, and the switch to Tapu Bulu is predicted at 12.5%: the knockout is the choice seven times
        // in eight, and points before a draw it still wins otherwise (2026-10-13, user decision).
        val (context, decision) = decide("lucario-aims-at-expected-tapu-bulu")
        assertEquals("closecombat", label(context, decision), "${decision.tags}")
        assertTrue(decision.tags.any { it == "quick_knockout" || it == "quick_plus_knockout_if_stays" }, "${decision.tags}")
    }

    private fun decide(name: String): Pair<BattleDecisionContext, BattleDecision> {
        val snapshot = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/$name.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .let(AiTestDecisionSnapshot::fromJson)
        val context = snapshot.context.copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L)
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, _, _ -> error("the native search must not run") },
            searchless = true,
            stageTrace = { lastStages = it },
        )
        val session = brain.openSession(BattleBrainOpenContext(
            battleId = context.state.battleId,
            format = context.state.format,
            trainerProfile = BattleTrainerProfile.boss(),
        ))
        return context to brain.decide(session, context).toCompletableFuture().get()
    }

    private var lastStages: jbro.cobblemon.mcc.betterai.brain.LocalDecisionStages? = null

    private fun label(context: BattleDecisionContext, decision: BattleDecision): String = label(context, decision.actionId)

    private fun label(context: BattleDecisionContext, actionId: String): String {
        val candidate = context.candidates.first { it.actionId == actionId }
        val part = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions.first() else candidate
        return part.switchPokemonId?.let { id -> context.state.pokemon.first { it.battlePokemonId == id }.speciesId.substringAfter(':') }
            ?: part.moveId.orEmpty()
    }
}
