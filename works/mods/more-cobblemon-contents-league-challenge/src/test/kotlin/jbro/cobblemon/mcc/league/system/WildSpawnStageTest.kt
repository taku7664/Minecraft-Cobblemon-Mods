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

    private fun branch(name: String, minLevel: Int?, blocked: Boolean = false, unmet: Boolean = false, maxLevel: Int = Int.MAX_VALUE) =
        WildSpawnStage.Branch(name, minLevel, maxLevel, blocked, unmet)

    @Test
    fun `a spawn evolves once its level reaches the evolution level`() {
        val charmeleon = listOf(branch("charmeleon", 16))
        assertEquals(listOf("charmeleon"), WildSpawnStage.next(16, charmeleon))
        assertEquals(listOf("charmeleon"), WildSpawnStage.next(70, charmeleon))
        assertEquals(emptyList<String>(), WildSpawnStage.next(15, charmeleon))
        assertEquals(emptyList<String>(), WildSpawnStage.next(30, listOf(branch("charmeleon", 16, maxLevel = 20))))
    }

    @Test
    fun `an evolution without a level condition never happens`() {
        assertEquals(emptyList<String>(), WildSpawnStage.next(80, listOf(branch("raichu", null), branch("gengar", null))))
    }

    @Test
    fun `a condition on the Pokemon itself rules a branch out`() {
        // A male Combee never becomes a Vespiquen; a female Burmy takes the Wormadam branch only.
        assertEquals(emptyList<String>(), WildSpawnStage.next(40, listOf(branch("vespiquen", 21, blocked = true, unmet = true))))
        assertEquals(listOf("wormadam"),
            WildSpawnStage.next(40, listOf(branch("wormadam", 20), branch("mothim", 20, blocked = true, unmet = true))))
    }

    @Test
    fun `branches whose other conditions hold win, otherwise any reached branch may be taken`() {
        val cubone = listOf(branch("marowak", 28), branch("marowak alolan", 28, unmet = true))
        assertEquals(listOf("marowak"), WildSpawnStage.next(30, cubone))
        // Pancham needs a Dark type in an owner's party, which a wild one never has.
        assertEquals(listOf("pangoro"), WildSpawnStage.next(40, listOf(branch("pangoro", 32, unmet = true))))
        assertEquals(listOf("hitmonlee", "hitmonchan"),
            WildSpawnStage.next(25, listOf(branch("hitmonlee", 20, unmet = true), branch("hitmonchan", 20, unmet = true))))
    }
}
