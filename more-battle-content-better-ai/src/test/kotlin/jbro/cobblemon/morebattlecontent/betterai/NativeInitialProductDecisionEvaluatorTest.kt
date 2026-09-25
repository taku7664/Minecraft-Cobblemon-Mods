package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactOwnTeamView
import jbro.cobblemon.morebattlecontent.api.ai.BattleExactPokemonBuildView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionEvaluator
import jbro.cobblemon.morebattlecontent.betterai.search.NativeInitialProductDecisionStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchResult
import jbro.cobblemon.morebattlecontent.betterai.search.NativeProductWorldSearchStatus
import jbro.cobblemon.morebattlecontent.betterai.search.NativeRootActionValue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorld
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlan
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlanIssue
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeInitialProductWorldPlanIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeInitialProductDecisionEvaluatorTest {
    @Test
    fun `turns posterior native values into product ranks without handmade score carryover`() {
        val context = context()
        var observedDeadline = 0L
        val evaluator = NativeInitialProductDecisionEvaluator(
            planWorlds = { supplied, _ -> plan(supplied) },
            searchWorlds = { request ->
                observedDeadline = request.deadlineNanos
                NativeProductWorldSearchResult(
                    status = NativeProductWorldSearchStatus.COMPLETED,
                    rootValues = listOf(
                        NativeRootActionValue(request.productActions[0], 0.25),
                        NativeRootActionValue(request.productActions[1], 0.10),
                    ),
                    depthCompleted = 1,
                    nodesVisited = 7,
                )
            },
            nowEpochMillis = { 1_000L },
            nanoTime = { 5_000_000L },
            leafEvaluator = { _, _, _, _ -> 0.05 },
        )

        val result = evaluator.evaluate(
            context,
            BattleTrainerProfile.balanced(),
            LocalDecisionTuning.CURRENT,
            LocalLookaheadBudget(250L, 100, 1),
        )

        assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, result.status)
        assertEquals(listOf("action-a", "action-b"), result.ranked.map { it.outcome.candidate.actionId })
        assertEquals(listOf(20.0, 5.0), result.ranked.map { it.comparisonValue })
        assertTrue(result.ranked.all { !it.outcome.publiclyInert && !it.outcome.entryFaints })
        assertEquals(7, result.nodesVisited)
        assertEquals(1, result.depthCompleted)
        assertEquals(255_000_000L, observedDeadline)
    }

    @Test
    fun `non opening state is not applicable and invokes no native dependency`() {
        var invoked = false
        val evaluator = NativeInitialProductDecisionEvaluator(
            planWorlds = { _, _ -> invoked = true; error("must not plan") },
            searchWorlds = { invoked = true; error("must not search") },
        )

        val result = evaluator.evaluate(
            context(turn = 2),
            BattleTrainerProfile.balanced(),
            LocalDecisionTuning.CURRENT,
            LocalLookaheadBudget(250L, 100, 1),
        )

        assertEquals(NativeInitialProductDecisionStatus.NOT_APPLICABLE, result.status)
        assertFalse(invoked)
    }

    @Test
    fun `planning failure is explicit and never starts search`() {
        val issue = NativeInitialProductWorldPlanIssue(
            NativeInitialProductWorldPlanIssueCode.PUBLIC_SPECIES_IDENTITY_MISSING,
        )
        var searched = false
        val evaluator = NativeInitialProductDecisionEvaluator(
            planWorlds = { _, _ -> NativeInitialProductWorldPlan(emptyList(), listOf(issue)) },
            searchWorlds = { searched = true; error("must not search") },
        )

        val result = evaluator.evaluate(
            context(),
            BattleTrainerProfile.balanced(),
            LocalDecisionTuning.CURRENT,
            LocalLookaheadBudget(250L, 100, 1),
        )

        assertEquals(NativeInitialProductDecisionStatus.PLANNING_FAILED, result.status)
        assertEquals(listOf(issue), result.planIssues)
        assertTrue(result.ranked.isEmpty())
        assertFalse(searched)
    }

    @Test
    fun `expired product deadline fails before native search`() {
        var searched = false
        val evaluator = NativeInitialProductDecisionEvaluator(
            planWorlds = { supplied, _ -> plan(supplied) },
            searchWorlds = { searched = true; error("must not search") },
            nowEpochMillis = { 2_000L },
        )

        val result = evaluator.evaluate(
            context(),
            BattleTrainerProfile.balanced(),
            LocalDecisionTuning.CURRENT,
            LocalLookaheadBudget(250L, 100, 1),
        )

        assertEquals(NativeInitialProductDecisionStatus.SEARCH_FAILED, result.status)
        assertEquals(NativeProductWorldSearchStatus.NO_COMMON_COMPLETED_DEPTH, result.searchStatus)
        assertTrue(result.ranked.isEmpty())
        assertFalse(searched)
    }

    @Test
    fun `native world failure remains explicit and carries the failed world`() {
        val evaluator = NativeInitialProductDecisionEvaluator(
            planWorlds = { supplied, _ -> plan(supplied) },
            searchWorlds = {
                NativeProductWorldSearchResult(
                    status = NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED,
                    nodesVisited = 11,
                    failedWorldId = "world-1",
                )
            },
            nowEpochMillis = { 1_000L },
        )

        val result = evaluator.evaluate(
            context(),
            BattleTrainerProfile.balanced(),
            LocalDecisionTuning.CURRENT,
            LocalLookaheadBudget(250L, 100, 1),
        )

        assertEquals(NativeInitialProductDecisionStatus.SEARCH_FAILED, result.status)
        assertEquals(NativeProductWorldSearchStatus.WORLD_SEARCH_FAILED, result.searchStatus)
        assertEquals("world-1", result.failedWorldId)
        assertEquals(11, result.nodesVisited)
        assertTrue(result.ranked.isEmpty())
    }

    @Test
    fun `last common completed depth remains available when deeper search truncates`() {
        val evaluator = NativeInitialProductDecisionEvaluator(
            planWorlds = { supplied, _ -> plan(supplied) },
            searchWorlds = { request ->
                NativeProductWorldSearchResult(
                    status = NativeProductWorldSearchStatus.PARTIAL_DEPTH,
                    rootValues = listOf(
                        NativeRootActionValue(request.productActions[1], 0.30),
                        NativeRootActionValue(request.productActions[0], 0.20),
                    ),
                    depthCompleted = 1,
                    nodesVisited = 100,
                )
            },
            nowEpochMillis = { 1_000L },
        )

        val result = evaluator.evaluate(
            context(),
            BattleTrainerProfile.balanced(),
            LocalDecisionTuning.CURRENT,
            LocalLookaheadBudget(250L, 100, 1),
        )

        assertEquals(NativeInitialProductDecisionStatus.AVAILABLE, result.status)
        assertEquals(listOf("action-b", "action-a"), result.ranked.map { it.outcome.candidate.actionId })
        assertEquals(1, result.depthCompleted)
        assertEquals(100, result.nodesVisited)
        assertTrue(result.truncated)
    }

    private fun plan(context: BattleDecisionContext) = NativeInitialProductWorldPlan(
        worlds = listOf(NativeInitialProductWorld(
            hypothesisId = "world-1",
            probability = 1.0,
            definition = DEFINITION,
            publicContext = context,
        )),
        issues = emptyList(),
    )

    private fun context(turn: Int = 1): BattleDecisionContext {
        val state = BattleStateView(
            battleId = BATTLE,
            format = BattleFormat.SINGLE,
            turn = turn,
            pokemon = listOf(
                pokemon(ALLY, BattleSide.ALLY),
                pokemon(OPPONENT, BattleSide.OPPONENT),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val exact = BattleExactOwnTeamView(listOf(BattleExactPokemonBuildView(
            ALLY, "synchronize", null, "serious", "N", ZERO_EVS, PERFECT_IVS,
            "psychic", "mew",
        )))
        val preview = BattleOpponentTeamPreviewView(1, listOf(
            BattleOpponentTeamPreviewPokemonView(0, "cobblemon:mew", "normal", 50),
        ))
        return BattleDecisionContext(
            requestId = REQUEST,
            state = state,
            candidates = ACTIONS,
            deadlineEpochMillis = 2_000L,
        ).copy(opponentTeamPreview = preview, exactOwnTeam = exact)
    }

    private fun pokemon(id: UUID, side: BattleSide) = BattlePokemonStateView(
        id, side, 0, "cobblemon:mew", "normal", 50, 1.0, null,
        emptyMap(), emptySet(), null, null, false,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val ZERO_EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
        val PERFECT_IVS = ZERO_EVS.mapValues { 31 }
        val ACTIONS = listOf(
            BattleActionCandidate("action-a", BattleActionKind.USE_MOVE, 0, 0, "psychic"),
            BattleActionCandidate("action-b", BattleActionKind.USE_MOVE, 0, 1, "shadowball"),
        )
        val DEFINITION = NativeBattleDefinition(
            "cobblemonsingles",
            listOf(1, 2, 3, 4),
            listOf(NativePokemonSet("ally", "mew", listOf("psychic"), "synchronize", uuid = ALLY.toString())),
            listOf(NativePokemonSet("opponent", "mew", listOf("psychic"), "synchronize", uuid = OPPONENT.toString())),
        )
    }
}
