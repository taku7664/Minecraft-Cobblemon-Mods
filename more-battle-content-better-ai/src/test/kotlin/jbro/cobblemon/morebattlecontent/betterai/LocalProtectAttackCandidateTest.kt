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

/** Explicit mechanical fixture; not a legal preset or a strength benchmark. */
class LocalProtectAttackCandidateTest {
    @Test
    fun `protect plus a partner attack reaches search`() {
        checkCoverage(100)
    }

    @Test
    fun `protect is still considered when attacking is more attractive initially`() {
        checkCoverage(180)
    }

    @Test
    fun `Protect works from slot one with canonical component order`() {
        checkCoverage(180, protectorSlot = 1)
    }

    @Test
    fun `omitted probability follows the existing unconditional Protect declaration`() {
        checkCoverage(180, probability = null)
    }

    @Test
    fun `other protection names and absent or inactive effects are not adopted`() {
        checkCoverage(180, protectionMoveId = "endure", expectedReservation = false)
        checkCoverage(180, declared = false, expectedReservation = false)
        checkCoverage(180, probability = 0.0, expectedReservation = false)
    }

    private fun checkCoverage(attackStat: Int, protectionMoveId: String = "protect", declared: Boolean = true,
        probability: Double? = 1.0, expectedReservation: Boolean = true, protectorSlot: Int = 0) {
        val partnerSlot = 1 - protectorSlot
        val allies = listOf(mon(BattleSide.ALLY, protectorSlot, attackStat), mon(BattleSide.ALLY, partnerSlot))
        val foes = (0..1).map { mon(BattleSide.OPPONENT, it) }
        val protect = BattleActionCandidate("protect", BattleActionKind.USE_MOVE, actorSlot = protectorSlot, moveSlot = 0,
            moveId = "cobblemon:$protectionMoveId", moveDetails = BattleMoveCandidateView(
                "normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 4, 10, BattleMoveTargetPattern.SELF,
                BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    if (declared) listOf(BattleMoveEffectView(BattleMoveEffectKind.PROTECT_USER,
                        BattleMoveEffectTarget.USER, probability)) else emptyList(), false)))
        fun attacks(slot: Int) = (0..2).flatMap { move -> (0..1).map { target -> attack(slot, target, move) } }
        fun joint(a: BattleActionCandidate, b: BattleActionCandidate): BattleActionCandidate {
            val parts = listOf(a, b).sortedBy { it.actorSlot }
            return BattleActionCandidate(parts.joinToString("+") { it.actionId }, BattleActionKind.COMPOSITE,
                componentActionIds = parts.map { it.actionId }, componentActions = parts)
        }
        val candidates = (listOf(protect) + attacks(protectorSlot)).flatMap { a -> attacks(partnerSlot).map { joint(a, it) } }
        val state = BattleStateView(UUID(0, 1), BattleFormat.DOUBLE, 2, allies + foes, BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 2 }, emptyList(), emptyList())
        val known = attack(0, 0, 0)
        val context = BattleDecisionContext(UUID(0, 2), state, candidates, Long.MAX_VALUE,
            publicActionCatalog = BattlePublicActionCatalogView(foes.map { BattlePokemonActionCatalogView(
                it.battlePokemonId, listOf(BattlePublicMoveOptionView(known.moveId!!, known.moveDetails!!,
                    BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), true) }))
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        val profile = BattleTrainerProfile.balanced(2).let { it.copy(difficulty = it.difficulty.copy(lookaheadPlies = 1)) }
        val ranked = LocalBattleActionPolicy.rank(calculated, null, profile)
        val cooperative = ranked.first { it.outcome.candidate.componentActions.any { part -> part.actionId == "protect" } }.outcome.candidate
        val exposed = joint(attacks(protectorSlot).first(), cooperative.componentActions.single { it.actorSlot == partnerSlot })
        val reply = attack(0, protectorSlot, 0, BattleSide.ALLY)
        fun project(action: BattleActionCandidate) = PublicSingleTurnProjector.project(state, action, reply, calculated, RecursiveActionHistory())
        val protected = project(cooperative)
        val unprotected = project(exposed)
        fun hp(outcomes: List<jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection>, id: UUID): Double {
            assertEquals(1.0, outcomes.sumOf { it.probability * it.orderProbability }, 1e-9)
            return outcomes.sumOf { it.probability * it.orderProbability * it.state.pokemon.single { mon -> mon.battlePokemonId == id }.hpFraction }
        }
        if (expectedReservation) {
            assertEquals(1.0, hp(protected, allies[0].battlePokemonId), 1e-9)
            assertTrue(hp(unprotected, allies[0].battlePokemonId) < 1.0)
            assertTrue(protected.all { allies[1].battlePokemonId in it.executedMoveIdsByPokemon })
            assertTrue(foes.any { hp(protected, it.battlePokemonId) < 1.0 })
        }
        fun evaluate(tuning: LocalDecisionTuning) = LocalRecursiveLookaheadEvaluator.evaluate(
            ranked, calculated, profile, tuning, clockMillis = { 0L })
        val narrow = evaluate(LocalDecisionTuning.CURRENT)
        val wide = evaluate(LocalDecisionTuning.CURRENT.copy(maximumRootCandidates = Int.MAX_VALUE))
        assertFalse(narrow.truncated)
        assertFalse(wide.truncated)
        assertEquals(candidates.size, wide.responseCoverageByAction.size)
        assertEquals(expectedReservation, cooperative.actionId in LocalCooperativeRootRetention.select(ranked, calculated))
        assertTrue(narrow.responseCoverageByAction.size <= LocalDecisionTuning.CURRENT.maximumRootActionsPerSlot *
            LocalDecisionTuning.CURRENT.maximumRootActionsPerSlot + 3)
        println("PROTECT_ATTACK slot=$protectorSlot move=$protectionMoveId declared=$declared probability=$probability attack=$attackStat " +
            "candidates=${candidates.size} kept=${narrow.responseCoverageByAction.size} " +
            "retained=${cooperative.actionId in narrow.responseCoverageByAction} nodes=${narrow.nodesVisited} wideNodes=${wide.nodesVisited} " +
            "baseRank=${ranked.indexOfFirst { it.outcome.candidate.actionId == cooperative.actionId } + 1} " +
            "narrowChoice=${narrow.ranked.first().outcome.candidate.actionId} wideChoice=${wide.ranked.first().outcome.candidate.actionId}")
        if (expectedReservation) assertTrue(cooperative.actionId in narrow.responseCoverageByAction,
            "Protect must remain searchable alongside an executable partner attack")
    }

    private fun attack(slot: Int, target: Int, move: Int, side: BattleSide = BattleSide.OPPONENT) = BattleActionCandidate(
        "hit-$slot-$move-$target", BattleActionKind.USE_MOVE, actorSlot = slot, moveSlot = move + 1,
        moveId = "cobblemon:probe$move", targets = listOf(BattleTargetSlot(side, target)),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL,
            70.0 + move * 10, 100.0, 0, 10, BattleMoveTargetPattern.SELECTED_OPPONENT))

    private fun mon(side: BattleSide, slot: Int, attackStat: Int = 100) = BattlePokemonStateView(
        battlePokemonId = UUID(0, (10 + side.ordinal * 2 + slot).toLong()), side = side, activeSlot = slot,
        speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = 1.0, statusId = null,
        statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null, knownHeldItemId = null,
        fainted = false, knownTypeIds = setOf("normal"), combatStats = if (side == BattleSide.ALLY)
            BattleCombatStatRangesView.exact(200, attackStat, 100, 100, 100, 100)
        else publicExactStats(200, 100, 100, 100, 100, 90))
}
