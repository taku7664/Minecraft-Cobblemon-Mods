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
