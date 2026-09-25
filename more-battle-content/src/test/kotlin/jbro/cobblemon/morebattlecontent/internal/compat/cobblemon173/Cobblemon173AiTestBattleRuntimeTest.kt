package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.internal.command.AiTestDifficulty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Cobblemon173AiTestBattleRuntimeTest {
    @Test
    fun `Cynthia test fixture has the complete champion roster with four legal move slots`() {
        assertEquals(
            listOf("spiritomb", "roserade", "togekiss", "lucario", "milotic", "garchomp"),
            CynthiaAiTestFixture.team.map { it.speciesId.substringAfter(':') },
        )
        assertEquals(6, CynthiaAiTestFixture.team.map { it.setId }.distinct().size)
        CynthiaAiTestFixture.team.forEach { pokemon ->
            assertEquals(4, pokemon.moves.size, pokemon.setId)
            check(pokemon.evs.total in 504..510) { "${pokemon.setId} EV total is ${pokemon.evs.total}" }
            assertEquals(186, pokemon.ivs.total, pokemon.setId)
        }
    }

    @Test
    fun `four command difficulties preserve champion personality and select the intended search depth`() {
        val expected = listOf(
            BattleDifficultyProfiles.INTRODUCTORY,
            BattleDifficultyProfiles.STANDARD,
            BattleDifficultyProfiles.ADVANCED,
            BattleDifficultyProfiles.BOSS,
        )

        assertEquals(expected, AiTestDifficulty.entries.map { it.trainerProfile().difficulty })
        assertEquals(listOf(1, 2, 2, 3), AiTestDifficulty.entries.map { it.trainerProfile().difficulty.lookaheadPlies })
        assertEquals(1, AiTestDifficulty.INTRODUCTORY.skillLevel)
        assertEquals(5, AiTestDifficulty.BOSS.skillLevel)
        assertEquals(
            List(4) { AiTestDifficulty.BOSS.trainerProfile().personality },
            AiTestDifficulty.entries.map { it.trainerProfile().personality },
        )
    }

    @Test
    fun `AI test decisions can remove the wall clock deadline without changing normal battles`() {
        assertEquals(Long.MAX_VALUE, Cobblemon173BrainDecisionTiming.deadline(123L, unbounded = true))
        assertEquals(20_123L, Cobblemon173BrainDecisionTiming.deadline(123L, unbounded = false))
    }
}
