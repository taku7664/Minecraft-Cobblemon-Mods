package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.mon
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.state
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalJointKnockoutCreditTest {
    @Test fun `two partial credits count a foe once even if one action activates a mechanic`() {
        val a = hit(0, 0.6, mechanic = "mega"); val b = hit(1, 0.6)
        assertEquals(0.36 * LocalDecisionTuning.CURRENT.knockoutMaterialScore, duplicate(a, b), 1e-9)
    }
    @Test fun `a spread extra target and a selected move share the same removal credit`() {
        val a = hit(0, 0.6, spread = true); val b = hit(1, 0.6)
        assertEquals(0.36 * LocalDecisionTuning.CURRENT.knockoutMaterialScore, duplicate(a, b), 1e-9)
    }
    @Test fun `separate foes retain their own removal credits`() {
        assertEquals(0.0, duplicate(hit(0, 0.6, target = 0), hit(1, 0.6, target = 1)), 1e-9)
    }
    private fun duplicate(a: BattleActionCandidate, b: BattleActionCandidate): Double {
        val state = state(mon(BattleSide.ALLY, 0), mon(BattleSide.ALLY, 1),
            mon(BattleSide.OPPONENT, 0), mon(BattleSide.OPPONENT, 1), format = BattleFormat.DOUBLE)
        val combined = BattleActionCandidate("both", BattleActionKind.COMPOSITE,
            componentActionIds = listOf(a.actionId, b.actionId), componentActions = listOf(a, b))
        return LocalTacticalScorer.duplicateCertainKnockoutCredit(combined, context(state, combined))
    }
    private fun hit(slot: Int, chance: Double, target: Int = 0, spread: Boolean = false, mechanic: String? = null): BattleActionCandidate {
        val range = BattleFractionRange(chance, chance)
        return BattleActionCandidate("hit:$slot", BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = 0,
            moveId = "cobblemon:tackle", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, if (spread) 1 else target)),
            mechanic = mechanic?.let { BattleMechanicCandidate(it, null, null) }, moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL,
                80.0, 100.0, 0, 10, if (spread) BattleMoveTargetPattern.ALL_OPPONENTS else BattleMoveTargetPattern.SELECTED_OPPONENT),
            facts = BattleCandidateFactsView(baseAccuracyProbability = 1.0, typeChartMultiplier = 1.0,
                standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
                standardDamageFractionRange = BattleDamageFractionRange(0.8, 1.2),
                standardKnockoutAssessment = if (spread) BattleKnockoutAssessment.IMPOSSIBLE else BattleKnockoutAssessment.POSSIBLE,
                standardDamageRollKoProbabilityRange = range,
                spreadTargets = if (spread) listOf(
                    BattleSpreadTargetFactsView(BattleSide.OPPONENT, 1, standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE),
                    BattleSpreadTargetFactsView(BattleSide.OPPONENT, 0, typeChartMultiplier = 1.0,
                        standardKnockoutAssessment = BattleKnockoutAssessment.POSSIBLE, standardDamageRollKoProbabilityRange = range),
                ) else emptyList()),
        )
    }
}
