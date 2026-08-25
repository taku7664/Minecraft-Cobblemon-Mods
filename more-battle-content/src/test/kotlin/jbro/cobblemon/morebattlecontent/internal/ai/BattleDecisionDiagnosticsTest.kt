package jbro.cobblemon.morebattlecontent.internal.ai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BattleDecisionDiagnosticsTest {
    @Test
    fun `selected primary summary exposes only bounded operational fields`() {
        assertEquals(
            "source=PRIMARY_BRAIN candidate_count=4 elapsed_ms=125 action_kinds=USE_MOVE failures=none",
            BattleDecisionDiagnostics.summary(
                source = BattleDecisionSource.PRIMARY_BRAIN,
                candidateCount = 4,
                elapsedMillis = 125L,
                actionKinds = listOf(BattleActionKind.USE_MOVE),
                failures = emptyList(),
            ),
        )
    }

    @Test
    fun `fallback summary names each failed stage and reason`() {
        assertEquals(
            "source=LOCAL_BRAIN candidate_count=3 elapsed_ms=902 failures=PRIMARY:BRAIN_FAILURE",
            BattleDecisionDiagnostics.summary(
                source = BattleDecisionSource.LOCAL_BRAIN,
                candidateCount = 3,
                elapsedMillis = 902L,
                failures = listOf(
                    BattleDecisionFailure(
                        stage = BattleDecisionStage.PRIMARY,
                        reason = BattleDecisionFailureReason.BRAIN_FAILURE,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `local lookahead summary exposes bounded depth and pruning diagnostics only`() {
        assertEquals(
            "source=LOCAL_BRAIN candidate_count=6 elapsed_ms=25 diagnostics=difficulty_standard,lookahead_nodes_81,lookahead_pruned_9,lookahead_turns_2 failures=none",
            BattleDecisionDiagnostics.summary(
                source = BattleDecisionSource.LOCAL_BRAIN,
                candidateCount = 6,
                elapsedMillis = 25L,
                diagnosticTags = setOf(
                    "lookahead_turns_2",
                    "lookahead_nodes_81",
                    "lookahead_pruned_9",
                    "difficulty_standard",
                    "choice_seed_should_not_be_logged",
                ),
                failures = emptyList(),
            ),
        )
    }
}
