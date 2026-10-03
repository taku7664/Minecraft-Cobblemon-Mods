package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.search.LocalRecursiveLookaheadEvaluator
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory
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
        // Root retention without the unsearched median correction, which changes these losses on its own.
        val tuning = LocalDecisionTuning.CURRENT.copy(unsearchedTakeMedianAdjustment = false)
        val ranked = LocalBattleActionPolicy.rank(calculated, null, profile, tuning)
        fun evaluate(settings: LocalDecisionTuning) = LocalRecursiveLookaheadEvaluator.evaluate(
            ranked, calculated, profile, settings, clockMillis = { 0L })
        var narrowClockCalls = 0
        val narrow = LocalRecursiveLookaheadEvaluator.evaluate(ranked, calculated, profile, tuning,
            clockMillis = { narrowClockCalls++; 0L })
        val wide = evaluate(tuning.copy(maximumRootCandidates = Int.MAX_VALUE))
        CooperativeSearchComparison.verifyPoolRecovery("redirect-$redirectId-$drawerSlot-$stages-$probability", narrow,
            LocalRecursiveLookaheadEvaluator.evaluate(ranked, calculated, profile, tuning, clockMillis = { 0L },
                rootChoicePool = CooperativeSearchComparison::choicePool), wide)
        val mixing = CooperativeSearchComparison.verifyLeaderRecovery("redirect-$redirectId-$drawerSlot-$stages-$probability", narrow,
            evaluate(tuning.copy(revalidateUnsearchedRootLeaders = true)), wide)
        if (redirectId == "followme" && drawerSlot == 0 && expectedCoverage) {
            // Counterexample to adopting leader-only validation: a verified rank one does not
            // prevent the actual selector from overwhelmingly drawing unsearched alternatives.
            assertTrue(mixing.completePool)
            assertEquals(913, mixing.unsearchedDraws)
            assertTrue(mixing.observedUnsearchedMass > 0.85)
        }
        if (redirectId == "splash") {
            // With finished candidates discarded, a cut depth leaves the ranking untouched.
            val enabled = tuning.copy(revalidateUnsearchedRootLeaders = true, keepFinishedCandidates = false)
            val budget = LocalLookaheadBudgetPolicy.forTier(profile.difficulty.tier)
            // The normal root ends with two measurement calls. Leader validation replaces those
            // with two outer deadline checks; expire on its next work call, inside the extra probe.
            // Expiring on an outer check tests missing leader validation rather than a cut search.
            fun interruptedClock(): () -> Long {
                var calls = 0
                return { if (++calls <= narrowClockCalls) 0L else budget.timeMillis }
            }
            val interrupted = LocalRecursiveLookaheadEvaluator.evaluate(ranked, calculated, profile, enabled,
                clockMillis = interruptedClock(), budget = budget)
            assertTrue(interrupted.truncated, "Budget must include the additional leader probe")
            assertEquals(0, interrupted.depthCompleted)
            assertEquals(ranked, interrupted.ranked, "Discard the entire unfinished depth, not just the leader")
            assertTrue(interrupted.responseCoverageByAction.isEmpty())
            // Kept (the default), the candidates the cut first depth finished keep their search values: doubles
            // searches one depth only, so discarding it played the bare heuristic whenever a decision ran long.
            val kept = LocalRecursiveLookaheadEvaluator.evaluate(ranked, calculated, profile,
                enabled.copy(keepFinishedCandidates = true),
                clockMillis = interruptedClock(), budget = budget)
            assertTrue(kept.truncated)
            assertTrue(kept.partialDepthCandidates > 0, "The clock must interrupt after some root candidates finished")
            assertTrue(kept.responseCoverageByAction.isNotEmpty(), "The finished candidates keep their coverage")
            val interruptedPool = LocalRecursiveLookaheadEvaluator.evaluate(ranked, calculated, profile,
                LocalDecisionTuning.CURRENT.copy(keepFinishedCandidates = false),
                clockMillis = interruptedClock(), budget = budget,
                rootChoicePool = CooperativeSearchComparison::choicePool)
            assertTrue(interruptedPool.truncated)
            assertEquals(0, interruptedPool.depthCompleted)
            assertEquals(ranked, interruptedPool.ranked)
            assertTrue(interruptedPool.responseCoverageByAction.isEmpty())
            for (invalidPool in listOf(emptySet(), setOf("not-an-action"))) {
                assertThrows(IllegalArgumentException::class.java) {
                    LocalRecursiveLookaheadEvaluator.evaluate(ranked, calculated, profile, clockMillis = { 0L },
                        rootChoicePool = { invalidPool })
                }
            }
            val deeperProfile = profile.copy(difficulty = profile.difficulty.copy(lookaheadPlies = 2, foresightWeight = 0.0))
            // The two-turn doubles search, off in the shipped tuning, still has to recover what the narrow root lost.
            fun deeper(settings: LocalDecisionTuning, pool: Boolean = false) = LocalRecursiveLookaheadEvaluator.evaluate(
                ranked, calculated, deeperProfile, settings.copy(doublesSingleTurn = false), clockMillis = { 0L },
                budget = budget.copy(nodeLimit = 1_000_000),
                rootChoicePool = if (pool) CooperativeSearchComparison::choicePool else null)
            val repaired = deeper(enabled)
            val reference = deeper(enabled.copy(maximumRootCandidates = Int.MAX_VALUE))
            assertEquals(2, repaired.depthCompleted)
            CooperativeSearchComparison.verifyLeaderRecovery("splash-depth2-zero-future", narrow, repaired, reference)
            CooperativeSearchComparison.verifyPoolRecovery("splash-depth2-zero-future", narrow,
                deeper(tuning, pool = true), deeper(tuning.copy(maximumRootCandidates = Int.MAX_VALUE), pool = true))
            val firstPly = evaluate(enabled).ranked.associateBy { it.outcome.candidate.actionId }
            for (rank in repaired.ranked.filter { it.outcome.candidate.actionId in narrow.responseCoverageByAction }) {
                assertEquals(firstPly.getValue(rank.outcome.candidate.actionId).comparisonValue, rank.comparisonValue, 1e-9)
            }
        }
        val referenceLoss = CooperativeSearchComparison.verifyAndReport(
            "redirect-$redirectId-$drawerSlot-$stages-$probability", narrow, wide)
        // Characterize the current unsearched-Splash ranking defect; not a desired loss allowance.
        assertEquals(if (redirectId == "splash") 34.0 else 0.0, referenceLoss, 1e-9)
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
