package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.outcome.PublicActionOutcomeProjector
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.mon
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.state
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.context
import jbro.cobblemon.mcc.betterai.LocalEvaluationRegressionFixture.attack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalConditionalDamageAbilitiesTest {
    @Test fun `the root credits a Sniper critical knockout that an ordinary hit cannot reach`() {
        val s = state(mon(BattleSide.ALLY, 0, ability = "sniper"), mon(BattleSide.OPPONENT, 0, hp = 0.4))
        val ctx = PublicBattleTacticalCalculator.calculate(context(s, attack(power = 50.0)))
        assertEquals(jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.CURRENT.knockoutMaterialScore / 24.0,
            jbro.cobblemon.mcc.betterai.evaluation.LocalTacticalScorer.knockoutUtility(ctx.candidates.single(), context = ctx), 1e-9)
    }
    @Test fun `Sniper random critical hits remain possible in the compact chance model`() {
        val s = state(mon(BattleSide.ALLY, 0, ability = "sniper"), mon(BattleSide.OPPONENT, 0))
        val move = attack(power = 50.0)
        val ctx = PublicBattleTacticalCalculator.calculate(context(s, move))
        val branches = jbro.cobblemon.mcc.betterai.calculation.PublicMoveOutcomeBranchProjector.project(ctx.candidates.single(), ctx, BattleSide.ALLY)
        assertEquals(1.0 / 24.0, branches.filter { it.damageFraction > 0.4 }.sumOf { it.probability }, 1e-9)
    }
    @Test fun `Stakeout boosts the hit into a foe who switched in this turn`() {
        fun switchedDamage(ability: String?): Double {
            val incoming = mon(BattleSide.OPPONENT, null)
            val s = state(mon(BattleSide.ALLY, 0, ability = ability), mon(BattleSide.OPPONENT, 0), incoming)
            val move = attack(power = 40.0)
            val switch = BattleActionCandidate("switch", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = incoming.battlePokemonId)
            return PublicSingleTurnProjector.project(s, move, switch, context(s, move)).sumOf { branch ->
                branch.probability * branch.orderProbability * (1.0 - branch.stateBeforeResidual.pokemon.single {
                    it.battlePokemonId == incoming.battlePokemonId }.hpFraction)
            }
        }
        assertEquals(2.0, switchedDamage("stakeout") / switchedDamage(null), 0.02)
        assertEquals(1.0, damage("stakeout") / damage(null), 1e-9)
    }
    @Test fun `Sniper adds its multiplier only to a critical hit`() {
        assertEquals(1.5, damage("sniper", critical = true) / damage(null, critical = true), 0.03)
    }
    @Test fun `Battle Armor prevents the guaranteed critical multiplier and Sniper boost`() {
        assertEquals(damage(null, targetAbility = "battlearmor"),
            damage("sniper", critical = true, targetAbility = "battlearmor"), 1e-9)
    }
    @Test fun `Analytic values moving after the foe`() {
        assertEquals(1.3, damage("analytic", speed = 50) / damage(null, speed = 50), 0.02)
        assertEquals(1.0, damage("analytic", speed = 200) / damage(null, speed = 200), 1e-9)
    }
    @Test fun `Analytic uses the simulated queue when the other slot waits`() {
        val a = mon(BattleSide.ALLY, 0, ability = "analytic", speed = 200)
        val b = mon(BattleSide.OPPONENT, 0)
        val s = state(a, b); val move = attack(power = 50.0)
        val branches = PublicSingleTurnProjector.project(s, move, BattleActionCandidate("wait", BattleActionKind.WAIT), context(s, move))
        val damage = branches.sumOf { it.probability * it.orderProbability *
            (1.0 - it.stateBeforeResidual.pokemon.single { p -> p.battlePokemonId == b.battlePokemonId }.hpFraction) }
        assertTrue(damage > this.damage(null, speed = 200, power = 50.0) * 1.15)
    }
    private fun damage(ability: String?, critical: Boolean = false, speed: Int = 100,
        targetAbility: String? = null, power: Double = 50.0): Double {
        val s = state(mon(BattleSide.ALLY, 0, ability = ability, speed = speed), mon(BattleSide.OPPONENT, 0, ability = targetAbility))
        val effect = if (critical) BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            listOf(BattleMoveEffectView(BattleMoveEffectKind.ALWAYS_CRITICAL, BattleMoveEffectTarget.SELECTED_TARGET, 1.0)),
            scriptedBehavior = false) else null
        val move = attack(power = power, effects = effect)
        val ctx = PublicBattleTacticalCalculator.calculate(context(s, move))
        return requireNotNull(PublicActionOutcomeProjector.project(ctx.candidates.single(), ctx).expectedDamageFraction)
    }
}
