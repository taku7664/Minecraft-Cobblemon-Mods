package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class LocalForesightOwnershipTest {
    @Test
    fun `zero foresight preserves one turn scores across deeper completed searches`() {
        val failures = mutableListOf<String>()
        var compared = 0
        for (fixture in RootTacticalFixtures.all()) {
            // Include damage-roll boundaries instead of only coarse HP checkpoints.
            for (hpStep in 1..40) {
                val hp = hpStep / 40.0
                val source = withOpponentHp(fixture.context, hp)
                val profile = BattleTrainerProfile.balanced().let {
                    it.copy(difficulty = it.difficulty.copy(foresightWeight = 0.0, lookaheadPlies = 1))
                }
                val ranked = LocalBattleActionPolicy.rank(source, strategy = null, profile = profile)
                fun evaluate(depth: Int) = LocalRecursiveLookaheadEvaluator.evaluate(ranked, source,
                    profile.copy(difficulty = profile.difficulty.copy(lookaheadPlies = depth)),
                    clockMillis = { 0L }, budget = LocalLookaheadBudget(100000, 100000, 2))
                val shallow = evaluate(1)
                val deep = evaluate(2)
                assertEquals(1, shallow.depthCompleted)
                assertEquals(2, deep.depthCompleted)
                assertEquals(shallow.publicResponseCoverage, deep.publicResponseCoverage)
                val first = shallow.ranked.associateBy { it.outcome.candidate.actionId }
                for (action in deep.ranked) {
                    compared++
                    val before = first.getValue(action.outcome.candidate.actionId)
                    if (abs(before.comparisonValue - action.comparisonValue) > 1e-9 ||
                        abs(before.lookaheadUtility - action.lookaheadUtility) > 1e-9) {
                        failures += "${fixture.id} hp=$hp ${action.outcome.candidate.actionId}: ${before.comparisonValue} -> ${action.comparisonValue}"
                    }
                }
            }
        }
        println("ZERO_FORESIGHT compared=$compared mismatches=${failures.size}")
        assertEquals(1640, compared)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `nonzero foresight still contributes future value`() {
        val source = RootTacticalFixtures.all().single { it.id == "setup_hp100_hit60_reply30" }.context
        val profile = BattleTrainerProfile.balanced()
        val ranked = LocalBattleActionPolicy.rank(source, strategy = null, profile = profile)
        val values = listOf(0.0, 0.5, 1.0).map { weight ->
            val result = LocalRecursiveLookaheadEvaluator.evaluate(ranked, source,
                profile.copy(difficulty = profile.difficulty.copy(foresightWeight = weight, lookaheadPlies = 2)),
                clockMillis = { 0L }, budget = LocalLookaheadBudget(100000, 100000, 2))
            assertEquals(2, result.depthCompleted)
            result.ranked.single { it.outcome.candidate.actionId == "setup" }.comparisonValue
        }
        assertTrue(abs(values[2] - values[0]) > 1.0)
        assertEquals((values[0] + values[2]) / 2.0, values[1], 1e-9)
    }

    private fun withOpponentHp(source: BattleDecisionContext, hp: Double): BattleDecisionContext {
        val state = source.state
        val pokemon = state.pokemon.map { old ->
            if (old.side == BattleSide.ALLY) old else BattlePokemonStateView(
                old.battlePokemonId, old.side, old.activeSlot, old.speciesId, old.formId, old.level,
                hp, old.statusId, old.statStages, old.knownMoveIds, old.knownAbilityId, old.knownHeldItemId,
                false, old.knownTypeIds, old.combatStats)
        }
        return PublicBattleTacticalCalculator.calculate(BattleDecisionContext(source.requestId,
            BattleStateView(state.battleId, state.format, state.turn, pokemon, state.field,
                state.remainingPokemonBySide, state.observedEvents, state.inferences), source.candidates.map { old ->
                    BattleActionCandidate(old.actionId, old.kind, old.actorSlot, old.moveSlot, old.moveId,
                        old.targets, old.switchPokemonId, old.componentActionIds, old.componentActions,
                        old.mechanic, old.moveDetails, facts = null, tags = old.tags)
                },
            Long.MAX_VALUE, source.memory, source.publicActionCatalog))
    }
}
