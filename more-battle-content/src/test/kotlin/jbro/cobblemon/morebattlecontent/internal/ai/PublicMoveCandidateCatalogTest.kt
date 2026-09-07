package jbro.cobblemon.morebattlecontent.internal.ai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PublicMoveCandidateCatalogTest {
    @Test
    fun `hypothesis mechanics snapshot only members of the partial pool`() {
        val detail = BattleMoveCandidateView("electric", BattleMoveDamageCategory.SPECIAL, 90.0, 100.0, 0, 24)
        val details = mutableMapOf("thunderbolt" to detail)
        val pool = BattlePublicMoveCandidatePoolView(UUID.randomUUID(), "thundurus", null,
            setOf("thunderbolt", "protect"), "fixture", details)
        details.clear()
        assertEquals(detail, pool.moveDetails["thunderbolt"])
        assertFalse(pool.moveDetails.containsKey("protect"))
        assertThrows(UnsupportedOperationException::class.java) { (pool.moveDetails as MutableMap).clear() }
        assertThrows(IllegalArgumentException::class.java) {
            BattlePublicMoveCandidatePoolView(UUID.randomUUID(), "thundurus", null, emptySet(),
                "fixture", mapOf("thunderbolt" to detail))
        }
    }

    @Test
    fun `partial candidates are immutable and never become revealed or complete moves`() {
        val id = UUID.randomUUID()
        val moves = mutableSetOf("thunderbolt", "protect")
        val pool = BattlePublicMoveCandidatePoolView(id, "thundurus", null, moves, "fixture:learnset")
        val pools = mutableListOf(pool)
        val catalog = BattlePublicActionCatalogView(emptyList(), candidatePools = pools)
        moves.clear()
        pools.clear()
        assertEquals(setOf("protect", "thunderbolt"), catalog.candidatePools.single().moveIds)
        assertTrue(catalog.forPokemon(id).isEmpty())
        assertFalse(catalog.isMoveSetComplete(id))
        assertThrows(UnsupportedOperationException::class.java) { (pool.moveIds as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (catalog.candidatePools as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) {
            BattlePublicActionCatalogView(emptyList(), candidatePools = listOf(pool, pool))
        }
    }

    @Test
    fun `restoring original moves retains separately sourced candidates and old constructors`() {
        val id = UUID.randomUUID()
        val pool = BattlePublicMoveCandidatePoolView(id, "ditto", null, emptySet(), "fixture:partial")
        val original = BattlePokemonActionCatalogView(id, emptyList())
        val source = BattlePublicActionCatalogView(emptyList(), listOf(original), listOf(pool))
        val restored = source.afterSwitch(setOf(id))
        assertSame(pool, restored.candidatePools.single())
        assertEquals(1, source.originalEntries.size)
        assertTrue(restored.originalEntries.isEmpty())
        assertTrue(BattlePublicActionCatalogView.empty().candidatePools.isEmpty())
        assertNotNull(BattlePublicActionCatalogView::class.java.getConstructor(List::class.java))
        assertNotNull(BattlePublicActionCatalogView::class.java.getConstructor(List::class.java, List::class.java))
        assertThrows(IllegalArgumentException::class.java) {
            BattlePublicMoveCandidatePoolView(id, "ditto", null, setOf(""), "fixture")
        }
    }
}
