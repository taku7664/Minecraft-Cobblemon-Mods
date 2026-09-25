package jbro.cobblemon.morebattlecontent.betterai

import java.util.concurrent.ExecutionException
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainOpenContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.brain.NativeInitialProductDecisionException
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelection
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionSelector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluation
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRankAdapter
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductSessionReconcileStatus
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
    fun `AI test persona removes only the wall clock budget`() {
        val context = contestedContext()
        val base = LocalLookaheadBudget(timeMillis = 17L, nodeLimit = 123, chanceBranchesPerMove = 7)
        val observed = mutableListOf<LocalLookaheadBudget>()
        val brain = LocalTacticalBrain(
            lookaheadBudget = { base },
            nativeInitialDecision = { _, _, _, budget, _ ->
                observed += budget
                NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.PLANNING_FAILED,
                    planIssues = listOf(NativeInitialProductWorldPlanIssue(
                        NativeInitialProductWorldPlanIssueCode.OPPONENT_PREVIEW_MISSING,
                    )),
                )
            },
        )
        fun invoke(persona: String?) {
            val session = brain.openSession(BattleBrainOpenContext(
                battleId = context.state.battleId,
                format = context.state.format,
                trainerProfile = BattleTrainerProfile.balanced(2),
                trainerPersonaId = persona,
            ))
            assertThrows(ExecutionException::class.java) {
                brain.decide(session, context).toCompletableFuture().get()
            }
        }

        invoke(null)
        invoke("${BattleBrainContentIds.AI_TEST_PERSONA_PREFIX}boss")

        assertEquals(base, observed[0])
        assertEquals(Long.MAX_VALUE, observed[1].timeMillis)
        assertEquals(base.nodeLimit, observed[1].nodeLimit)
        assertEquals(base.chanceBranchesPerMove, observed[1].chanceBranchesPerMove)
    }

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
        assertTrue(failure.message!!.contains("PUBLIC_SPECIES_IDENTITY_MISSING"))
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

    @Test
    fun `continued native ranks drive the next decision and replace the retained roots`() {
        val context = contestedContext()
        val initialRanks = NativeProductRankAdapter.rank(context.candidates.mapIndexed { index, candidate ->
            NativeRootActionValue(candidate, 1.0 - index * 0.1)
        })
        val continuedRanks = NativeProductRankAdapter.rank(context.candidates.mapIndexed { index, candidate ->
            NativeRootActionValue(candidate, index * 0.4)
        })
        val initialState = nativeSessionState(context, "initial")
        val continuedState = nativeSessionState(context, "continued")
        var calls = 0
        var carriedAfterContinuation: NativeProductSessionState? = null
        val brain = LocalTacticalBrain(
            actionSelector = LocalActionSelector { ranked, seed, _ ->
                LocalActionSelection(ranked.first(), seed, ranked.size, 1.0)
            },
            nativeInitialDecision = { _, _, _, _, state ->
                calls++
                when (calls) {
                    1 -> NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.AVAILABLE,
                        ranked = initialRanks,
                        depthCompleted = 1,
                        nodesVisited = 1,
                        searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                        sessionState = initialState,
                    )
                    2 -> {
                        assertEquals(initialRanks.first().outcome.candidate.actionId,
                            state?.pendingOwnAction?.actionId)
                        NativeInitialProductDecisionEvaluation(
                            status = NativeInitialProductDecisionStatus.AVAILABLE,
                            ranked = continuedRanks,
                            depthCompleted = 2,
                            nodesVisited = 7,
                            searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                            sessionState = continuedState,
                        )
                    }
                    else -> {
                        carriedAfterContinuation = state
                        NativeInitialProductDecisionEvaluation(NativeInitialProductDecisionStatus.NOT_APPLICABLE)
                    }
                }
            },
        )
        val session = open(brain, context)

        brain.decide(session, context).toCompletableFuture().get()
        val continued = brain.decide(session, context).toCompletableFuture().get()
        brain.decide(session, context).toCompletableFuture().get()

        assertEquals(continuedRanks.first().outcome.candidate.actionId, continued.actionId)
        assertTrue("native_showdown_continuation" in continued.tags)
        assertEquals("continued",
            carriedAfterContinuation?.worlds?.single()?.rootSnapshot?.frame?.snapshotJson)
        assertEquals(continued.actionId, carriedAfterContinuation?.pendingOwnAction?.actionId)
    }

    @Test
    fun `single legal action still advances an existing native session`() {
        val contested = contestedContext()
        val context = contested.copy(candidates = listOf(contested.candidates.first()))
        val state = nativeSessionState(context, "continued")
        var invoked = false
        var calls = 0
        val brain = LocalTacticalBrain(
            actionSelector = LocalActionSelector { ranked, seed, _ ->
                LocalActionSelection(ranked.first(), seed, ranked.size, 1.0)
            },
            nativeInitialDecision = { _, _, _, _, supplied ->
                calls++
                if (calls == 1) {
                    assertNull(supplied)
                    NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.AVAILABLE,
                        ranked = NativeProductRankAdapter.rank(contested.candidates.mapIndexed { index, action ->
                            NativeRootActionValue(action, 1.0 - index * 0.1)
                        }),
                        depthCompleted = 1,
                        nodesVisited = 1,
                        searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                        sessionState = nativeSessionState(contested, "initial"),
                    )
                } else {
                    invoked = true
                    assertTrue(supplied != null)
                    NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.AVAILABLE,
                        ranked = NativeProductRankAdapter.rank(listOf(
                            NativeRootActionValue(context.candidates.single(), 1.0),
                        )),
                        depthCompleted = 1,
                        nodesVisited = 1,
                        searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                        sessionState = state,
                    )
                }
            },
        )
        val session = open(brain, contested)
        brain.decide(session, contested).toCompletableFuture().get()

        val decision = brain.decide(session, context).toCompletableFuture().get()

        assertTrue(invoked)
        assertEquals(context.candidates.single().actionId, decision.actionId)
        assertTrue("native_showdown_continuation" in decision.tags)
    }

    @Test
    fun `continuation reconciliation failure escapes to the outer fallback without legacy projection`() {
        val context = contestedContext()
        val state = nativeSessionState(context)
        var calls = 0
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, _ ->
                calls++
                if (calls == 1) {
                    NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.AVAILABLE,
                        ranked = NativeProductRankAdapter.rank(context.candidates.mapIndexed { index, action ->
                            NativeRootActionValue(action, 1.0 - index * 0.1)
                        }),
                        depthCompleted = 1,
                        nodesVisited = 1,
                        searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                        sessionState = state,
                    )
                } else {
                    NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.RECONCILIATION_FAILED,
                        reconciliationStatus = NativeProductSessionReconcileStatus.PUBLIC_EVENT_HISTORY_GAP,
                    )
                }
            },
        )
        val session = open(brain, context)
        brain.decide(session, context).toCompletableFuture().get()

        val thrown = assertThrows(ExecutionException::class.java) {
            brain.decide(session, context).toCompletableFuture().get()
        }

        val failure = thrown.cause as NativeInitialProductDecisionException
        assertEquals(NativeInitialProductDecisionStatus.RECONCILIATION_FAILED, failure.evaluation.status)
        assertEquals(NativeProductSessionReconcileStatus.PUBLIC_EVENT_HISTORY_GAP,
            failure.evaluation.reconciliationStatus)
    }

    private fun nativeSessionState(
        context: jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext,
        snapshotJson: String = "root",
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
            snapshotJson = snapshotJson,
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
