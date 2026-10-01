package jbro.cobblemon.mcc.internal.pvp

import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpMatchStoreTest {
    private val alex = UUID.randomUUID()
    private val sam = UUID.randomUUID()
    private val kim = UUID.randomUUID()

    private fun store() = PvpMatchStore(Files.createTempDirectory("pvp").resolve("data").resolve("matches.sqlite"))

    @Test
    fun `a battle is recorded once however often its completion is retried`() {
        val store = store()
        val battle = UUID.randomUUID()
        assertTrue(store.record(battle, 1_000, "single", alex, "Alex", sam, "Sam"))
        assertFalse(store.record(battle, 2_000, "single", alex, "Alex", sam, "Sam"))
        assertEquals(1, store.matches().size)
    }

    @Test
    fun `matches come newest first a page at a time, and per player`() {
        val store = store()
        repeat(5) { store.record(UUID.randomUUID(), 1_000L + it, "single", alex, "Alex", sam, "Sam") }
        store.record(UUID.randomUUID(), 2_000, "double", kim, "Kim", sam, "Sam")
        val first = store.matches(limit = 4)
        assertEquals(4, first.size)
        assertEquals("double", first[0].format)
        val rest = store.matches(limit = 4, before = first.last().id)
        assertEquals(2, rest.size)
        assertTrue(rest.all { it.id < first.last().id })
        assertEquals(5, store.matches(alex).size)
        assertEquals(6, store.matches(sam).size)
        assertTrue(store.matches(kim).all { it.winnerId == kim })
    }

    @Test
    fun `a player's record against each opponent counts both sides, busiest rival first`() {
        val store = store()
        repeat(3) { store.record(UUID.randomUUID(), 1_000L + it, "single", alex, "Alex", sam, "Sam") }
        store.record(UUID.randomUUID(), 2_000, "single", sam, "Sam", alex, "Alex")
        store.record(UUID.randomUUID(), 3_000, "double", kim, "Kim", alex, "Alex")
        val opponents = store.opponents(alex)
        assertEquals(listOf(sam, kim), opponents.map { it.opponentId })
        assertEquals(3, opponents[0].wins)
        assertEquals(1, opponents[0].losses)
        assertEquals(0, opponents[1].wins)
        assertEquals(1, opponents[1].losses)
    }

    @Test
    fun `players are found by their latest name, whatever its case`() {
        val store = store()
        store.record(UUID.randomUUID(), 1_000, "single", alex, "OldName", sam, "Sam")
        store.record(UUID.randomUUID(), 2_000, "single", sam, "Sam", alex, "Alex")
        assertEquals(alex, store.playerNamed("alex"))
        assertEquals(alex, store.playerNamed("OLDNAME"))
        assertEquals("Alex", store.nameOf(alex))
        assertEquals("Alex", store.opponents(sam).single().opponentName)
        assertNull(store.playerNamed("nobody"))
    }

    @Test
    fun `the history survives reopening the file`() {
        val file = Files.createTempDirectory("pvp").resolve("matches.sqlite")
        PvpMatchStore(file).record(UUID.randomUUID(), 1_000, "single", alex, "Alex", sam, "Sam")
        assertEquals(1, PvpMatchStore(file).matches().size)
    }
}
