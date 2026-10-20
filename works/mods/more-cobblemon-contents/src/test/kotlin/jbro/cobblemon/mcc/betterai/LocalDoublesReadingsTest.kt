package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
import jbro.cobblemon.mcc.betterai.brain.LocalDecisionStages
import jbro.cobblemon.mcc.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.mcc.betterai.evaluation.LocalIdleUtilityMoveRules
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Doubles readings from a hand-played Trick Room game (2026-10-20). */
class LocalDoublesReadingsTest {
    @Test
    fun `a knockout lands unless its target threatens the attacker, not the partner`() {
        // A 26% +2 Kingambit would knock out the Slowking, not the Rhyperior whose Earthquake knocks it out: read off its
        // best target, its knockout was worth nothing and the Rhyperior Stone Edged the Rotom-Wash instead.
        val (context, stages) = decide("rhyperior-earthquakes-setting-up-kingambit-doubles")
        val best = stages.base.maxBy { it.value }.key
        assertEquals("earthquake", parts(context, best).first().moveId, "${label(context, best)} ${stages.base.entries.sortedByDescending { it.value }.take(4)}")
    }

    @Test
    fun `a target falling to sand at the turn's end is worth no knockout once it has moved`() {
        // Sand and Trick Room up: the 2% Hatterene moves first and the sand takes it; the Dusclops beside it is the target.
        val (context, stages) = decide("duraludon-ignores-hatterene-falling-to-sand-doubles")
        val best = stages.base.maxBy { it.value }.key
        val attack = parts(context, best).first()
        assertEquals(listOf(BattleTargetSlot(BattleSide.OPPONENT, 1)), attack.targets, "${label(context, best)} ${attack.targets}")
    }

    @Test
    fun `an attack part counts at the chance its user moves before the other foe knocks it out`() {
        // Under Trick Room the 19% Kingdra moves after the Hatterene beside its target, whose Dazzling Gleam knocks it out:
        // its hits earn nothing, so spending the side's Dynamax on one is a loss.
        val (context, stages) = decide("kingdra-doomed-keeps-dynamax-doubles")
        val best = stages.refined.maxBy { it.value }.key
        assertTrue(parts(context, best).none { it.mechanic != null }, "${label(context, best)} ${stages.refined.entries.sortedByDescending { it.value }.take(4)}")
        assertTrue(stages.drawProbabilities.filterKeys { id -> parts(context, id).any { it.mechanic != null } }.values.sum() < 0.25, "${stages.drawProbabilities}")
    }

    @Test
    fun `a part is read on the weather its faster partner's move sets first`() {
        // Garchomp's Max Rockfall (sand) comes before Torkoal's Solar Beam: the beam charges and is halved, not fired in sun.
        val snapshot = load("torkoal-solarbeam-under-partner-max-rockfall-doubles")
        val context = jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator.calculate(snapshot.context, observedBoard = true)
        fun total(id: String) = jbro.cobblemon.mcc.betterai.policy.LocalBattleActionOutcomeEvaluator.evaluate(
            context.candidates.first { it.actionId == id }, context, null, BattleTrainerProfile.boss())
        val beamWithRockfall = total("move 3 2, move 3 1 dynamax")
        val beamWithEdge = total("move 3 2, move 3 1")
        val beamAlone = jbro.cobblemon.mcc.betterai.policy.LocalBattleActionOutcomeEvaluator.evaluate(
            beamWithEdge.componentOutcomes[0].candidate, context, null, BattleTrainerProfile.boss())
        assertTrue(beamWithRockfall.componentOutcomes[0].tacticalUtility < beamAlone.tacticalUtility - 10.0,
            "${beamWithRockfall.componentOutcomes[0].tacticalUtility} vs ${beamAlone.tacticalUtility}")
        assertEquals(beamAlone.tacticalUtility, beamWithEdge.componentOutcomes[0].tacticalUtility, 1e-9)
    }

    @Test
    fun `a Trick Room part takes the room's value in doubles`() {
        val (context, stages) = decide("trickroom-lead-doubles")
        val room = stages.rules["trickRoom"].orEmpty()
        assertTrue(room.isNotEmpty(), "${stages.rules.keys}")
        val best = stages.refined.maxBy { it.value }.key
        assertTrue(parts(context, best).any { it.moveId == "trickroom" }, label(context, best))
        // Both slots toggling it leave the room as it was.
        val twice = context.candidates.first { c -> parts(context, c.actionId).count { it.moveId == "trickroom" } == 2 }.actionId
        assertTrue((stages.refined[twice] ?: 0.0) < (stages.refined[best] ?: 0.0) - 100.0, "${stages.refined[twice]}")
    }

    @Test
    fun `a delayed hit fails into a slot already waiting on one`() {
        val context = load("rhyperior-earthquakes-setting-up-kingambit-doubles").context
        val pending = BattleTimedEffectView("futuresightslot1", 2, sourcePokemonId = context.state.pokemon.first {
            it.side == BattleSide.ALLY && it.speciesId.endsWith("slowking") }.battlePokemonId, targetSlot = 1, sourceMoveId = "futuresight")
        val field = context.state.field
        val waiting = context.copy(state = context.state.derive(field = BattleFieldStateView(field.weather, field.terrain, field.roomEffects,
            field.globalEffects, field.sideConditions + (BattleSide.OPPONENT to field.sideConditions[BattleSide.OPPONENT].orEmpty() + pending))))
        val sights = waiting.candidates.flatMap { parts(waiting, it.actionId) }.filter { it.moveId == "futuresight" && it.mechanic == null }
            .distinctBy { it.targets }
        val into = sights.single { it.targets.single() == BattleTargetSlot(BattleSide.OPPONENT, 1) }
        val other = sights.single { it.targets.single() == BattleTargetSlot(BattleSide.OPPONENT, 0) }
        assertTrue(LocalIdleUtilityMoveRules.fails(into, waiting))
        assertFalse(LocalIdleUtilityMoveRules.fails(other, waiting))
        assertFalse(LocalIdleUtilityMoveRules.fails(into, context))
    }

    private fun load(name: String): AiTestDecisionSnapshot =
        java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/betterai/snapshots/$name.json.gz")))
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .let(AiTestDecisionSnapshot::fromJson)

    private fun decide(name: String): Pair<BattleDecisionContext, LocalDecisionStages> {
        val snapshot = load(name)
        val context = snapshot.context.copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L)
        var stages: LocalDecisionStages? = null
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, _, _ -> error("the native search must not run") },
            searchless = true,
            stageTrace = { stages = it },
        )
        val session = brain.openSession(BattleBrainOpenContext(battleId = context.state.battleId, format = context.state.format,
            trainerProfile = BattleTrainerProfile.boss()))
        brain.decide(session, context).toCompletableFuture().get()
        return context to requireNotNull(stages)
    }

    private fun parts(context: BattleDecisionContext, actionId: String): List<BattleActionCandidate> {
        val candidate = context.candidates.first { it.actionId == actionId }
        return if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
    }

    private fun label(context: BattleDecisionContext, actionId: String) =
        parts(context, actionId).joinToString("+") { it.moveId ?: ">" + it.switchPokemonId }
}
