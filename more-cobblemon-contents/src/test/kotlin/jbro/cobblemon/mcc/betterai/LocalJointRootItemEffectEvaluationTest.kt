package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A joint choice can spend the target's one item only once, even when both attacks connect. */
class LocalJointRootItemEffectEvaluationTest {
    private val fixture = LocalEvaluationRegressionFixture

    @Test
    fun `two Knock Off attacks credit one known Leftovers removal at the root`() {
        val moves = listOf(attack("knockoff", 0), attack("knockoff", 1))
        val joint = joint(moves)
        val source = context("leftovers", joint)
        val tactical = LocalTacticalScorer.scoreBreakdown(joint, source)
        val outcome = LocalBattleActionPolicy.rank(source, null, BattleTrainerProfile.boss()).single().outcome
        assertEquals(6.25, tactical.itemUtility, 1e-9)
        assertEquals(6.25, outcome.itemUtility, 1e-9)
    }

    @Test
    fun `two half accurate Knock Off attacks price the chance of at least one removal`() {
        val joint = joint(listOf(attack("knockoff", 0, accuracy = 50.0), attack("knockoff", 1, accuracy = 50.0)))
        val source = context("leftovers", joint)
        // Independent public accuracy: 1 - (1 - .5)^2 = .75, not .5 + .5.
        assertEquals(6.25 * 0.75, LocalTacticalScorer.scoreBreakdown(joint, source).itemUtility, 1e-9)
        assertEquals(6.25 * 0.75,
            LocalBattleActionPolicy.rank(source, null, BattleTrainerProfile.boss()).single().outcome.itemUtility, 1e-9)
    }

    @Test
    fun `two Knock Off attacks remove the public Choice Band pressure once`() {
        val single = attack("knockoff", 0)
        val joint = joint(listOf(single, attack("knockoff", 1)))
        fun itemDelta(action: BattleActionCandidate): Double =
            LocalTacticalScorer.score(action, context("choiceband", action)) -
                LocalTacticalScorer.score(action, context("", action))
        val once = itemDelta(single)
        assertTrue(once > 1.0, "The opponent's public physical move must make its Choice Band valuable")
        assertEquals(once, itemDelta(joint), 1e-9)
    }

    @Test
    fun `two super effective attacks trigger the target's Weakness Policy only once`() {
        val single = attack("shadowball", 0, type = "ghost")
        val joint = joint(listOf(single, attack("shadowball", 1, type = "ghost")))
        val singleValue = LocalTacticalScorer.scoreBreakdown(single, context("weaknesspolicy", single, targetType = "psychic"))
        val jointContext = context("weaknesspolicy", joint, targetType = "psychic")
        val jointValue = LocalTacticalScorer.scoreBreakdown(joint, jointContext)
        assertTrue(singleValue.itemUtility < -1.0, "The publicly revealed physical reply must price its Attack boost")
        assertEquals(singleValue.itemUtility, jointValue.itemUtility, 1e-9)
        assertEquals(singleValue.statStageUtility, jointValue.statStageUtility, 1e-9)
        assertEquals(singleValue.itemUtility,
            LocalBattleActionPolicy.rank(jointContext, null, BattleTrainerProfile.boss()).single().outcome.itemUtility, 1e-9)
    }

    @Test
    fun `two attacks that jointly knock out the holder get no future Leftovers value`() {
        val joint = joint(listOf(attack("knockoff", 0), attack("knockoff", 1)))
        val source = context("leftovers", joint, targetHp = 0.15)
        assertEquals(0.0, LocalTacticalScorer.scoreBreakdown(joint, source).itemUtility, 1e-9)
        assertEquals(0.0, LocalBattleActionPolicy.rank(source, null, BattleTrainerProfile.boss()).single().outcome.itemUtility, 1e-9)
    }

    @Test
    fun `a faster neutral Knock Off removes Weakness Policy before the slower super effective hit`() {
        val joint = joint(listOf(attack("knockoff", 0, type = "normal"), attack("shadowball", 1, type = "ghost")))
        val source = context("weaknesspolicy", joint, targetType = "psychic", firstAbility = "normalize",
            firstSpeed = 200, secondSpeed = 50)
        val tactical = LocalTacticalScorer.scoreBreakdown(joint, source)
        assertEquals(0.0, tactical.itemUtility, 1e-9)
        assertEquals(0.0, tactical.statStageUtility, 1e-9)
        val outcome = LocalBattleActionPolicy.rank(source, null, BattleTrainerProfile.boss()).single().outcome
        assertEquals(0.0, outcome.itemUtility, 1e-9)
        assertEquals(0.0, outcome.statStageUtility, 1e-9)
    }

    @Test
    fun `a shared extra spread target uses its own public damage instead of the primary range`() {
        val range = BattleDamageFractionRange(0.01, 0.01)
        val zeroKo = BattleFractionRange(0.0, 0.0)
        val spread = BattleActionCandidate("spread:0", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:surf", moveDetails = BattleMoveCandidateView("water", BattleMoveDamageCategory.SPECIAL,
                40.0, 100.0, 0, 10, BattleMoveTargetPattern.ALL_OPPONENTS),
            facts = BattleCandidateFactsView(baseAccuracyProbability = 1.0,
                standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
                standardDamageFractionRange = range, standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
                standardDamageRollKoProbabilityRange = zeroKo,
                spreadTargets = listOf(
                    BattleSpreadTargetFactsView(BattleSide.OPPONENT, 0, typeChartMultiplier = 1.0,
                        standardDamageFractionRange = range, standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
                        standardDamageRollKoProbabilityRange = zeroKo),
                    BattleSpreadTargetFactsView(BattleSide.OPPONENT, 1, typeChartMultiplier = 1.0,
                        standardDamageFractionRange = BattleDamageFractionRange(0.1, 0.1),
                        standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
                        standardDamageRollKoProbabilityRange = zeroKo),
                )))
        val joint = joint(listOf(spread, attack("knockoff", 1, targetSlot = 1)))
        val original = context("", joint)
        val source = original.copy(state = original.state.copyState(pokemon = original.state.pokemon.map {
            if (it.side == BattleSide.OPPONENT && it.activeSlot == 1) it.copyState(hpFraction = 0.15, knownHeldItemId = "leftovers") else it
        }))
        // The extra target takes .1 + .1 and faints; .01 is only the primary target's damage.
        assertEquals(0.0, LocalTacticalScorer.scoreBreakdown(joint, source).itemUtility, 1e-9)
        assertEquals(0.0, LocalBattleActionPolicy.rank(source, null, BattleTrainerProfile.boss()).single().outcome.itemUtility, 1e-9)
    }

    private fun context(item: String, action: BattleActionCandidate, targetType: String = "normal",
        targetHp: Double = 1.0, firstAbility: String? = null, firstSpeed: Int = 100, secondSpeed: Int = 100): BattleDecisionContext {
        val first = fixture.mon(BattleSide.ALLY, 0, speed = firstSpeed, ability = firstAbility).copyState(knownHeldItemId = "")
        val second = fixture.mon(BattleSide.ALLY, 1, speed = secondSpeed).copyState(knownHeldItemId = "")
        val target = fixture.mon(BattleSide.OPPONENT, 0, type = targetType, hp = targetHp).copyState(knownHeldItemId = item)
        val partner = fixture.mon(BattleSide.OPPONENT, 1).copyState(knownHeldItemId = "")
        return fixture.context(fixture.state(first, second, target, partner, format = BattleFormat.DOUBLE), action)
            .copy(publicActionCatalog = BattlePublicActionCatalogView(listOf(
                BattlePokemonActionCatalogView(target.battlePokemonId, listOf(BattlePublicMoveOptionView(
                    "cobblemon:tackle", BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL,
                        40.0, 100.0, 0, 10, BattleMoveTargetPattern.SELECTED_OPPONENT),
                    BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true),
                BattlePokemonActionCatalogView(partner.battlePokemonId, listOf(BattlePublicMoveOptionView(
                    "cobblemon:splash", BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS,
                        0.0, 100.0, 0, 10, BattleMoveTargetPattern.SELF),
                    BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true),
            )))
    }

    private fun joint(moves: List<BattleActionCandidate>) = BattleActionCandidate("joint", BattleActionKind.COMPOSITE,
        componentActionIds = moves.map { it.actionId }, componentActions = moves)

    private fun attack(id: String, slot: Int, type: String = "dark", accuracy: Double = 100.0, targetSlot: Int = 0) = BattleActionCandidate(
        "$id:$slot", BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = 0, moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, targetSlot)),
        moveDetails = BattleMoveCandidateView(type, BattleMoveDamageCategory.PHYSICAL, 40.0, accuracy, 0, 10,
            BattleMoveTargetPattern.SELECTED_OPPONENT),
        facts = BattleCandidateFactsView(baseAccuracyProbability = accuracy / 100.0,
            standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
            standardDamageFractionRange = BattleDamageFractionRange(0.1, 0.1),
            standardKnockoutAssessment = BattleKnockoutAssessment.IMPOSSIBLE,
            standardDamageRollKoProbabilityRange = BattleFractionRange(0.0, 0.0)),
    )
}
