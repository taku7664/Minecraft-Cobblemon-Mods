package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelection
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelector
import jbro.cobblemon.mcc.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluation
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.betterai.search.NativeProductRankAdapter
import jbro.cobblemon.mcc.betterai.search.NativeProductRootSnapshot
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionReconcileStatus
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionState
import jbro.cobblemon.mcc.betterai.search.NativeProductSessionWorld
import jbro.cobblemon.mcc.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.mcc.betterai.search.NativeRootActionValue
import jbro.cobblemon.mcc.betterai.search.NativeSearchWorldKey
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanIssue
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanIssueCode
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeInitialProductBrainIntegrationTest {
    @Test
    fun `AI test persona searches on the real battle clock`() {
        val context = contestedContext()
        val base = LocalLookaheadBudget(timeMillis = 17L, nodeLimit = 123, chanceBranchesPerMove = 7)
        val observed = mutableListOf<LocalLookaheadBudget>()
        val deadlines = mutableListOf<Long>()
        val brain = LocalTacticalBrain(
            lookaheadBudget = { base },
            nativeInitialDecision = { decisionContext, _, _, budget, _, _ ->
                observed += budget
                deadlines += decisionContext.deadlineEpochMillis
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
            val decision = brain.decide(session, context).toCompletableFuture().get()
            assertTrue("native_fallback_planning_failed" in decision.tags)
            assertTrue("native_failed_policy_choice" in decision.tags)
        }

        val startedAt = System.currentTimeMillis()
        invoke(null)
        invoke("${BattleBrainContentIds.AI_TEST_PERSONA_PREFIX}boss")
        val finishedAt = System.currentTimeMillis()

        // Two Pokemon a side: the native search looks a turn further on more nodes.
        val native = LocalLookaheadBudgetPolicy.forNativePosition(base, context.state)
        assertEquals(native, observed[0])
        assertEquals(native, observed[1])
        // The whole decision shares one clock, however far the caller's own deadline lies.
        deadlines.forEach { deadline ->
            assertTrue(deadline in startedAt..finishedAt + LocalLookaheadBudgetPolicy.MAX_TIME_MILLIS)
        }
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
            nativeInitialDecision = { _, _, _, _, _, _ ->
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
    fun `opening native planning failure takes the one-turn policy, never the legacy lookahead`() {
        val context = contestedContext()
        val issue = NativeInitialProductWorldPlanIssue(
            NativeInitialProductWorldPlanIssueCode.PUBLIC_SPECIES_IDENTITY_MISSING,
        )
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, _, _ ->
                NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.PLANNING_FAILED,
                    planIssues = listOf(issue),
                )
            },
        )

        val decision = brain.decide(open(brain, context), context).toCompletableFuture().get()

        assertTrue("native_fallback_planning_failed" in decision.tags)
        assertTrue("native_failed_policy_choice" in decision.tags)
        assertFalse(decision.tags.any { it.startsWith("lookahead_stop_") })
    }

    @Test
    fun `non opening decision retains the legacy path until persistent native sessions exist`() {
        val context = contestedContext()
        var invoked = false
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, _, _ ->
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
            nativeInitialDecision = { _, _, _, _, state, _ ->
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
            nativeInitialDecision = { _, _, _, _, state, _ ->
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
            nativeInitialDecision = { _, _, _, _, supplied, _ ->
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
    fun `continuation reconciliation failure clears native state and takes the one-turn policy`() {
        val context = contestedContext()
        val state = nativeSessionState(context)
        var calls = 0
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, _, _ ->
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

        val decision = brain.decide(session, context).toCompletableFuture().get()

        assertTrue("native_fallback_reconciliation_failed" in decision.tags)
        assertTrue("native_failed_policy_choice" in decision.tags)
        assertFalse(decision.tags.any { it.startsWith("lookahead_stop_") })
    }

    @Test
    fun `failed continued search keeps reconciled roots and replays the submitted choice next turn`() {
        val context = contestedContext()
        val opening = nativeSessionState(context)
        val reconciled = nativeSessionState(context, snapshotJson = "reconciled")
        val received = mutableListOf<NativeProductSessionState?>()
        val brain = LocalTacticalBrain(
            nativeInitialDecision = { _, _, _, _, previous, _ ->
                received += previous
                when (received.size) {
                    1 -> NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.AVAILABLE,
                        ranked = NativeProductRankAdapter.rank(context.candidates.mapIndexed { index, action ->
                            NativeRootActionValue(action, 1.0 - index * 0.1)
                        }),
                        depthCompleted = 1,
                        nodesVisited = 1,
                        searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                        sessionState = opening,
                    )
                    2 -> NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.SEARCH_FAILED,
                        searchStatus = NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED,
                        retainedSessionState = reconciled,
                    )
                    else -> NativeInitialProductDecisionEvaluation(
                        status = NativeInitialProductDecisionStatus.RECONCILIATION_FAILED,
                        reconciliationStatus = NativeProductSessionReconcileStatus.PUBLIC_EVENT_HISTORY_GAP,
                    )
                }
            },
        )
        val session = open(brain, context)
        brain.decide(session, context).toCompletableFuture().get()

        val fallback = brain.decide(session, context).toCompletableFuture().get()
        brain.decide(session, context).toCompletableFuture().get()

        assertTrue("native_fallback_search_failed" in fallback.tags)
        assertTrue("native_session_retained" in fallback.tags)
        val carried = requireNotNull(received[2]) { "The reconciled roots must reach the next decision" }
        assertEquals("reconciled", carried.worlds.single().rootSnapshot.frame.snapshotJson)
        assertEquals(fallback.actionId, carried.pendingOwnAction?.actionId,
            "The next reconciliation must replay the action actually submitted")
    }

    @Test
    fun `a heal that kept losing is charged on the native path as on the legacy one`() {
        // Dev game b73aae8a, turn 8: Milotic had used Recover three turns in a row into Ferrothorn's Leech Seed and
        // Power Whip and ended each lower. The native search, which sees the coming turns only, still put Recover first.
        // The recording predates the observer measuring HP changes line to line: Milotic's turns 5 to 7 carry the net
        // change between the recorded decisions instead of the +51% each the old observer wrote.
        val snapshot = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream(
            "/betterai/snapshots/milotic-losing-recover-loop.json.gz"))).use { it.readBytes().toString(Charsets.UTF_8) }
            .let(jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot::fromJson)
        val context = snapshot.context.copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000L)
        val milotic = context.state.pokemon.single { it.side.name == "ALLY" && it.activeSlot == 0 }
        assertTrue(jbro.cobblemon.mcc.betterai.evaluation.LocalRecoveryLoop.failedStreak(milotic.battlePokemonId, context) >= 2)
        // The turn's native values, Recover ahead of the switch to Giratina.
        val boardValues = mapOf(
            "move:0:1:auto:base" to -0.115, "switch:0:2d75d3e6-abe4-4909-940a-3070a5323abe" to -0.35,
            "switch:0:25414feb-753d-456e-91e6-87052c5a4501" to -0.62, "switch:0:a2201571-a26c-44a4-baa9-c92987691dc5" to -0.63,
            "move:0:0:p1a:base" to -1.62, "move:0:3:p1a:base" to -1.99, "move:0:2:auto:base" to -2.35,
            "switch:0:eabf28a2-8e74-4d83-8dcd-085acafe1580" to -2.48, "switch:0:7669caac-2343-4af6-9dd9-e332a44a2909" to -2.52,
        )
        val nativeRanks = jbro.cobblemon.mcc.betterai.search.NativeProductRankAdapter.rank(context.candidates.map {
            jbro.cobblemon.mcc.betterai.search.NativeRootActionValue(it, boardValues.getValue(it.actionId))
        })
        assertEquals("move:0:1:auto:base", nativeRanks.first().outcome.candidate.actionId)
        var finalRanks = emptyList<jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank>()
        val brain = LocalTacticalBrain(
            actionSelector = jbro.cobblemon.mcc.betterai.policy.LocalActionSelector { ranked, seed, _ ->
                finalRanks = ranked
                jbro.cobblemon.mcc.betterai.policy.LocalActionSelection(ranked.first(), seed, 1, 1.0)
            },
            nativeInitialDecision = { _, _, _, _, _, _ ->
                NativeInitialProductDecisionEvaluation(
                    status = NativeInitialProductDecisionStatus.AVAILABLE,
                    ranked = nativeRanks,
                    depthCompleted = 2,
                    nodesVisited = 100,
                    searchStatus = NativeProductWorldSearchStatus.COMPLETED,
                    sessionState = nativeSessionState(context),
                )
            },
        )

        val decision = brain.decide(open(brain, context), context).toCompletableFuture().get()

        assertEquals("switch:0:2d75d3e6-abe4-4909-940a-3070a5323abe", decision.actionId)
        val recover = finalRanks.single { it.outcome.candidate.actionId == "move:0:1:auto:base" }
        val giratina = finalRanks.single { it.outcome.candidate.actionId == decision.actionId }
        assertTrue(recover.comparisonValue < giratina.comparisonValue)
    }

    private fun nativeSessionState(
        context: jbro.cobblemon.mcc.internal.ai.BattleDecisionContext,
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

    private fun open(brain: LocalTacticalBrain, context: jbro.cobblemon.mcc.internal.ai.BattleDecisionContext) =
        brain.openSession(BattleBrainOpenContext(
            battleId = context.state.battleId,
            format = context.state.format,
            trainerProfile = BattleTrainerProfile.balanced(2),
        ))
}
