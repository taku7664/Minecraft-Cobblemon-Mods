package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.morebattlecontent.betterai.search.LocalCooperativeRootRetention
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Deliberately weak spread attack probes retention, not optimality or legal preset strength. */
class LocalImmuneSpreadCandidateTest {
    @Test
    fun `an immune partner spread joint survives per slot narrowing`() = checkCoverage(setOf("flying"), true)

    @Test
    fun `a vulnerable partner does not receive the immunity reservation`() = checkCoverage(setOf("normal"), false)

    @Test
    fun `unknown partner typing is not treated as immunity`() = checkCoverage(emptySet(), false)

    @Test
    fun `a known levitate partner can also retain the joint`() = checkCoverage(setOf("normal"), true, "cobblemon:levitate")

    @Test
    fun `one remaining foe uses primary damage facts instead of an absent spread list`() =
        checkCoverage(setOf("flying"), true, opponentCount = 1)

    private fun checkCoverage(partnerTypes: Set<String>, expected: Boolean, partnerAbility: String? = null, opponentCount: Int = 2) {
        val allies = listOf(mon(BattleSide.ALLY, 0, setOf("normal")), mon(BattleSide.ALLY, 1, partnerTypes, partnerAbility))
        val foes = (0 until opponentCount).map { mon(BattleSide.OPPONENT, it, setOf("normal")) }
        val spread = BattleActionCandidate("spread", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:earthquake", moveDetails = BattleMoveCandidateView(
                "ground", BattleMoveDamageCategory.PHYSICAL, 20.0, 100.0, 0, 10, BattleMoveTargetPattern.ALL_ADJACENT))
        fun attacks(slot: Int) = (0..2).flatMap { move -> (0 until opponentCount).map { target -> attack(slot, target, move) } }
        val joints = (listOf(spread) + attacks(0)).flatMap { a -> attacks(1).map { b ->
            BattleActionCandidate("${a.actionId}+${b.actionId}", BattleActionKind.COMPOSITE,
                componentActionIds = listOf(a.actionId, b.actionId), componentActions = listOf(a, b))
        } }
        val state = BattleStateView(UUID(0, 1), BattleFormat.DOUBLE, 2, allies + foes,
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to allies.size, BattleSide.OPPONENT to foes.size), emptyList(), emptyList())
        val option = attack(0, 0, 0)
        val context = BattleDecisionContext(UUID(0, 2), state, joints, Long.MAX_VALUE,
            publicActionCatalog = BattlePublicActionCatalogView(foes.map {
                BattlePokemonActionCatalogView(it.battlePokemonId, listOf(BattlePublicMoveOptionView(
                    option.moveId!!, option.moveDetails!!, BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), true)
            }))
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        val profile = BattleTrainerProfile.balanced(2).let { it.copy(difficulty = it.difficulty.copy(lookaheadPlies = 1)) }
        val ranked = LocalBattleActionPolicy.rank(calculated, null, profile)
        val cooperative = ranked.first { it.outcome.candidate.componentActions.first().actionId == "spread" }.outcome.candidate
        val targets = cooperative.componentActions.first().facts!!.spreadTargets
        assertEquals(if (opponentCount == 1) 0 else opponentCount, targets.size)
        assertTrue(targets.all { it.side == BattleSide.OPPONENT })
        assertTrue(targets.filter { it.side == BattleSide.OPPONENT }.all { it.standardDamageFractionRange!!.minimum > 0.0 })
        val idle = BattleActionCandidate("idle", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:splash", moveDetails = BattleMoveCandidateView(
                "normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10, BattleMoveTargetPattern.SELF))
        if (partnerTypes.isNotEmpty()) {
            val projected = PublicSingleTurnProjector.project(state, cooperative, idle, calculated, RecursiveActionHistory())
            assertTrue(projected.isNotEmpty())
            assertEquals(1.0, projected.sumOf { it.probability * it.orderProbability }, 1e-9)
            val hp = projected.sumOf { it.probability * it.orderProbability *
                it.state.pokemon.single { mon -> mon.battlePokemonId == allies[1].battlePokemonId }.hpFraction }
            if (expected) assertEquals(1.0, hp, 1e-9) else assertTrue(hp < 1.0)
        }
        fun evaluate(tuning: LocalDecisionTuning) = LocalRecursiveLookaheadEvaluator.evaluate(
            ranked, calculated, profile, tuning, clockMillis = { 0L })
        val narrow = evaluate(LocalDecisionTuning.CURRENT)
        val wide = evaluate(LocalDecisionTuning.CURRENT.copy(maximumRootCandidates = Int.MAX_VALUE))
        assertEquals(expected, cooperative.actionId in LocalCooperativeRootRetention.select(ranked, calculated))
        assertFalse(narrow.truncated)
        assertFalse(wide.truncated)
        assertEquals(joints.size, wide.responseCoverageByAction.size)
        assertEquals(expected, cooperative.actionId in narrow.responseCoverageByAction,
            "Only publicly zero ally damage may reserve a spread joint")
        assertTrue(narrow.responseCoverageByAction.size <= LocalDecisionTuning.CURRENT.maximumRootActionsPerSlot *
            LocalDecisionTuning.CURRENT.maximumRootActionsPerSlot + 2)
        println("IMMUNE_SPREAD types=$partnerTypes ability=$partnerAbility candidates=${joints.size} kept=${narrow.responseCoverageByAction.size} " +
            "nodes=${narrow.nodesVisited} wideNodes=${wide.nodesVisited} baseRank=${ranked.indexOfFirst { it.outcome.candidate.actionId == cooperative.actionId } + 1} " +
            "narrowChoice=${narrow.ranked.first().outcome.candidate.actionId} wideChoice=${wide.ranked.first().outcome.candidate.actionId}")
    }

    private fun attack(slot: Int, target: Int, move: Int) = BattleActionCandidate(
        "hit-$slot-$move-$target", BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = move + 1,
        moveId = "cobblemon:probe$move", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, target)),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL,
            70.0 + move * 10, 100.0, 0, 10, BattleMoveTargetPattern.SELECTED_OPPONENT))

    private fun mon(side: BattleSide, slot: Int, types: Set<String>, ability: String? = null) = BattlePokemonStateView(
        battlePokemonId = UUID(0, (10 + side.ordinal * 2 + slot).toLong()), side = side, activeSlot = slot,
        speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = 1.0, statusId = null,
        statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = ability, knownHeldItemId = null,
        fainted = false, knownTypeIds = types, combatStats = if (side == BattleSide.ALLY)
            BattleCombatStatRangesView.exact(200, 100, 100, 100, 100, 100)
        else publicExactStats(200, 100, 100, 100, 100, 90))
}
