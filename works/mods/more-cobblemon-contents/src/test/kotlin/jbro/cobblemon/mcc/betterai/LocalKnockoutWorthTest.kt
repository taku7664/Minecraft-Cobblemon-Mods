package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth
import jbro.cobblemon.mcc.betterai.matchup.MatchupScores
import jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField
import jbro.cobblemon.mcc.betterai.matchup.PokemonMatchupScore
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalKnockoutWorthTest {
    @Test
    fun `a Pokemon is worth its exchanges against the other side, the board's mean held at the flat value`() {
        val ace = mon(BattleSide.ALLY, 0)
        val spent = mon(BattleSide.ALLY, null)
        val foeA = mon(BattleSide.OPPONENT, 0)
        val foeB = mon(BattleSide.OPPONENT, null)
        // The ace wins both exchanges outright; the spent Pokemon loses both without denting either foe.
        val table = mapOf(
            pair(ace, foeA, 1.0), pair(ace, foeB, 0.6),
            pair(spent, foeA, -1.0), pair(spent, foeB, -1.0),
        )
        val state = BattleStateView(
            UUID.randomUUID(), BattleFormat.SINGLE, 5, listOf(ace, spent, foeA, foeB),
            BattleFieldStateView(null, null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }),
            BattleSide.entries.associateWith { 2 }, emptyList(), emptyList(),
        )
        val worth = LocalKnockoutWorth.worths(state, MatchupScores(emptyMap(), table, complete = true), 200.0)

        assertEquals(200.0, worth.values.average(), 1e-9)
        assertEquals(0.0, worth.getValue(spent.battlePokemonId), 1e-9)
        assertTrue(worth.getValue(ace.battlePokemonId) > worth.getValue(foeA.battlePokemonId))
        // foeA: (1 - 1) / 2 against the ace, (1 + 1) / 2 against the spent one.
        val mean = listOf(0.9, 0.0, 0.5, 0.6).average()
        assertEquals(200.0 * 0.5 / mean, worth.getValue(foeA.battlePokemonId), 1e-9)
    }

    private fun pair(ally: BattlePokemonStateView, foe: BattlePokemonStateView, score: Double) =
        Triple(ally.battlePokemonId, foe.battlePokemonId, MatchupSpeedField.CURRENT) to
            PokemonMatchupScore(ally.battlePokemonId, foe.battlePokemonId, MatchupSpeedField.CURRENT, null, null, 0.5,
                (score + 1.0) / 2.0, 0.0, 0.0, score)

    private fun mon(side: BattleSide, slot: Int?) = BattlePokemonStateView(
        UUID.randomUUID(), side, slot, "showdown:probe", null, 50, 1.0, null, emptyMap(), emptySet(), null, null, false, setOf("water"),
    )
}
