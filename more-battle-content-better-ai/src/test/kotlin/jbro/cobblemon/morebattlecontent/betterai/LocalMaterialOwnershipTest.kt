package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalImmediateTurnScorer
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalLookaheadStateEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** Characterization of existing material units, not a claim that these weights are optimal. */
class LocalMaterialOwnershipTest {
    @Test
    fun `immediate and leaf material preserve visible bench unseen and knockout values`() {
        val empty = state(emptyList(), 0, 0)
        val cases = listOf(
            state(listOf(pokemon(1, BattleSide.ALLY, 1.0)), 1, 0) to 3.0,
            state(listOf(pokemon(1, BattleSide.ALLY, 0.25)), 1, 0) to 2.25,
            state(listOf(pokemon(1, BattleSide.ALLY, 0.25, active = false)), 1, 0) to 2.25,
            state(emptyList(), 2, 1) to 3.0,
            state(listOf(pokemon(1, BattleSide.ALLY, 0.25), pokemon(2, BattleSide.OPPONENT, 0.75)), 2, 1) to 2.5,
            state(listOf(pokemon(1, BattleSide.ALLY, 0.0, fainted = true)), 0, 1) to -3.0,
            state(listOf(pokemon(1, BattleSide.ALLY, 0.0)), 0, 0) to 0.0,
            state(listOf(pokemon(1, BattleSide.ALLY, 0.5, fainted = true)), 0, 0) to 0.0,
            // A temporarily lower total does not subtract publicly visible living Pokemon.
            state(listOf(pokemon(1, BattleSide.ALLY, 0.5)), 0, 0) to 2.5,
        )
        cases.forEachIndexed { index, (board, expected) ->
            assertEquals(expected, LocalImmediateTurnScorer.score(empty, board).materialDelta, "immediate case $index")
            assertEquals(expected, LocalLookaheadStateEvaluator.evaluate(board, context(board)), "leaf case $index")
        }
    }

    @Test
    fun `probabilistic knockout credit equals removal value beyond lost HP`() {
        for (victim in BattleSide.entries) {
            for (hp in listOf(0.125, 0.5, 1.0)) {
                val before = state(listOf(pokemon(1, victim, hp)), if (victim == BattleSide.ALLY) 1 else 0,
                    if (victim == BattleSide.OPPONENT) 1 else 0)
                val after = state(listOf(pokemon(1, victim, 0.0, fainted = true)), 0, 0)
                val attacker = if (victim == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
                val sign = if (attacker == BattleSide.ALLY) 1 else -1
                val removal = LocalImmediateTurnScorer.score(before, after).materialDelta - sign * hp
                assertEquals(sign * 2.0, removal)
                for (probability in listOf(-1.0, 0.0, 0.375, 1.0, 2.0)) {
                    assertEquals(removal * probability.coerceIn(0.0, 1.0),
                        LocalImmediateTurnScorer.expectedKnockoutBonus(attacker, probability))
                }
            }
        }
    }

    private fun context(state: BattleStateView) = BattleDecisionContext(UUID(0, 910), state,
        listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)), Long.MAX_VALUE)

    private fun state(pokemon: List<BattlePokemonStateView>, allies: Int, opponents: Int) = BattleStateView(
        UUID(0, 911), BattleFormat.SINGLE, 1, pokemon, BattleFieldStateView.empty(),
        mapOf(BattleSide.ALLY to allies, BattleSide.OPPONENT to opponents), emptyList(), emptyList())

    private fun pokemon(id: Long, side: BattleSide, hp: Double, active: Boolean = true, fainted: Boolean = false) =
        BattlePokemonStateView(UUID(0, id), side, if (active) 0 else null, "fixture:material", null, 50,
            hp, null, emptyMap(), emptySet(), null, null, fainted)
}
