package jbro.cobblemon.mcc.internal.tower

import java.util.Random
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TowerChampionRotationTest {
    private val champions = setOf("champion_blue", "champion_lance", "champion_cynthia")

    @Test
    fun `every Champion comes once a round and none comes twice in a row, across rounds and runs`() {
        val rotation = TowerChampionRotation()
        val player = UUID(0, 1)
        val random = Random(7)
        val met = ArrayList<String>()
        repeat(30) {
            val excluded = rotation.excluded(player, champions)
            val pick = (champions - excluded).shuffled(random).first()
            rotation.record(player, pick, champions)
            met += pick
        }
        met.chunked(champions.size).forEach { round -> assertEquals(champions, round.toSet(), "round $round") }
        met.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test
    fun `each challenger has their own rotation`() {
        val rotation = TowerChampionRotation()
        rotation.record(UUID(0, 1), "champion_blue", champions)
        assertEquals(setOf("champion_blue"), rotation.excluded(UUID(0, 1), champions))
        assertEquals(emptySet<String>(), rotation.excluded(UUID(0, 2), champions))
    }
}
