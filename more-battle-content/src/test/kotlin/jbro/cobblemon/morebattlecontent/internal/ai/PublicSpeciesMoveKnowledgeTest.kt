package jbro.cobblemon.morebattlecontent.internal.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicSpeciesMoveKnowledgeTest {
    @Test
    fun `public move pool snapshots its partial source without claiming ownership`() {
        val source = mutableSetOf("thunderbolt", "protect")
        val pool = PublicSpeciesMovePool(source, "fixture:public_learnset")
        source.clear()
        assertEquals(setOf("protect", "thunderbolt"), pool.moveIds)
        assertEquals("fixture:public_learnset", pool.sourceId)
        assertThrows(UnsupportedOperationException::class.java) { (pool.moveIds as MutableSet).clear() }
        assertThrows(IllegalArgumentException::class.java) { PublicSpeciesMovePool(setOf(""), "fixture") }
        assertThrows(IllegalArgumentException::class.java) { PublicSpeciesMovePool(emptySet(), "") }
    }

    @Test
    fun `unavailable rule source is distinct from a present empty pool`() {
        val unavailable = PublicSpeciesMoveKnowledge { _, _ -> null }
        val empty = PublicSpeciesMoveKnowledge { _, _ -> PublicSpeciesMovePool(emptySet(), "fixture") }
        assertNull(unavailable.possibleMoves("probe", null))
        assertNotNull(empty.possibleMoves("probe", null))
    }
}
