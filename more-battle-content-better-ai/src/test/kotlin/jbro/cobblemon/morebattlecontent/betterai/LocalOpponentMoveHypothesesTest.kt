package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.state.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalOpponentMoveHypothesesTest {
    @Test
    fun `double joint hypotheses keep separate fourth slots and PP across the next turn`() {
        val secondId = UUID.randomUUID()
        fun member(memberId: UUID, side: BattleSide, slot: Int) = BattlePokemonStateView(memberId, side,
            slot, "probe", null, 50, 1.0, null, emptyMap(), setOf("a", "b", "c"), null, null, false)
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.DOUBLE, 1,
            listOf(member(UUID.randomUUID(), BattleSide.ALLY, 0), member(UUID.randomUUID(), BattleSide.ALLY, 1),
                pokemon, member(secondId, BattleSide.OPPONENT, 1)), BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 2), emptyList(), emptyList())
        val doubleCatalog = BattlePublicActionCatalogView(emptyList(), candidatePools = catalog.candidatePools +
            BattlePublicMoveCandidatePoolView(secondId, "probe", null, setOf("d", "e"), "fixture",
                mapOf("d" to details, "e" to details)))
        val prior = RecursiveActionHistory(moveUses = mapOf(
            RecursiveMoveUseKey(id, "d") to 6, RecursiveMoveUseKey(secondId, "e") to 7))
        fun actions(history: RecursiveActionHistory, limit: Int = Int.MAX_VALUE) = PublicFutureActionFactory.actions(
            state, BattleSide.OPPONENT, doubleCatalog, history, candidateLimitPerSlot = limit,
            unknownMovePokemonIds = setOf(id, secondId), includeMoveHypotheses = true)
        val joint = actions(prior).first { it.componentActions.map { component -> component.moveId } == listOf("d", "e") }
        val committed = LocalOpponentMoveHypotheses.assumeAction(state, doubleCatalog, prior, joint)
        assertEquals(mapOf(id to setOf("d"), secondId to setOf("e")), committed.assumedOpponentMoveIds)
        assertEquals(listOf(2, 1), joint.componentActions.map { it.moveDetails?.currentPp })
        val spent = LocalBranchMoveInputs.afterExecutedMoves(committed, mapOf(id to "d", secondId to "e"))
        val next = actions(spent)
        val nextMoves = next.flatMap { it.componentActions }.filter { it.kind == BattleActionKind.USE_MOVE }
        assertTrue(nextMoves.isNotEmpty())
        assertTrue(nextMoves.all { it.actorSlot == 0 && it.moveId == "d" && it.moveDetails?.currentPp == 1 })
        assertTrue(next.any { turn -> turn.componentActions.all { "unknown_public_response" in it.tags } })
        assertEquals(4, actions(committed, limit = 2).size)
        assertTrue(prior.assumedOpponentMoveIds.isEmpty())
        assertEquals(6, prior.moveUses[RecursiveMoveUseKey(id, "d")])
        assertTrue(doubleCatalog.entries.isEmpty())
    }

    @Test
    fun `a hypothesized charge can finish at zero PP without reopening a move slot`() {
        val ally = BattlePokemonStateView(UUID.randomUUID(), BattleSide.ALLY, 0, "target", null, 50,
            1.0, null, emptyMap(), emptySet(), null, null, false)
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(ally, pokemon),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        val assumed = LocalOpponentMoveHypotheses.assume(pokemon, catalog, RecursiveActionHistory(), "d")
        val charging = assumed.copy(moveUses = mapOf(RecursiveMoveUseKey(id, "d") to 8),
            chargingMoveByPokemon = mapOf(id to "d"))
        fun actions(history: RecursiveActionHistory) = PublicFutureActionFactory.actions(state, BattleSide.OPPONENT,
            catalog, history, unknownMovePokemonIds = setOf(id), includeMoveHypotheses = true)
        val continuation = actions(charging).single()
        assertEquals("d", continuation.moveId)
        assertEquals(0, continuation.moveDetails?.currentPp)
        val committed = LocalOpponentMoveHypotheses.assumeAction(state, catalog, charging, continuation)
        val finished = LocalBranchMoveInputs.afterExecutedMoves(committed, mapOf(id to "d"))
            .copy(chargingMoveByPokemon = emptyMap())
        assertEquals(8, finished.moveUses[RecursiveMoveUseKey(id, "d")])
        assertFalse(actions(finished).any { it.kind == BattleActionKind.USE_MOVE })
        assertEquals(setOf("d"), finished.assumedOpponentMoveIds[id])
    }

    @Test
    fun `opt-in future actions use marked hypotheses and retain unknown response`() {
        val ally = BattlePokemonStateView(UUID.randomUUID(), BattleSide.ALLY, 0, "target", null, 50,
            1.0, null, emptyMap(), emptySet(), null, null, false)
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(ally, pokemon),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            emptyList(), emptyList())
        val root = RecursiveActionHistory(moveUses = mapOf(RecursiveMoveUseKey(id, "d") to 2))
        fun actions(history: RecursiveActionHistory, enabled: Boolean) = PublicFutureActionFactory.actions(
            state, BattleSide.OPPONENT, catalog, history, unknownMovePokemonIds = setOf(id),
            includeMoveHypotheses = enabled)
        assertFalse(LocalDecisionTuning.CURRENT.lookaheadMoveHypotheses)
        assertFalse(actions(root, false).any { it.kind == BattleActionKind.USE_MOVE })
        val enabled = actions(root, true)
        assertTrue(enabled.any { "unknown_public_response" in it.tags })
        val move = enabled.single { it.moveId == "d" }
        assertTrue("hypothetical_public_move" in move.tags)
        assertEquals(6, move.moveDetails?.currentPp)
        assertEquals(listOf(BattleTargetSlot(BattleSide.ALLY, 0)), move.targets)
        val committed = LocalOpponentMoveHypotheses.assumeAction(state, catalog, root, move)
        assertEquals(setOf("d"), actions(committed, true).mapNotNull { it.moveId }.toSet())
        assertTrue(root.assumedOpponentMoveIds.isEmpty())
        assertTrue(catalog.forPokemon(id).isEmpty())
    }

    private val id = UUID.randomUUID()
    private val pokemon = BattlePokemonStateView(id, BattleSide.OPPONENT, 0, "probe", null, 50, 1.0,
        null, emptyMap(), setOf("a", "b", "c"), null, null, false)
    private val details = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 8)
    private val catalog = BattlePublicActionCatalogView(emptyList(), candidatePools = listOf(
        BattlePublicMoveCandidatePoolView(id, "probe", null, setOf("d", "e"), "fixture",
            mapOf("d" to details, "e" to details))))

    @Test
    fun `a selected hypothesis fills the fourth slot even without execution and isolates siblings`() {
        val root = RecursiveActionHistory()
        assertEquals(setOf("d", "e"), LocalOpponentMoveHypotheses.options(pokemon, catalog, root).keys)
        val left = LocalOpponentMoveHypotheses.assume(pokemon, catalog, root, "d")
        val right = LocalOpponentMoveHypotheses.assume(pokemon, catalog, root, "e")
        assertTrue(left.moveUses.isEmpty())
        assertEquals(setOf("d"), LocalOpponentMoveHypotheses.options(pokemon, catalog, left).keys)
        assertEquals(setOf("e"), LocalOpponentMoveHypotheses.options(pokemon, catalog, right).keys)
        assertTrue(root.assumedOpponentMoveIds.isEmpty())
        assertNotEquals(left, right)
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(pokemon),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 0, BattleSide.OPPONENT to 1),
            emptyList(), emptyList())
        val wait = BattleActionCandidate("wait", BattleActionKind.WAIT)
        val outcome = PublicTurnProjection(state, listOf(BattleSide.ALLY, BattleSide.OPPONENT),
            stateBeforeResidual = state, directDamage = LocalDirectDamageLedger.EMPTY)
        val next = RecursiveHistoryProjector.project(left, state, outcome, wait, wait)
        assertTrue(next.moveUses.isEmpty())
        assertEquals(left.assumedOpponentMoveIds, next.assumedOpponentMoveIds)
        assertEquals(setOf("d"), LocalOpponentMoveHypotheses.options(pokemon, catalog, next).keys)
        assertThrows(IllegalArgumentException::class.java) {
            LocalOpponentMoveHypotheses.assume(pokemon, catalog, left, "e")
        }
        assertEquals(setOf("a", "b", "c"), pokemon.knownMoveIds)
    }

    @Test
    fun `unavailable templates and exhausted PP do not invent executable moves`() {
        assertTrue(LocalOpponentMoveHypotheses.options(pokemon, BattlePublicActionCatalogView.empty(),
            RecursiveActionHistory()).isEmpty())
        val history = RecursiveActionHistory(moveUses = mapOf(RecursiveMoveUseKey(id, "d") to 8))
        assertEquals(setOf("e"), LocalOpponentMoveHypotheses.options(pokemon, catalog, history).keys)
        val wrongForm = BattlePublicActionCatalogView(emptyList(), candidatePools = listOf(
            BattlePublicMoveCandidatePoolView(id, "other", null, setOf("d"), "fixture", mapOf("d" to details))))
        assertTrue(LocalOpponentMoveHypotheses.options(pokemon, wrongForm, RecursiveActionHistory()).isEmpty())
    }
}
