package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Mechanical fixture, not a legal preset team or a population strength benchmark. */
class LocalCooperativeCandidateCoverageTest {
    @Test
    fun `a functioning redirect setup joint reaches root search`() {
        checkCoverage()
    }

    @Test
    fun `rage powder also retains a setup joint`() {
        checkCoverage(redirectId = "ragepowder")
    }

    @Test
    fun `redirection works from slot one with canonical component order`() {
        checkCoverage(drawerSlot = 1)
        checkCoverage(redirectId = "ragepowder", drawerSlot = 1)
    }

    @Test
    fun `ordinary status does not reserve a cooperative search slot`() {
        checkCoverage(redirectId = "splash", expectedCoverage = false)
    }

    @Test
    fun `non improving and zero probability effects do not reserve a slot`() {
        checkCoverage(stages = mapOf("attack" to -1), expectedCoverage = false)
        checkCoverage(probability = 0.0, expectedCoverage = false)
        checkCoverage(probability = null, expectedCoverage = false)
    }

    private fun checkCoverage(
        redirectId: String = "followme", stages: Map<String, Int> = mapOf("attack" to 2),
        probability: Double? = 1.0, expectedCoverage: Boolean = true, drawerSlot: Int = 0,
    ) {
        val partnerSlot = 1 - drawerSlot
        val drawer = mon(BattleSide.ALLY, drawerSlot, 1.0, 300, 200)
        val partner = mon(BattleSide.ALLY, partnerSlot, 0.3, 150, 50)
        val opponents = listOf(mon(BattleSide.OPPONENT, 0, 1.0, 200, 100),
            mon(BattleSide.OPPONENT, 1, 1.0, 200, 100))
        val follow = status(redirectId, drawerSlot, 2)
        val setup = status("swordsdance", partnerSlot, 0, stages, probability)
        val own = listOf(follow) + attacks(drawerSlot)
        val other = listOf(setup) + attacks(partnerSlot)
        val joints = own.flatMap { first -> other.map { second -> joint(first, second) } }
        val state = BattleStateView(UUID(0, 1), BattleFormat.DOUBLE, 2,
            listOf(drawer, partner) + opponents, BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 2 }, emptyList(), emptyList())
        val knownAttack = attack(0, 1, 0)
        val catalog = BattlePublicActionCatalogView(opponents.map {
            BattlePokemonActionCatalogView(it.battlePokemonId,
                listOf(BattlePublicMoveOptionView(knownAttack.moveId!!, knownAttack.moveDetails!!,
                    BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), moveSetComplete = true)
        })
        val context = BattleDecisionContext(UUID(0, 2), state, joints, Long.MAX_VALUE,
            publicActionCatalog = catalog)
        val cooperative = joint(follow, setup)
        val exposed = joint(own[1], setup)
        val reply = attack(0, partnerSlot, 0, BattleSide.ALLY)
        fun partnerHp(action: BattleActionCandidate): Double = PublicSingleTurnProjector.project(
            state, action, reply, context, RecursiveActionHistory()).sumOf { branch ->
            branch.probability * branch.orderProbability *
                branch.state.pokemon.single { it.battlePokemonId == partner.battlePokemonId }.hpFraction
        }
        if (redirectId != "splash") {
            assertTrue(partnerHp(cooperative) > partnerHp(exposed), "Redirection must demonstrably protect the setup user")
        }
        if (expectedCoverage) {
            val branches = PublicSingleTurnProjector.project(state, cooperative, reply, context, RecursiveActionHistory())
            assertTrue(branches.isNotEmpty())
            assertTrue(branches.all { branch ->
                branch.state.pokemon.single { it.battlePokemonId == partner.battlePokemonId }.statStages["attack"] == 2
            }, "The protected partner must actually complete its setup")
        }
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        val profile = BattleTrainerProfile.balanced(2).let {
            it.copy(difficulty = it.difficulty.copy(lookaheadPlies = 1))
        }
        val tuning = LocalDecisionTuning.CURRENT
        val ranked = LocalBattleActionPolicy.rank(calculated, null, profile, tuning)
        fun evaluate(settings: LocalDecisionTuning) = LocalRecursiveLookaheadEvaluator.evaluate(
            ranked, calculated, profile, settings, clockMillis = { 0L })
        val narrow = evaluate(tuning)
        val wide = evaluate(tuning.copy(maximumRootCandidates = Int.MAX_VALUE))
        val referenceLoss = CooperativeSearchComparison.verifyAndReport(
            "redirect-$redirectId-$drawerSlot-$stages-$probability", narrow, wide)
        // Characterize the current unsearched-Splash ranking defect; not a desired loss allowance.
        assertEquals(if (redirectId == "splash") 31.5 else 0.0, referenceLoss, 1e-9)
        assertFalse(narrow.truncated)
        assertFalse(wide.truncated)
        assertEquals(1, narrow.depthCompleted)
        assertEquals(1, wide.depthCompleted)
        assertEquals(joints.size, wide.responseCoverageByAction.size)
        assertTrue(cooperative.actionId in wide.responseCoverageByAction)
        assertEquals(expectedCoverage, cooperative.actionId in narrow.responseCoverageByAction,
            "Only a declared redirect plus positive self setup receives the reserved search slot")
        assertTrue(narrow.responseCoverageByAction.size <= tuning.maximumRootActionsPerSlot * tuning.maximumRootActionsPerSlot + 1)
        println("COOPERATIVE_ROOT slot=$drawerSlot candidates=${joints.size} narrow=${narrow.responseCoverageByAction.size} " +
            "wide=${wide.responseCoverageByAction.size} redirectSetupNarrow=${cooperative.actionId in narrow.responseCoverageByAction} " +
            "baseRank=${ranked.indexOfFirst { it.outcome.candidate.actionId == cooperative.actionId } + 1} " +
            "narrowNodes=${narrow.nodesVisited} wideNodes=${wide.nodesVisited} " +
            "narrowChoice=${narrow.ranked.first().outcome.candidate.actionId} wideChoice=${wide.ranked.first().outcome.candidate.actionId}")
    }

    private fun joint(a: BattleActionCandidate, b: BattleActionCandidate): BattleActionCandidate {
        val parts = listOf(a, b).sortedBy { it.actorSlot }
        return BattleActionCandidate(parts.joinToString("+") { it.actionId }, BattleActionKind.COMPOSITE,
            componentActionIds = parts.map { it.actionId }, componentActions = parts)
    }

    private fun attacks(slot: Int) = (0..2).flatMap { move -> (0..1).map { target -> attack(slot, target, move) } }

    private fun attack(slot: Int, target: Int, move: Int, side: BattleSide = BattleSide.OPPONENT) = BattleActionCandidate(
        actionId = "hit-$slot-$move-$target", kind = BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = move + 1,
        moveId = "cobblemon:probe$move", targets = listOf(BattleTargetSlot(side, target)),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL,
            70.0 + move * 10, 100.0, 0, 10, BattleMoveTargetPattern.SELECTED_OPPONENT))

    private fun status(id: String, slot: Int, priority: Int, stages: Map<String, Int> = emptyMap(),
        probability: Double? = 1.0) = BattleActionCandidate(
        actionId = "$id-$slot", kind = BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = 0,
        moveId = "cobblemon:$id", moveDetails = BattleMoveCandidateView(
            "normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, priority, 10, BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                stages.takeIf { it.isNotEmpty() }?.let { listOf(BattleMoveEffectView(
                    BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.USER, probability, statStages = it)) } ?: emptyList(), false)))

    private fun mon(side: BattleSide, slot: Int, hp: Double, maxHp: Int, defense: Int) = BattlePokemonStateView(
        battlePokemonId = UUID(0, (10 + side.ordinal * 2 + slot).toLong()), side = side, activeSlot = slot,
        speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = hp, statusId = null,
        statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null, knownHeldItemId = null,
        fainted = false, knownTypeIds = setOf("normal"), combatStats =
            if (side == BattleSide.ALLY) BattleCombatStatRangesView.exact(maxHp, 100, defense, 100, defense, 100)
            else publicExactStats(maxHp, 140, defense, 100, defense, 120))
}
