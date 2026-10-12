package jbro.cobblemon.mcc.betterai.engine

import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import jbro.cobblemon.mcc.betterai.engine.dex.MissingHook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every Mega Stone in the dex, Mega Showdown's Z-A and Champions ones included, keeps its holder from losing it.
 * The per-stone ports listed 47 of the 87; the rest (Staraptite among them) reached a missing handler and the
 * engine let Knock Off take them (2026-10-13).
 */
class EngineMegaStoneTakeItemTest {
    @Test
    fun `every mega stone has its take-item rule`() {
        val stones = EngineDex.bundled().allItems.filter { it.data("megaStone") != null && it.declares("onTakeItem") }
        assertTrue(stones.size > 47, "only ${stones.size} Mega Stones in the dex")
        val missing = stones.filter { it.handler("onTakeItem").let { handler -> handler == null || handler is MissingHook } }
        assertEquals(emptyList<String>(), missing.map { it.id })
    }
}
