package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerPersonality
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionOutcome
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalTrainerStyle
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalWeightedActionSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalRepeatedProtectionSelectionTest {
    private val selector = LocalWeightedActionSelector()

    @Test
    fun `successful protection chain excludes another protection when any viable action exists`() {
        val protect = rank("protect", 900.0, executableDamageActions = 0)
        val attack = rank("foul_play", 10.0, executableDamageActions = 1, entryFaints = true)
        val context = LocalActionMixingContext(
            personality = BattleTrainerPersonality.balanced(),
            memory = BattleTacticalMemoryView(lastMoveId = "protect", sameMoveRepeatCount = 1),
            style = LocalTrainerStyle.BALANCED,
            riskBudget = 0.5,
            repeatedProtectionActionIds = setOf("protect"),
        )

        repeat(1_000) { seed ->
            assertEquals("foul_play", selector.choose(listOf(protect, attack), seed.toLong(), context)
                .rank.outcome.candidate.actionId)
        }
        assertEquals("protect", selector.choose(listOf(protect), 0L, context).rank.outcome.candidate.actionId)
    }

    private fun rank(
        actionId: String,
        score: Double,
        executableDamageActions: Int,
        entryFaints: Boolean = false,
    ) = LocalBattleActionRank(
        outcome = LocalBattleActionOutcome(
            candidate = BattleActionCandidate(
                actionId = actionId,
                kind = BattleActionKind.USE_MOVE,
                actorSlot = 0,
                moveSlot = 0,
                moveId = actionId,
            ),
            tacticalUtility = score,
            expectedDamageFraction = 0.0,
            secureStandardKnockouts = 0,
            executableDamageActions = executableDamageActions,
            publiclyInert = false,
            entryFaints = entryFaints,
            switchPostEntryHp = null,
            currentDefensiveExposure = null,
            resultingDefensiveExposure = null,
            survivalPositionImprovement = null,
        ),
        decisionTier = 3,
        comparisonValue = score,
        executionProbability = 1.0,
        worstResponseHpRetention = 1.0,
    )
}
