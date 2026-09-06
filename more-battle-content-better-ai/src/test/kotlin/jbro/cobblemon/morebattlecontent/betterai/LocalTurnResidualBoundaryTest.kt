package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalTurnResidualBoundaryTest {
    @Test
    fun `waiting preserves HP before poison while final state includes poison`() {
        val outcomes = project(1.0, "psn", attack = false)
        outcomes.forEach {
            assertEquals(1.0, foeHp(it.stateBeforeResidual), 1e-9)
            assertEquals(0.875, foeHp(it.state), 1e-9)
            assertEquals(2, it.stateBeforeResidual.turn)
            assertEquals(3, it.state.turn)
            assertTrue(it.directDamage.amounts.isEmpty(), "Poison is not damage from a submitted move")
        }
        assertEquals(1.0, outcomes.sumOf { it.probability * it.orderProbability }, 1e-9)
    }

    @Test
    fun `hit and miss retain different pre residual HP even when poison makes both final states faint`() {
        val outcomes = project(0.05, "psn", attack = true)
        assertEquals(1.0, outcomes.sumOf { it.probability * it.orderProbability }, 1e-9)
        outcomes.forEach { assertEquals(0.0, foeHp(it.state), 1e-9) }
        assertTrue(outcomes.any { foeHp(it.stateBeforeResidual) == 0.0 })
        assertTrue(outcomes.any { foeHp(it.stateBeforeResidual) == 0.05 })
        assertEquals(0.025, outcomes.sumOf {
            it.probability * it.orderProbability * foeHp(it.stateBeforeResidual)
        }, 1e-9, "Merging identical final states must not lose the miss branch's pre-residual HP")
    }

    @Test
    fun `without residual effects the two HP observations agree`() {
        project(1.0, null, attack = true).forEach {
            assertEquals(foeHp(it.state), foeHp(it.stateBeforeResidual), 1e-9)
        }
    }

    private fun foeHp(state: BattleStateView) = state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction

    @Test
    fun `berry recovery does not erase direct move damage`() {
        val plain = project(0.55, null, attack = true)
        val berry = project(0.55, null, attack = true, item = "sitrusberry")
        fun damage(outcomes: List<PublicTurnProjection>) = outcomes.sumOf {
            it.probability * it.orderProbability * it.directDamage.amounts.values.sum()
        }
        assertTrue(damage(plain) > 0.0)
        assertEquals(damage(plain), damage(berry), 1e-9)
        val netLoss = berry.sumOf {
            it.probability * it.orderProbability * (0.55 - foeHp(it.stateBeforeResidual))
        }
        assertTrue(netLoss < damage(berry), "The fixture must actually trigger berry recovery")
    }

    private fun project(hp: Double, status: String?, attack: Boolean, item: String? = null): List<PublicTurnProjection> {
        fun mon(side: BattleSide) = BattlePokemonStateView(
            UUID(0, 10L + side.ordinal), side, 0, "cobblemon:probe", null, 50,
            if (side == BattleSide.ALLY) 1.0 else hp, if (side == BattleSide.ALLY) null else status,
            emptyMap(), emptySet(), null, if (side == BattleSide.OPPONENT) item else null, false, knownTypeIds = setOf("normal"),
            combatStats = if (side == BattleSide.ALLY) BattleCombatStatRangesView.exact(200, 100, 100, 100, 100, 200)
                else publicExactStats(200, 100, 100, 100, 100, 50))
        val state = BattleStateView(UUID(0, 1), BattleFormat.SINGLE, 2, BattleSide.entries.map(::mon),
            BattleFieldStateView.empty(), BattleSide.entries.associateWith { 1 }, emptyList(), emptyList())
        val action = if (!attack) BattleActionCandidate("wait", BattleActionKind.WAIT) else
            BattleActionCandidate("hit", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
                moveId = "cobblemon:tackle", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0,
                    50.0, 0, 35, BattleMoveTargetPattern.SELECTED_OPPONENT,
                    effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), false)))
        val context = PublicBattleTacticalCalculator.calculate(BattleDecisionContext(UUID(0, 2), state,
            listOf(action), Long.MAX_VALUE))
        return PublicSingleTurnProjector.project(state, context.candidates.single(),
            BattleActionCandidate("opponent-wait", BattleActionKind.WAIT), context)
    }
}
