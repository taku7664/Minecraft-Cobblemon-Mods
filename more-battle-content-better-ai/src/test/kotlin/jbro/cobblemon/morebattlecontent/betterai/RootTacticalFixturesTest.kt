package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootTacticalFixturesTest {
    @Test
    fun `fixed grid is complete not filtered by scheduler success`() {
        val fixtures = RootTacticalFixtures.all()
        assertEquals(20, fixtures.size)
        assertEquals(20, fixtures.map { it.id }.distinct().size)
        assertEquals(18, fixtures.count { it.family == "SETUP_WINDOW" })
        for (fixture in fixtures) {
            assertEquals(BattleFormat.SINGLE, fixture.context.state.format)
            assertNull(PublicRootAllocationExperiment.exclusion(fixture.context))
            assertTrue(fixture.context.state.pokemon.all { it.combatStats != null && it.knownMoveIds.isNotEmpty() })
        }
    }

    @Test
    fun `controls preserve immediate finishing and priority survival at both depths`() {
        for (fixture in RootTacticalFixtures.all().filter { it.expectedAction != null }) {
            for (depth in 1..2) {
                val result = RootObjectiveReference.evaluate(fixture.context, depth)
                assertTrue(result.matches)
                assertEquals(fixture.expectedAction, result.isolatedRanking.first(), "${fixture.id} depth=$depth")
            }
        }
    }

    @Test
    fun `priority control really prevents a lethal public reply`() {
        val fixture = RootTacticalFixtures.all().single { it.id == "priority_survival" }
        val source = fixture.context
        val reply = PublicFutureActionFactory.actions(source.state, BattleSide.OPPONENT, source.publicActionCatalog).single()
        fun outcomes(id: String) = PublicSingleTurnProjector.project(source.state,
            source.candidates.single { it.actionId == id }, reply, source)
        val quick = outcomes("priority")
        val slow = outcomes("strike")
        assertTrue(quick.isNotEmpty() && slow.isNotEmpty())
        assertTrue(quick.all { o -> o.state.pokemon.single { it.side == BattleSide.ALLY }.hpFraction > 0.0 })
        assertTrue(quick.all { o -> o.state.pokemon.single { it.side == BattleSide.OPPONENT }.fainted })
        assertTrue(slow.all { o -> o.state.pokemon.single { it.side == BattleSide.ALLY }.fainted })
    }

    @Test
    fun `mixed depths can worsen a real projected choice before full depth completes`() {
        // Characterization of a known experimental limitation, not desired product behavior.
        val source = RootTacticalFixtures.all().single { it.id == "setup_hp40_hit120_reply60" }.context
        val reference = RootObjectiveReference.evaluate(source, 2)
        fun run(budget: Int) = RootDeepeningAllocator.run(source.candidates.map { it.actionId },
            RootDeepeningPolicy.SCORE_PRIORITY, budget, LiveRecursiveRootEvaluator(source)::evaluate)
        val shallow = run(50)
        val mixed = run(100)
        val complete = run(200)
        assertEquals(0.0, RootObjectiveReference.loss(reference.scores, shallow.chosen))
        assertTrue(mixed.depths.values.distinct().size > 1)
        assertTrue(requireNotNull(RootObjectiveReference.loss(reference.scores, mixed.chosen)) > 0.0)
        assertTrue(complete.targetDepthComplete)
        assertEquals(0.0, RootObjectiveReference.loss(reference.scores, complete.chosen))
    }

    @Test
    fun `setup grid exposes at least one change of best action with deeper search`() {
        val choices = RootTacticalFixtures.all().filter { it.family == "SETUP_WINDOW" }.map { fixture ->
            val one = RootObjectiveReference.evaluate(fixture.context, 1)
            val two = RootObjectiveReference.evaluate(fixture.context, 2)
            assertTrue(one.matches && two.matches)
            Triple(fixture.id, one.isolatedRanking.first(), two.isolatedRanking.first())
        }
        println("TACTICAL_HORIZONS $choices")
        assertTrue(choices.any { it.second != it.third }, "Grid does not distinguish search depth: $choices")
    }
}
