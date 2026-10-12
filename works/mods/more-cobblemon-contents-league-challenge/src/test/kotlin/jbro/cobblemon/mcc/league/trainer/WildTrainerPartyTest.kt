package jbro.cobblemon.mcc.league.trainer

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WildTrainerPartyTest {
    @Test
    fun `aces always bring four to six Pokemon at every level cap`() {
        (1..100).forEach { cap ->
            val random = Random(cap)
            val sizes = List(500) { WildTrainerParty.size(cap, WildTrainerTier.ACE, random) }.toSet()
            assertEquals(setOf(4, 5, 6), sizes, "Ace cap $cap")
        }
    }

    @Test
    fun `ordinary trainers still bring one to six Pokemon in every phase`() {
        listOf(1, 24, 25, 44, 45, 64, 65, 100).forEach { cap ->
            val random = Random(cap)
            val sizes = List(500) { WildTrainerParty.size(cap, WildTrainerTier.NORMAL, random) }.toSet()
            assertEquals((1..6).toSet(), sizes, "Normal cap $cap")
        }
    }

    @Test
    fun `an ace with a small species pool still rolls at least four legal Pokemon`() {
        val definition = WildTrainerDefinition(
            npcClass = "test:ace",
            bp = 1L..1L,
            tier = WildTrainerTier.ACE,
            pokemon = listOf(WildTrainerStage("magikarp", 1, 100)),
        )
        (1..100).forEach { cap ->
            val random = Random(cap)
            repeat(50) {
                val party = WildTrainerParty.roll(definition, cap, random)
                assertTrue(party.size in 4..6, "Ace cap $cap: $party")
                assertTrue(party.all { (species, level) -> species == "magikarp" && level in 1..cap })
            }
        }
    }
}
