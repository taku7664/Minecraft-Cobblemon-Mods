package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * A move that lowers its user's own stats is charged for it once at the root and once in the search, like its
 * damage. The search used to hand the root's charge back while both kept counting the damage, so the Boss fired
 * Draco Meteor again at -2 Special Attack when a full-power move did as much.
 */
class LocalSelfDropMoveTest {
    @Test
    fun `the Boss does not repeat Draco Meteor at -2 when another move hits as hard`() {
        val boss = BattleDifficultyProfiles.BOSS
        val tuning = LocalDecisionTuning.CURRENT
        val budget = { tier: jbro.cobblemon.mcc.internal.ai.BattleTrainerTier ->
            LocalLookaheadBudgetPolicy.forTier(tier).copy(timeMillis = Long.MAX_VALUE, nodeLimit = 50_000_000)
        }
        val definition = LocalTacticalScenarioDefinition("draco-at-minus-two",
            listOf("dialga_preset_1", "rotomwash_preset_4", "heatran_preset_1"),
            listOf("toxapex_preset_2", "garchomp_preset_1", "swampert_preset_1"), 41_000, BattleFormat.SINGLE)
        val contexts = mutableListOf<jbro.cobblemon.mcc.internal.ai.BattleDecisionContext>()
        LocalTacticalScenarioBattle.run(definition, 1, tuning, tuning, boss, boss, recordedContexts = contexts,
            lookaheadBudget = budget,
            start = LocalScenarioStart(stages = mapOf((BattleSide.ALLY to 0) to mapOf("special_attack" to -2))))
        val breakdown = LocalDecisionInstrumentation.inspect(contexts.first(), BattleTrainerProfile.balanced().copy(difficulty = boss), tuning = tuning)
        println(breakdown.format("draco-at-minus-two"))
        assertFalse(breakdown.chosenByRanking!!.actionId.contains("dracometeor"), breakdown.format("draco-at-minus-two"))
    }
}
