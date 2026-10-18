package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.betterai.matchup.LocalKnockoutWorth
import jbro.cobblemon.mcc.betterai.matchup.MatchupScores
import jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField
import jbro.cobblemon.mcc.betterai.matchup.MoveMatchupScore
import jbro.cobblemon.mcc.betterai.matchup.PokemonMatchupScore
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LocalKnockoutWorthTest {
    @Test
    fun `a Pokemon is worth its exchanges against the other side, each side's mean held at one Pokemon`() {
        val ace = mon(BattleSide.ALLY, 0)
        val spent = mon(BattleSide.ALLY, null)
        val foeA = mon(BattleSide.OPPONENT, 0)
        val foeB = mon(BattleSide.OPPONENT, null)
        // The ace wins both exchanges outright; the spent Pokemon loses both without denting either foe.
        val table = mapOf(
            pair(ace, foeA, 1.0), pair(ace, foeB, 0.6),
            pair(spent, foeA, -1.0), pair(spent, foeB, -1.0),
        )
        val worth = LocalKnockoutWorth.worths(state(ace, spent, foeA, foeB), MatchupScores(emptyMap(), table, complete = true), 200.0)

        assertEquals(200.0, listOf(ace, spent).map { worth.getValue(it.battlePokemonId) }.average(), 1e-9)
        assertEquals(200.0, listOf(foeA, foeB).map { worth.getValue(it.battlePokemonId) }.average(), 1e-9)
        assertEquals(0.0, worth.getValue(spent.battlePokemonId), 1e-9)
        // foeA: (1 - 1) / 2 against the ace, (1 + 1) / 2 against the spent one; foeB: 0.2 and 1.
        assertEquals(200.0 * 0.5 / 0.55, worth.getValue(foeA.battlePokemonId), 1e-9)
    }

    @Test
    fun `a Pokemon that loses every exchange is still worth the hit it lands before it falls`() {
        val ace = mon(BattleSide.ALLY, 0)
        val spent = mon(BattleSide.ALLY, null)
        val foeA = mon(BattleSide.OPPONENT, 0)
        val foeB = mon(BattleSide.OPPONENT, null)
        val table = mapOf(
            pair(ace, foeA, 1.0), pair(ace, foeB, 0.6),
            pair(spent, foeA, -1.0), pair(spent, foeB, -1.0),
        )
        // The spent Pokemon still takes half of foeA's bar with one hit: half a board unit against foeA, none against foeB.
        val hit = MoveMatchupScore(spent.battlePokemonId, "probe", foeA.battlePokemonId, 1.0, 0.5, 0.5,
            listOf(1.0, 1.0, 0.0), listOf(0.0, 0.5, 0.0), 2.0, 0.5)
        val worth = LocalKnockoutWorth.worths(state(ace, spent, foeA, foeB),
            MatchupScores(mapOf((spent.battlePokemonId to foeA.battlePokemonId) to listOf(hit)), table, complete = true), 2.0)

        // Board units: the ace 2 x 2 = 4 on the fight, the spent one 0 + 0.25; scaled to a mean of 2.
        assertEquals(2.0 * 0.25 / ((4.0 + 0.25) / 2.0), worth.getValue(spent.battlePokemonId), 1e-9)
    }

    @Test
    fun `a hit the Pokemon never gets to use is worth nothing`() {
        // A Lucario the faster boosted Mega Tyranitar knocks out first: its High Jump Kick would knock the Mega out, but
        // never lands, so it is worth no more than an idle teammate that loses the same way.
        val mega = mon(BattleSide.ALLY, 0)
        val lucario = mon(BattleSide.OPPONENT, 0)
        val idle = mon(BattleSide.OPPONENT, null)
        // The Mega's hit goes first (its priority stands in for the boosted Speed these stat-less Pokemon cannot show).
        val earthquake = MoveMatchupScore(mega.battlePokemonId, "earthquake", lucario.battlePokemonId, 1.0, 1.0, 1.0,
            listOf(1.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0), 1.0, 1.0, priority = 1)
        val kick = MoveMatchupScore(lucario.battlePokemonId, "highjumpkick", mega.battlePokemonId, 0.9, 1.0, 1.0,
            listOf(1.0, 0.1, 0.01), listOf(0.0, 0.0, 0.0), 1.1, 0.9)
        // The Mega moves first and knocks the Lucario out (first 1.0, earthquake ko 1.0); the idle one loses the same way.
        fun pair(foe: BattlePokemonStateView, foeMove: MoveMatchupScore?) = Triple(mega.battlePokemonId, foe.battlePokemonId, MatchupSpeedField.CURRENT) to
            PokemonMatchupScore(mega.battlePokemonId, foe.battlePokemonId, MatchupSpeedField.CURRENT, earthquake, foeMove, 1.0, 1.0, 1.0, 0.0, 1.0)
        val worth = LocalKnockoutWorth.worths(state(mega, lucario, idle), MatchupScores(mapOf(
            (lucario.battlePokemonId to mega.battlePokemonId) to listOf(kick),
        ), mapOf(pair(lucario, kick), pair(idle, null)), complete = true), 2.0)
        assertEquals(worth.getValue(idle.battlePokemonId), worth.getValue(lucario.battlePokemonId), 1e-9)
    }

    private fun state(vararg pokemon: BattlePokemonStateView) = BattleStateView(
        UUID.randomUUID(), BattleFormat.SINGLE, 5, pokemon.toList(),
        BattleFieldStateView(null, null, emptyList(), emptyList(), BattleSide.entries.associateWith { emptyList() }),
        BattleSide.entries.associateWith { 2 }, emptyList(), emptyList(),
    )

    private fun pair(ally: BattlePokemonStateView, foe: BattlePokemonStateView, score: Double) =
        Triple(ally.battlePokemonId, foe.battlePokemonId, MatchupSpeedField.CURRENT) to
            PokemonMatchupScore(ally.battlePokemonId, foe.battlePokemonId, MatchupSpeedField.CURRENT, null, null, 0.5,
                (score + 1.0) / 2.0, 0.0, 0.0, score)

    private fun mon(side: BattleSide, slot: Int?) = BattlePokemonStateView(
        UUID.randomUUID(), side, slot, "showdown:probe", null, 50, 1.0, null, emptyMap(), emptySet(), null, null, false, setOf("water"),
    )
}
