package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.outcome.PublicActionOutcomeProjector
import jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.mcc.betterai.mechanics.LocalDirectHitMechanics
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.mechanics.LocalBattleStateFingerprint
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.mon
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.state
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.context
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.attack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalSubstituteEvaluationTest {
    @Test fun `a transferred decoy may exceed the recipients maximum body HP`() {
        val a = mon(BattleSide.ALLY, 0)
        val b = mon(BattleSide.OPPONENT, 0, volatiles = setOf("substitute"))
            .copyState(knownSubstituteHpFractionRange = BattleDamageFractionRange(1.5, 1.5))
        val hit = LocalDirectHitMechanics.apply(state(a, b), a.battlePokemonId, b.battlePokemonId, 1.0, emptyList(), false)
        assertEquals(0.5, hit.state.pokemon.single { it.side == BattleSide.OPPONENT }.knownSubstituteHpFractionRange!!.minimum, 1e-9)
        assertEquals(1.0, hit.state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction, 1e-9)
    }
    @Test fun `root and search both allow later multi hits to knock out the body`() {
        val s = state(mon(BattleSide.ALLY, 0), mon(BattleSide.OPPONENT, 0, hp = 0.1, volatiles = setOf("substitute"))
            .copyState(knownSubstituteHpFractionRange = BattleDamageFractionRange(0.05, 0.05)))
        val effect = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            listOf(BattleMoveEffectView(BattleMoveEffectKind.MULTI_HIT, BattleMoveEffectTarget.SELECTED_TARGET,
                amountRange = BattleIntegerRange(2, 2))), scriptedBehavior = false)
        val move = attack(power = 60.0, effects = effect)
        val ctx = PublicBattleTacticalCalculator.calculate(context(s, move))
        assertTrue(LocalTacticalScorer.knockoutUtility(ctx.candidates.single(), context = ctx) > 0.0)
        val branches = PublicSingleTurnProjector.project(s, move, BattleActionCandidate("wait", BattleActionKind.WAIT), ctx)
        assertTrue(branches.all { it.stateBeforeResidual.pokemon.single { p -> p.side == BattleSide.OPPONENT }.fainted })
    }
    @Test fun `weak repeated hits consume known decoy HP without overflowing to the body`() {
        val a = mon(BattleSide.ALLY, 0)
        val b = mon(BattleSide.OPPONENT, 0, volatiles = setOf("substitute"))
            .copyState(knownSubstituteHpFractionRange = BattleDamageFractionRange(0.25, 0.25))
        var s = state(a, b)
        repeat(2) {
            s = LocalDirectHitMechanics.apply(s, a.battlePokemonId, b.battlePokemonId, 0.1, emptyList(), false).state
            assertTrue("substitute" in s.pokemon.single { it.side == BattleSide.OPPONENT }.knownVolatileEffectIds)
        }
        assertEquals(0.05, s.pokemon.single { it.side == BattleSide.OPPONENT }.knownSubstituteHpFractionRange!!.maximum, 1e-9)
        s = LocalDirectHitMechanics.apply(s, a.battlePokemonId, b.battlePokemonId, 0.1, emptyList(), false).state
        assertEquals(1.0, s.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction, 1e-9)
        assertFalse("substitute" in s.pokemon.single { it.side == BattleSide.OPPONENT }.knownVolatileEffectIds)
    }
    @Test fun `later hits in a multi hit move reach the body after the breaking hit`() {
        val a = mon(BattleSide.ALLY, 0)
        val b = mon(BattleSide.OPPONENT, 0, volatiles = setOf("substitute"))
            .copyState(knownSubstituteHpFractionRange = BattleDamageFractionRange(0.25, 0.25))
        val hit = LocalDirectHitMechanics.apply(state(a, b), a.battlePokemonId, b.battlePokemonId, 0.9, emptyList(), false, hitCount = 3)
        assertEquals(0.4, hit.state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction, 1e-9)
        assertEquals(0.85, hit.moveDamageFraction, 1e-9)
    }
    @Test fun `an unobserved decoy can survive or break a weak hit`() {
        val a = mon(BattleSide.ALLY, 0)
        val b = mon(BattleSide.OPPONENT, 0, volatiles = setOf("substitute"))
        val s = state(a, b); val move = attack(power = 20.0)
        val branches = PublicSingleTurnProjector.project(s, move, BattleActionCandidate("wait", BattleActionKind.WAIT), context(s, move))
        val broken = branches.filter { "substitute" !in it.stateBeforeResidual.pokemon.single { p -> p.side == BattleSide.OPPONENT }.knownVolatileEffectIds }
            .sumOf { it.probability * it.orderProbability }
        assertTrue(broken > 0.0 && broken < 1.0, "broken probability=$broken; raw=" +
            jbro.cobblemon.mcc.betterai.calculation.PublicMoveOutcomeBranchProjector.project(move, context(s, move), BattleSide.ALLY) +
            "; states=" + branches.map { it.stateBeforeResidual.pokemon.single { p -> p.side == BattleSide.OPPONENT }.knownVolatileEffectIds })
        assertEquals(1.0, branches.sumOf { it.probability * it.orderProbability }, 1e-9)
        assertTrue(branches.all { it.stateBeforeResidual.pokemon.single { p -> p.side == BattleSide.OPPONENT }.hpFraction == 1.0 })
    }
    @Test fun `decoy HP survives copies and gives different cache keys`() {
        val p = mon(BattleSide.OPPONENT, 0, volatiles = setOf("substitute"))
            .copyState(knownSubstituteHpFractionRange = BattleDamageFractionRange(0.1, 0.1))
        assertEquals(p.knownSubstituteHpFractionRange, p.copyState(hpFraction = 0.5).knownSubstituteHpFractionRange)
        assertNull(p.copyState(activeSlot = null).knownSubstituteHpFractionRange)
        val cache = LocalBattleStateFingerprint()
        assertNotEquals(cache.of(state(p)), cache.of(state(p.copyState(knownSubstituteHpFractionRange = BattleDamageFractionRange(0.2, 0.2)))))
    }
    @Test fun `the old volatile and Stellar constructor ABIs remain available`() {
        val constructors = BattlePokemonStateView::class.java.declaredConstructors
        for (count in listOf(17, 18, 20, 21, 23)) assertTrue(constructors.any { it.parameterCount == count }, "missing constructor $count")
    }
    @Test fun `a single hit into Substitute earns no body damage or knockout credit`() {
        val s = state(mon(BattleSide.ALLY, 0), mon(BattleSide.OPPONENT, 0, hp = 0.1, volatiles = setOf("substitute")))
        val ctx = PublicBattleTacticalCalculator.calculate(context(s, attack(power = 120.0)))
        val action = ctx.candidates.single()
        assertEquals(0.0, PublicActionOutcomeProjector.project(action, ctx).expectedDamageFraction!!, 1e-9)
        assertEquals(0.0, LocalTacticalScorer.knockoutUtility(action, context = ctx), 1e-9)
    }

    @Test fun `drain is based on damage to the decoy even when the body is untouched`() {
        val a = mon(BattleSide.ALLY, 0, hp = 0.5)
        val b = mon(BattleSide.OPPONENT, 0, volatiles = setOf("substitute"))
        val effect = BattleMoveEffectView(BattleMoveEffectKind.DRAIN_FRACTION, BattleMoveEffectTarget.USER,
            probability = 1.0, fractionRange = BattleFractionRange(0.5, 0.5))
        val hit = LocalDirectHitMechanics.apply(state(a, b), a.battlePokemonId, b.battlePokemonId, 0.1, listOf(effect), false)
        assertEquals(1.0, hit.state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction, 1e-9)
        assertTrue(hit.state.pokemon.single { it.side == BattleSide.ALLY }.hpFraction > 0.5)
    }

    @Test fun `bypassing the decoy still reaches the body`() {
        val a = mon(BattleSide.ALLY, 0); val b = mon(BattleSide.OPPONENT, 0, volatiles = setOf("substitute"))
        val hit = LocalDirectHitMechanics.apply(state(a, b), a.battlePokemonId, b.battlePokemonId, 0.1, emptyList(), false, true)
        assertEquals(0.9, hit.state.pokemon.single { it.side == BattleSide.OPPONENT }.hpFraction, 1e-9)
        assertTrue("substitute" in hit.state.pokemon.single { it.side == BattleSide.OPPONENT }.knownVolatileEffectIds)
    }
}
