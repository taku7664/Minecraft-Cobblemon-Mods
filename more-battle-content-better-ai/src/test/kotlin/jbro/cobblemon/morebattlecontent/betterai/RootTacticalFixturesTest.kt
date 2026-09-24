package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RootTacticalFixturesTest {
    @Test
    fun `tactical reference covers every approved difficulty horizon`() {
        assertEquals(
            listOf(
                BattleTrainerTier.INTRODUCTORY to 1,
                BattleTrainerTier.STANDARD to 2,
                BattleTrainerTier.ADVANCED to 2,
                BattleTrainerTier.BOSS to 3,
            ),
            BattleDifficultyProfiles.entries.map { it.tier to it.lookaheadPlies },
        )
    }

    @Test
    fun `default acceptance does not compare different completed depths`() {
        val source = RootTacticalFixtures.all().single { it.id == "setup_hp40_hit120_reply60" }.context
        for (budget in listOf(50, 100, 200)) {
            val result = RootDeepeningAllocator.run(source.candidates.map { it.actionId },
                RootDeepeningPolicy.SCORE_PRIORITY, budget, evaluate = LiveRecursiveRootEvaluator(source)::evaluate)
            assertEquals("strike", result.chosen, "budget=$budget")
            assertEquals(if (budget == 200) 2 else 1, result.selectionDepth)
        }
    }

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
    fun `controls preserve immediate finishing and priority survival at every supported depth`() {
        for (fixture in RootTacticalFixtures.all().filter { it.expectedAction != null }) {
            for (depth in 1..3) {
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
            RootDeepeningPolicy.SCORE_PRIORITY, budget, RootDepthAcceptance.LATEST_COMPLETED,
            LiveRecursiveRootEvaluator(source)::evaluate)
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
    fun `setup grid exposes boss depth setup windows absent at introductory depth`() {
        val introductoryDepth = BattleDifficultyProfiles.INTRODUCTORY.lookaheadPlies
        val bossDepth = BattleDifficultyProfiles.BOSS.lookaheadPlies
        val choices = RootTacticalFixtures.all().filter { it.family == "SETUP_WINDOW" }.map { fixture ->
            val introductory = RootObjectiveReference.evaluate(fixture.context, introductoryDepth)
            val boss = RootObjectiveReference.evaluate(fixture.context, bossDepth)
            assertTrue(introductory.matches && boss.matches)
            Triple(fixture.id, introductory.isolatedRanking.first(), boss.isolatedRanking.first())
        }
        assertTrue(choices.all { it.second == "strike" }, "Introductory depth unexpectedly sets up: $choices")
        assertTrue(choices.any { it.third == "setup" }, "Boss depth finds no setup window: $choices")
    }
}
