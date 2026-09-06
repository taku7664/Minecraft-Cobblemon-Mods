package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalSituationalEvaluator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalScorer
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalFaintedTargetRetargetTest {
    @Test
    fun `second focused attack reaches surviving foe after first attack knocks out its target`() = checkRetarget()

    @Test
    fun `replacement immunity is calculated instead of reusing the fainted targets damage`() =
        checkRetarget(replacementTypes = setOf("ghost"))

    @Test
    fun `scripted target rules are not guessed from an opposing slot`() =
        checkRetarget(pattern = BattleMoveTargetPattern.SCRIPTED)

    @Test
    fun `a living selected foe is not replaced by the other foe`() = checkRetarget(targetHp = 1.0)

    @Test
    fun `uncertain second hit is not silently treated as a full duplicate credit`() = checkRetarget(accuracy = 50.0)

    @Test
    fun `joint score retains declared recoil adjustments without a knockout`() =
        checkRetarget(targetHp = 1.0, recoil = true)

    private fun checkRetarget(replacementTypes: Set<String> = setOf("normal"),
        pattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT, targetHp: Double = 0.01,
        accuracy: Double = 100.0, recoil: Boolean = false) {
        for (targetSlot in 0..1) {
            val allies = (0..1).map { mon(BattleSide.ALLY, it, 1.0, 200 - it * 50) }
            val foes = (0..1).map { mon(BattleSide.OPPONENT, it, if (it == targetSlot) targetHp else 1.0, 50,
                if (it == targetSlot) setOf("normal") else replacementTypes) }
            val state = BattleStateView(UUID(0, 1), BattleFormat.DOUBLE, 2, allies + foes,
                BattleFieldStateView.empty(), BattleSide.entries.associateWith { 2 }, emptyList(), emptyList())
            fun attack(slot: Int, target: Int) = BattleActionCandidate("hit-$slot-$target", BattleActionKind.USE_MOVE,
                actorSlot = slot, moveSlot = 0, moveId = "cobblemon:tackle",
                targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, target)),
                moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL,
                    40.0, if (slot == 0) 100.0 else accuracy, 0, 35,
                    if (slot == 0) BattleMoveTargetPattern.SELECTED_OPPONENT else pattern,
                    effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                        if (recoil) listOf(BattleMoveEffectView(BattleMoveEffectKind.MAX_HP_RECOIL,
                            BattleMoveEffectTarget.USER, 1.0, fractionRange = BattleFractionRange(0.1, 0.1)))
                        else emptyList(), false)))
            fun joint(secondTarget: Int): BattleActionCandidate {
                val parts = listOf(attack(0, targetSlot), attack(1, secondTarget))
                return BattleActionCandidate("joint-$secondTarget", BattleActionKind.COMPOSITE,
                    componentActionIds = parts.map { it.actionId }, componentActions = parts)
            }
            val context = PublicBattleTacticalCalculator.calculate(BattleDecisionContext(UUID(0, 2), state,
                listOf(joint(targetSlot), joint(1 - targetSlot)), Long.MAX_VALUE))
            val ranked = LocalBattleActionPolicy.rank(context, null, BattleTrainerProfile.balanced(2))
            ranked.forEach { rank ->
                val candidate = rank.outcome.candidate
                val expected = rank.outcome.componentOutcomes.sumOf { it.tacticalUtility } +
                    LocalTacticalSituationalEvaluator.compositeCoordinationAdjustment(candidate, context) -
                    LocalTacticalScorer.duplicateCertainKnockoutCredit(candidate, context)
                assertEquals(expected, rank.outcome.tacticalUtility, 1e-9,
                    "A joint must retain the mechanics adjustments already applied to its components")
                if (recoil) assertTrue(rank.outcome.componentOutcomes.any {
                    kotlin.math.abs(it.tacticalUtility - LocalTacticalScorer.score(it.candidate, context,
                        profile = BattleTrainerProfile.balanced(2))) > 1e-9
                }, "The recoil fixture must actually exercise a nonzero component adjustment")
            }
            fun project(secondTarget: Int): Pair<Double, Double> {
                val candidate = context.candidates.single { it.actionId == "joint-$secondTarget" }
                val outcomes = PublicSingleTurnProjector.project(state, candidate,
                    BattleActionCandidate("wait", BattleActionKind.WAIT), context)
                assertEquals(1.0, outcomes.sumOf { it.probability * it.orderProbability }, 1e-9)
                val damageByTarget = outcomes.flatMap { branch ->
                    branch.directDamage.amounts.map { (hit, damage) ->
                        assertTrue(allies.any { it.battlePokemonId == hit.actorId })
                        hit.targetId to damage * branch.probability * branch.orderProbability
                    }
                }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
                fun hp(id: UUID) = outcomes.sumOf { branch -> branch.probability * branch.orderProbability *
                    branch.state.pokemon.single { it.battlePokemonId == id }.hpFraction }
                foes.forEach { foe ->
                    assertEquals(foe.hpFraction - hp(foe.battlePokemonId),
                        damageByTarget[foe.battlePokemonId] ?: 0.0, 1e-9,
                        "Direct damage must follow the actual recipient, including retargets and misses")
                }
                return hp(foes[targetSlot].battlePokemonId) to hp(foes[1 - targetSlot].battlePokemonId)
            }
            val focused = project(targetSlot)
            val split = project(1 - targetSlot)
            if (targetHp < 1.0) assertEquals(0.0, focused.first, 1e-9)
            else assertTrue(focused.first > 0.0 && focused.first < split.first)
            if (replacementTypes == setOf("ghost") || pattern == BattleMoveTargetPattern.SCRIPTED)
                assertEquals(1.0, split.second, 1e-9)
            else assertTrue(split.second < 1.0)
            assertEquals(if (pattern == BattleMoveTargetPattern.SCRIPTED || targetHp == 1.0) 1.0 else split.second, focused.second, 1e-9,
                "A fainted opposing target must not silently discard the second attack")
            if (targetHp < 1.0 && replacementTypes == setOf("normal") &&
                pattern == BattleMoveTargetPattern.SELECTED_OPPONENT && accuracy == 100.0) {
                val focusedRank = ranked.single { it.outcome.candidate.actionId == "joint-$targetSlot" }
                val splitRank = ranked.single { it.outcome.candidate.actionId == "joint-${1 - targetSlot}" }
                assertEquals(splitRank.outcome.knockoutUtility, focusedRank.outcome.knockoutUtility, 1e-9,
                    "One certain fainted target must contribute one knockout credit")
                val coordinationDifference = LocalTacticalSituationalEvaluator.compositeCoordinationAdjustment(
                    focusedRank.outcome.candidate, context) - LocalTacticalSituationalEvaluator.compositeCoordinationAdjustment(
                    splitRank.outcome.candidate, context)
                val componentDifference = focusedRank.outcome.componentOutcomes.sumOf { it.tacticalUtility } -
                    splitRank.outcome.componentOutcomes.sumOf { it.tacticalUtility }
                val duplicateDifference = LocalTacticalScorer.duplicateCertainKnockoutCredit(focusedRank.outcome.candidate, context) -
                    LocalTacticalScorer.duplicateCertainKnockoutCredit(splitRank.outcome.candidate, context)
                assertEquals(componentDifference + coordinationDifference - duplicateDifference,
                    focusedRank.outcome.tacticalUtility - splitRank.outcome.tacticalUtility, 1e-9,
                    "Component mechanics, joint coordination and duplicate credit must each be applied once")
                // Baseline evidence, not a frozen expected score or a new scoring contract.
                ranked.forEach { rank ->
                    val candidate = rank.outcome.candidate
                    println("RETARGET_SCORE target=$targetSlot action=${candidate.actionId} " +
                        "focused=${candidate.actionId == "joint-$targetSlot"} " +
                        "rank=${rank.comparisonValue} tactical=${rank.outcome.tacticalUtility} " +
                        "ko=${rank.outcome.knockoutUtility} secureTargets=${rank.outcome.secureStandardKnockouts} " +
                        "damage=${rank.outcome.expectedDamageFraction} " +
                        "coordination=${LocalTacticalSituationalEvaluator.compositeCoordinationAdjustment(candidate, context)}")
                }
            }
            if (accuracy < 100.0) {
                context.candidates.forEach { assertEquals(0.0,
                    LocalTacticalScorer.duplicateCertainKnockoutCredit(it, context), 1e-9) }
            }
        }
    }

    private fun mon(side: BattleSide, slot: Int, hp: Double, speed: Int, types: Set<String> = setOf("normal")) = BattlePokemonStateView(
        UUID(0, (10 + side.ordinal * 2 + slot).toLong()), side, slot, "cobblemon:probe", null, 50,
        hp, null, emptyMap(), emptySet(), null, null, false, knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) BattleCombatStatRangesView.exact(200, 100, 100, 100, 100, speed)
        else publicExactStats(200, 100, 100, 100, 100, speed))
}
