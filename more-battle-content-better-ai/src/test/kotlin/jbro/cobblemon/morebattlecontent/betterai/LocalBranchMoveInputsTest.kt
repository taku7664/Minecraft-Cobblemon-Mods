package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.state.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalProjectedActionCalculationCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalBranchMoveInputsTest {
    @Test
    fun `branch input restores known moves and spends PP only for history-free leaf evaluation`() {
        val id = UUID.randomUUID()
        val pokemon = BattlePokemonStateView(id, BattleSide.ALLY, 0, "ditto", null, 50, 1.0, null,
            emptyMap(), setOf("splash"), null, null, false)
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(pokemon),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 0), emptyList(), emptyList())
        fun entry(move: String, pp: Int) = BattlePokemonActionCatalogView(id, listOf(BattlePublicMoveOptionView(move,
            BattleMoveCandidateView("normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, pp),
            BattlePublicMoveKnowledge.EXACT_OWN)), true)
        val action = BattleActionCandidate("wait", BattleActionKind.WAIT)
        val source = BattleDecisionContext(UUID.randomUUID(), state, listOf(action), Long.MAX_VALUE,
            publicActionCatalog = BattlePublicActionCatalogView(listOf(entry("splash", 5)), listOf(entry("recover", 7))))
        val history = RecursiveActionHistory(restoredOriginalPokemonIds = setOf(id),
            moveUses = mapOf(RecursiveMoveUseKey(id, "recover") to 5))
        val projected = LocalBranchMoveInputs.context(source, state, history)
        val leaf = LocalBranchMoveInputs.context(source, state, history, spendPp = true)
        assertEquals(setOf("recover"), leaf.state.pokemon.single().knownMoveIds)
        assertEquals(7, projected.publicActionCatalog.forPokemon(id).single().details.currentPp)
        assertEquals(2, leaf.publicActionCatalog.forPokemon(id).single().details.currentPp)
        assertEquals(setOf("splash"), source.state.pokemon.single().knownMoveIds)
        assertEquals(5, source.publicActionCatalog.forPokemon(id).single().details.currentPp)
        assertNotEquals(LocalBranchMoveInputs.key("same-board", history),
            LocalBranchMoveInputs.key("same-board", history.copy(moveUses = emptyMap())))
        assertNotEquals(LocalBranchMoveInputs.key("same-board", history),
            LocalBranchMoveInputs.key("same-board", history.copy(restoredOriginalPokemonIds = emptySet())))

        val cache = LocalProjectedActionCalculationCache()
        var calculations = 0
        fun cached(catalog: BattlePublicActionCatalogView) = cache.getOrCalculate(state, BattleSide.ALLY, action,
            catalog = catalog) { calculations++; source }
        cached(source.publicActionCatalog)
        cached(projected.publicActionCatalog)
        cached(BattlePublicActionCatalogView(projected.publicActionCatalog.entries))
        assertEquals(2, calculations)
    }
}
