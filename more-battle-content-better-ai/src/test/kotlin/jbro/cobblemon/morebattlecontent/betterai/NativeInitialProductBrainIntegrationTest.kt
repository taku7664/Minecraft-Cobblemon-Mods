package jbro.cobblemon.morebattlecontent.betterai

import java.util.concurrent.ExecutionException
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.brain.NativeInitialProductDecisionException
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelection
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluation
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRankAdapter
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionState
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeRootActionValue
import jbro.cobblemon.morebattlecontent.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlanIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlanIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeInitialProductBrainIntegrationTest {
    @Test
    fun `available opening native ranks drive the product decision without handmade root refinement`() {
        val context = contestedContext()
        val preferred = context.candidates.last()
        val alternate = context.candidates.first()
        val nativeRanks = NativeProductRankAdapter.rank(listOf(
            NativeRootActionValue(preferred, 0.40),
            NativeRootActionValue(alternate, 0.10),
        ))
        var observedMixing: LocalActionMixingContext? = null
        val selector = LocalActionSelector { ranked, seed, mixing ->
            observedMixing = mixing
            assertEquals(nativeRanks, ranked)
            LocalActionSelection(ranked.first(), seed, ranked.size, 1.0)
        }
        val brain = LocalTacticalBrain(
            actionSelector = selector,
            nativeInitialDecision = { _, _, _, _, _ ->
                NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.AVAILABLE,
                    ranked = nativeRanks,
                    depthCompleted = 2,
                    nodesVisited = 37,
                    searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                    sessionState = nativeSessionState(context),
                )
            },
        )

        val decision = brain.decide(open(brain, context), context).toCompletableFuture().get()

        assertEquals(preferred.actionId, decision.actionId)
        assertTrue(requireNotNull(observedMixing).authoritativeSimulationScores)
        assertTrue("native_showdown_initial" in decision.tags)
        assertTrue("lookahead_turns_2" in decision.tags)
        assertTrue("lookahead_nodes_37" in decision.tags)
        assertTrue("native_search_completed" in decision.tags)
        assertFalse(decision.tags.any { it.startsWith("lookahead_pruned_") })
    }

    @Test
    fun `opening native planning failure escapes to the outer fallback instead of legacy projection`() {
        val context = contestedContext()
        val issue = NativeInitialProductWorldPlanIssue(
            NativeInitialProductWorldPlanIssueCode.PUBLIC_SPECIES_IDENTITY_MISSING,
        )
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, _ ->
                NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.PLANNING_FAILED,
                    planIssues = listOf(issue),
                )
            },
        )

        val thrown = assertThrows(ExecutionException::class.java) {
            brain.decide(open(brain, context), context).toCompletableFuture().get()
        }

        val failure = thrown.cause
        assertTrue(failure is NativeInitialProductDecisionException)
        assertEquals(NativeInitialProductDecisionStatus.PLANNING_FAILED,
            (failure as NativeInitialProductDecisionException).evaluation.status)
    }

    @Test
    fun `non opening decision retains the legacy path until persistent native sessions exist`() {
        val context = contestedContext()
        var invoked = false
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, _ ->
                invoked = true
                NativeInitialProductDecisionEvaluation(NativeInitialProductDecisionStatus.NOT_APPLICABLE)
            },
        )

        val decision = brain.decide(open(brain, context), context).toCompletableFuture().get()

        assertTrue(invoked)
        assertFalse("native_showdown_initial" in decision.tags)
        assertTrue(decision.tags.any { it.startsWith("lookahead_stop_") })
    }

    @Test
    fun `opening choice is attached to the native continuation passed to the next decision`() {
        val context = contestedContext()
        val nativeRanks = NativeProductRankAdapter.rank(context.candidates.mapIndexed { index, candidate ->
            NativeRootActionValue(candidate, 1.0 - index * 0.1)
        })
        val initialState = nativeSessionState(context)
        var calls = 0
        var carried: NativeProductSessionState? = null
        val selector = LocalActionSelector { ranked, seed, _ ->
            LocalActionSelection(ranked.first(), seed, ranked.size, 1.0)
        }
        val brain = LocalTacticalBrain(
            actionSelector = selector,
            nativeInitialDecision = { _, _, _, _, state ->
                calls++
                if (calls == 1) {
                    assertNull(state)
                    NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.AVAILABLE,
                        ranked = nativeRanks,
                        depthCompleted = 1,
                        nodesVisited = 1,
                        searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                        sessionState = initialState,
                    )
                } else {
                    carried = state
                    NativeInitialProductDecisionEvaluation(NativeInitialProductDecisionStatus.NOT_APPLICABLE)
                }
            },
        )
        val session = open(brain, context)

        val first = brain.decide(session, context).toCompletableFuture().get()
        brain.decide(session, context).toCompletableFuture().get()

        assertEquals(first.actionId, carried?.pendingOwnAction?.actionId)
        assertEquals(initialState.rulesFingerprint, carried?.rulesFingerprint)
        assertEquals(initialState.worlds, carried?.worlds)
    }

    private fun nativeSessionState(
        context: jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext,
    ): NativeProductSessionState {
        val ally = context.state.pokemon.first { it.side.name == "ALLY" }
        val opponent = context.state.pokemon.first { it.side.name == "OPPONENT" }
        val definition = NativeBattleDefinition(
            formatId = "cobblemonsingles",
            seed = listOf(1, 2, 3, 4),
            p1Team = listOf(NativePokemonSet("ally", "mew", listOf("tackle"), "synchronize",
                uuid = ally.battlePokemonId.toString())),
            p2Team = listOf(NativePokemonSet("opponent", "mew", listOf("tackle"), "synchronize",
                uuid = opponent.battlePokemonId.toString())),
        )
        val root = NativeProductRootSnapshot("test-rules", NativeBattleFrame(
            snapshotJson = "root",
            turn = context.state.turn,
            requestState = "move",
            ended = false,
            p1Active = emptyList(),
            p2Active = emptyList(),
            p1Team = emptyList(),
            p2Team = emptyList(),
            p1RequestJson = "null",
            p2RequestJson = "null",
            log = emptyList(),
        ))
        return NativeProductSessionState(
            battleId = context.state.battleId,
            format = context.state.format,
            rulesFingerprint = root.rulesFingerprint,
            worlds = listOf(NativeProductSessionWorld(
                key = NativeSearchWorldKey("world-1", 0),
                probability = 1.0,
                definition = definition,
                rootSnapshot = root,
                publicContext = context,
            )),
            publicTurn = context.state.turn,
            lastObservedEventSequence = null,
        )
    }

    private fun contestedContext() =
        LocalContestedDecisionCatalog.all(LocalTacticalBrainSimulationTest()).first().context

    private fun open(brain: LocalTacticalBrain, context: jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext) =
        brain.openSession(BattleBrainOpenContext(
            battleId = context.state.battleId,
            format = context.state.format,
            trainerProfile = BattleTrainerProfile.balanced(2),
        ))
}
