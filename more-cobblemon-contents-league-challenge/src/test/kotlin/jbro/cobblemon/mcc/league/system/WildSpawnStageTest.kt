package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WildSpawnStageTest {
    // Charizard (from 36), Charmeleon (from 16), then Charmander, which evolved from nothing.
    private val charizard = listOf(36, 16)

    @Test
    fun `a spawn steps back until its stage is met at that level`() {
        assertEquals(0, WildSpawnStage.stepsBack(40, charizard))
        assertEquals(0, WildSpawnStage.stepsBack(36, charizard))
        assertEquals(1, WildSpawnStage.stepsBack(35, charizard))
        assertEquals(1, WildSpawnStage.stepsBack(16, charizard))
        assertEquals(2, WildSpawnStage.stepsBack(12, charizard))
    }

    @Test
    fun `a stage nothing gives a level for keeps the line where it is`() {
        assertEquals(0, WildSpawnStage.stepsBack(5, listOf(null, 16)))
        assertEquals(1, WildSpawnStage.stepsBack(5, listOf(36, null)))
        assertEquals(0, WildSpawnStage.stepsBack(5, emptyList()))
    }
}
