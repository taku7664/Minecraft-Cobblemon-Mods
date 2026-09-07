package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalBoardMaterial
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalLeafDamageCapTest {
    @Test
    fun `uncapped diagnostic arm restores overkill incentive without changing raw exposure`() {
        val capped = EmbeddedPolicyComparison.tuning("CURRENT")
        val uncapped = EmbeddedPolicyComparison.tuning("CURRENT_UNCAPPED_LEAF")
        val ordinary = context(0, 100.0, BattleMoveTargetPattern.SELECTED_OPPONENT)
        val boosted = context(2, 100.0, BattleMoveTargetPattern.SELECTED_OPPONENT)
        fun leaf(source: BattleDecisionContext, tuning: LocalDecisionTuning) =
            LocalLookaheadStateEvaluator.evaluate(source.state, source, tuning = tuning)
        assertEquals(leaf(ordinary, capped), leaf(boosted, capped), 1e-9)
        assertTrue(leaf(boosted, uncapped) > leaf(ordinary, uncapped))
        assertTrue(leaf(ordinary, uncapped) > leaf(ordinary, capped))
        for (source in listOf(ordinary, boosted)) {
            assertEquals(LocalLookaheadStateEvaluator.attackPressure(source.state, BattleSide.ALLY, source,
                tuning = capped), LocalLookaheadStateEvaluator.attackPressure(source.state, BattleSide.ALLY, source,
                tuning = uncapped), 0.0)
        }
    }

    @Test
    fun `doubles leaf caps each candidate at its primary target before accuracy`() {
        for (accuracy in listOf(100.0, 50.0)) {
            for (pattern in listOf(BattleMoveTargetPattern.SELECTED_OPPONENT, BattleMoveTargetPattern.ALL_OPPONENTS)) {
                val targetHp = if (pattern == BattleMoveTargetPattern.ALL_OPPONENTS) 0.25 else 0.75
                val ordinary = context(0, accuracy, pattern)
                val boosted = context(2, accuracy, pattern)
                assertTrue(PublicFutureActionFactory.actions(ordinary.state, BattleSide.ALLY,
                    ordinary.publicActionCatalog).any { joint -> joint.componentActions.any { it.moveId == "overkill" } })
                val tuning = LocalDecisionTuning.CURRENT
                fun value(source: BattleDecisionContext) = LocalLookaheadStateEvaluator.evaluate(source.state, source)
                assertEquals(LocalBoardMaterial.evaluate(ordinary.state) +
                    (targetHp + tuning.leafKnockoutPressure) * accuracy / 100.0 * tuning.leafPressureWeight,
                    value(ordinary), 1e-9, "$pattern accuracy=$accuracy")
                assertEquals(value(ordinary), value(boosted), 1e-9,
                    "Already fatal attacks must not acquire more leaf value from boosting")
            }
        }
    }

    @Test
    fun `primary HP follows the damage calculators public redirection and spread order`() {
        for ((ability, type, expected) in listOf(
            Triple("lightningrod", "electric", 0.75), Triple("stormdrain", "water", 0.75),
            Triple("stormdrain", "electric", 0.25))) {
            val source = context(0, 100.0, BattleMoveTargetPattern.SELECTED_OPPONENT, type, ability)
            assertEquals(expected, PublicBattleTacticalCalculator.primaryTargetHpFraction(
                source.candidates.single(), source, BattleSide.ALLY), ability)
        }
        val spread = context(0, 100.0, BattleMoveTargetPattern.ALL_OPPONENTS, "electric", "lightningrod")
        assertEquals(0.25, PublicBattleTacticalCalculator.primaryTargetHpFraction(
            spread.candidates.single(), spread, BattleSide.ALLY), "Spread pressure uses its existing primary defender")
    }

    @Test
    fun `missing declared target does not invent a damage cap`() {
        val source = context(0, 100.0, BattleMoveTargetPattern.SELECTED_OPPONENT)
        val missing = BattleActionCandidate("missing", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "overkill", moveDetails = source.candidates.single().moveDetails,
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 3)))
        assertNull(PublicBattleTacticalCalculator.primaryTargetHpFraction(missing, source, BattleSide.ALLY))
    }

    private fun context(stage: Int, accuracy: Double, pattern: BattleMoveTargetPattern,
        type: String = "normal", redirector: String? = null): BattleDecisionContext {
        val state = BattleStateView(UUID(0, 800), BattleFormat.DOUBLE, 1,
            // Deliberately reversed foe order: spread facts choose active-slot order, not list order.
            listOf(mon(1, BattleSide.ALLY, 0, 1.0, stage), mon(2, BattleSide.ALLY, 1, 1.0, 0),
                mon(4, BattleSide.OPPONENT, 1, 0.75, 0, redirector), mon(3, BattleSide.OPPONENT, 0, 0.25, 0)),
            BattleFieldStateView.empty(), BattleSide.entries.associateWith { 2 }, emptyList(), emptyList())
        val details = BattleMoveCandidateView(typeId = type, damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = 1_000.0, accuracy = accuracy, priority = 0, currentPp = 8, targetPattern = pattern)
        val catalog = BattlePublicActionCatalogView(state.pokemon.map {
            BattlePokemonActionCatalogView(it.battlePokemonId, if (it.battlePokemonId == UUID(0, 1)) listOf(
                BattlePublicMoveOptionView("overkill", details, BattlePublicMoveKnowledge.EXACT_OWN)) else listOf(
                BattlePublicMoveOptionView("splash", BattleMoveCandidateView(typeId = "normal",
                    damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0, accuracy = 100.0,
                    priority = 0, currentPp = 40, targetPattern = BattleMoveTargetPattern.SELF),
                    if (it.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED)),
                moveSetComplete = true)
        })
        val action = BattleActionCandidate("overkill", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "overkill", moveDetails = details,
            targets = if (pattern == BattleMoveTargetPattern.SELECTED_OPPONENT)
                listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)) else emptyList())
        return BattleDecisionContext(UUID(0, 801), state, listOf(action), Long.MAX_VALUE, publicActionCatalog = catalog)
    }

    private fun mon(id: Long, side: BattleSide, slot: Int, hp: Double, stage: Int, ability: String? = null) = BattlePokemonStateView(
        UUID(0, id), side, slot, "test:leaf_cap", null, 50, hp, null, mapOf("attack" to stage),
        emptySet(), ability, null, false, knownTypeIds = setOf("normal"),
        combatStats = BattleCombatStatRangesView(BattleIntegerRange(100, 100), BattleIntegerRange(200, 200),
            BattleIntegerRange(100, 100), BattleIntegerRange(200, 200), BattleIntegerRange(100, 100),
            BattleIntegerRange(100, 100), if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN
            else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))
}
