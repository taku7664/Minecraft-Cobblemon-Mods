package jbro.cobblemon.mcc.betterai.engine

import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.BattleOptions
import jbro.cobblemon.mcc.betterai.engine.sim.fork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

/** A forked battle plays on exactly like the original, and playing it never changes the original. */
class EngineForkTest {
    private val p1 = listOf(RefSet("Garchomp", listOf("earthquake", "dragonclaw", "stoneedge"), item = "Life Orb", ability = "Rough Skin"),
        RefSet("Gengar", listOf("shadowball", "willowisp", "sludgebomb")))
    private val p2 = listOf(RefSet("Gyarados", listOf("waterfall", "icefang"), item = "Leftovers", ability = "Intimidate"),
        RefSet("Blissey", listOf("seismictoss", "thunderwave"), item = "Leftovers"))
    private val turns = listOf("move 2" to "move 1", "move 3" to "switch 2", "switch 2" to "move 2", "move 2" to "move 1",
        "move 1" to "move 2", "move 3" to "move 1")

    private fun start(): Battle = Battle(EngineReferee.dex, BattleOptions(seed = intArrayOf(5, 6, 7, 8))).also { b ->
        b.setPlayer("p1", "p1", p1.mapIndexed { i, s -> s.toSet("p1", i) })
        b.setPlayer("p2", "p2", p2.mapIndexed { i, s -> s.toSet("p2", i) })
    }

    private fun play(battle: Battle, from: Int) {
        for ((a, b) in turns.drop(from)) {
            if (battle.ended) break
            if (battle.sides[0].requestState.isNotEmpty()) battle.sides[0].choose(a)
            if (battle.sides[1].requestState.isNotEmpty()) battle.sides[1].choose(b)
            battle.commitDecisions()
        }
    }

    @Test
    fun `a fork continues exactly like the original`() {
        val whole = start().also { play(it, 0) }
        val original = start()
        for ((a, b) in turns.take(2)) {
            original.sides[0].choose(a)
            original.sides[1].choose(b)
            original.commitDecisions()
        }
        val copy = original.fork(keepLog = true)
        assertNotSame(original.sides[0].pokemon[0], copy.sides[0].pokemon[0])
        play(copy, 2)
        assertEquals(whole.log, copy.log)
        play(original, 2)
        assertEquals(whole.log, original.log)
    }

    @Test
    fun `playing a fork leaves the original untouched`() {
        val original = start()
        original.sides[0].choose("move 2")
        original.sides[1].choose("move 1")
        original.commitDecisions()
        val hpBefore = original.sides.map { s -> s.pokemon.map { it.hp } }
        val turn = original.turn
        val copy = original.fork()
        play(copy, 1)
        assertEquals(hpBefore, original.sides.map { s -> s.pokemon.map { it.hp } })
        assertEquals(turn, original.turn)
    }
}
