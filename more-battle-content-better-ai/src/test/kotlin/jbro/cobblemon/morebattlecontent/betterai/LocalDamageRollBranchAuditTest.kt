package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Regression checks promoted from the roll/KO attribution diagnostic baseline. */
class LocalDamageRollBranchAuditTest {
    @Test
    fun `audit roll dependent knockout and second attack recipient in both opposing slots`() {
        for (slot in 0..1) {
            val full = context(slot, 1.0, joint = false)
            val rolls = requireNotNull(PublicBattleTacticalCalculator.conservativeDamageRollFractions(
                full.candidates.single(), full, BattleSide.ALLY))
            for (hp in rolls.distinct().sorted().drop(1)) {
                val knockoutMass = rolls.count { it >= hp }.toDouble() / rolls.size
                assertTrue(knockoutMass > 0.0 && knockoutMass < 1.0, "The first hit must have both KO and survival rolls")
                val mixed = context(slot, hp, joint = true)
                val results = project(mixed)
                val otherTarget = monId(BattleSide.OPPONENT, 1 - slot)
                val secondActor = monId(BattleSide.ALLY, 1)
                val observedRetargetMass = results.filter { outcome ->
                    outcome.directDamage.amounts.any { (hit, damage) ->
                        hit.actorId == secondActor && hit.targetId == otherTarget && damage > 0.0
                    }
                }.sumOf { it.probability * it.orderProbability }
                // Both attacks are certain-accuracy ordinary hits, the second actor is slower, and the
                // other foe is healthy and non-immune. First-hit KO rolls therefore require retargeting.
                assertEquals(2, mixed.candidates.single().componentActions.size)
                assertTrue(results.all { it.actionOrderPokemonIds == listOf(monId(BattleSide.ALLY, 0), secondActor) })
                assertEquals(knockoutMass, observedRetargetMass, 1e-9,
                    "First-hit KO rolls must preserve the branch where the second hit changes recipient")
                println("ROLL_RETARGET_AUDIT slot=$slot hp=$hp rolls=$rolls " +
                    "requiredMass=$knockoutMass observedMass=$observedRetargetMass branches=${results.size}")
            }
        }
    }

    @Test
    fun `audit material delta and extra KO adjustment for a guaranteed knockout`() {
        val context = context(0, 0.01, joint = false)
        val results = project(context)
        assertTrue(results.all { it.state.pokemon.single { mon -> mon.battlePokemonId == monId(BattleSide.OPPONENT, 0) }.fainted })
        val material = results.sumOf {
            it.probability * it.orderProbability * LocalImmediateTurnScorer.score(context.state, it.state).materialDelta
        }
        val adjustment = results.sumOf { it.probability * it.orderProbability * it.expectedScoreAdjustment }
        val hpDamage = results.sumOf { it.probability * it.orderProbability * it.directDamage.amounts.values.sum() }
        assertEquals(0.01, hpDamage, 1e-9)
        assertEquals(2.01, material, 1e-9, "Board material already includes the removed Pokemon's two living-value units")
        assertEquals(0.0, adjustment, 1e-9, "A KO represented in the board must not be credited again")
        println("KO_MATERIAL_AUDIT damage=$hpDamage boardDelta=$material extraAdjustment=$adjustment total=${material + adjustment}")
    }

    private fun project(context: BattleDecisionContext) = PublicSingleTurnProjector.project(
        context.state, context.candidates.single(), BattleActionCandidate("wait", BattleActionKind.WAIT), context,
    ).also { assertEquals(1.0, it.sumOf { branch -> branch.probability * branch.orderProbability }, 1e-9) }

    @Test
    fun `one hit survival does not receive raw roll knockout material`() {
        for ((item, ability) in listOf("focussash" to null, null to "sturdy")) {
            val context = context(0, 1.0, joint = false, item = item, ability = ability, power = 1000.0)
            project(context).forEach { outcome ->
                val target = outcome.state.pokemon.single { it.battlePokemonId == monId(BattleSide.OPPONENT, 0) }
                assertFalse(target.fainted)
                assertEquals(0.005, target.hpFraction, 1e-9)
                assertEquals(0.0, outcome.expectedScoreAdjustment, 1e-9)
                assertEquals(0.995, LocalImmediateTurnScorer.score(context.state, outcome.state).materialDelta, 1e-9)
            }
        }
    }

    @Test
    fun `first move miss probability multiplies the roll dependent retarget probability`() {
        val context = context(0, 0.125, joint = true, firstAccuracy = 50.0)
        val mass = project(context).filter { outcome -> outcome.directDamage.amounts.any { (hit, damage) ->
            hit.actorId == monId(BattleSide.ALLY, 1) && hit.targetId == monId(BattleSide.OPPONENT, 1) && damage > 0.0
        } }.sumOf { it.probability * it.orderProbability }
        assertEquals(0.5 * 11.0 / 16.0, mass, 1e-9)
    }

    private fun context(targetSlot: Int, hp: Double, joint: Boolean, item: String? = null,
        ability: String? = null, power: Double = 40.0, firstAccuracy: Double = 100.0): BattleDecisionContext {
        val pokemon = BattleSide.entries.flatMap { side -> (0..1).map { slot ->
            BattlePokemonStateView(monId(side, slot), side, slot, "cobblemon:probe", null, 50,
                if (side == BattleSide.OPPONENT && slot == targetSlot) hp else 1.0,
                null, emptyMap(), emptySet(), if (side == BattleSide.OPPONENT) ability else null,
                if (side == BattleSide.OPPONENT) item else null, false, knownTypeIds = setOf("normal"),
                combatStats = if (side == BattleSide.ALLY)
                    BattleCombatStatRangesView.exact(200, 100, 100, 100, 100, 200 - slot * 50)
                else publicExactStats(200, 100, 100, 100, 100, 50))
        } }
        val state = BattleStateView(UUID(0, 1), BattleFormat.DOUBLE, 2, pokemon, BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 2 }, emptyList(), emptyList())
        fun attack(slot: Int) = BattleActionCandidate("hit-$slot", BattleActionKind.USE_MOVE,
            actorSlot = slot, moveSlot = 0, moveId = "cobblemon:tackle",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, targetSlot)),
            moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, power,
                if (slot == 0) firstAccuracy else 100.0,
                0, 35, BattleMoveTargetPattern.SELECTED_OPPONENT,
                effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), false)))
        val parts = listOf(attack(0), attack(1))
        val action = if (joint) BattleActionCandidate("joint", BattleActionKind.COMPOSITE,
            componentActionIds = parts.map { it.actionId }, componentActions = parts) else parts.first()
        return PublicBattleTacticalCalculator.calculate(BattleDecisionContext(UUID(0, 2), state, listOf(action), Long.MAX_VALUE))
    }

    private fun monId(side: BattleSide, slot: Int) = UUID(0, 10L + side.ordinal * 2 + slot)
}
