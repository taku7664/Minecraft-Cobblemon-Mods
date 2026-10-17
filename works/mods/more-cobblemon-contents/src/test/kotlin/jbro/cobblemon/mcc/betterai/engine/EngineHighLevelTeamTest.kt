package jbro.cobblemon.mcc.betterai.engine

import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hard champions field Lv101-105 teams (Cynthia hard, Leon hard). Showdown clamps levels to 1..9999, so the
 * engine world must accept them too; a 1..100 check turned off every engine path for those battles (battle
 * 992dfe75, 2026-10-18).
 */
class EngineHighLevelTeamTest {
    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private val stats = listOf("hp", "atk", "def", "spa", "spd", "spe")
    private fun evs(vararg pairs: Pair<String, Int>) = stats.associateWith { 0 } + pairs.toMap()

    private val definition = NativeBattleDefinition(
        formatId = "cobblemonsingles", seed = listOf(1, 2, 3, 4),
        p1Team = listOf(
            NativePokemonSet("Gyarados", "Gyarados", listOf("waterfall", "dragondance", "icefang", "taunt"),
                "Intimidate", uuid(1), item = "Sitrus Berry", level = 100),
        ),
        p2Team = listOf(
            NativePokemonSet("Garchomp", "Garchomp", listOf("scaleshot", "earthquake", "swordsdance", "rockslide"),
                "Rough Skin", uuid(2), item = "Life Orb", nature = "Jolly", level = 105,
                evs = evs("hp" to 4, "atk" to 252, "spe" to 252)),
            NativePokemonSet("Roserade", "Roserade", listOf("gigadrain", "toxicspikes", "sleeppowder", "sludgebomb"),
                "Natural Cure", uuid(3), item = "Focus Sash", nature = "Timid", level = 101,
                evs = evs("hp" to 4, "spa" to 252, "spe" to 252)),
        ),
    )

    @Test
    fun `a Lv105 team builds a world with Showdown's stats and plays a turn`() {
        val worker = EngineBranchWorker(cacheLimit = 1_000)
        val root = worker.createBattle(definition)
        val garchomp = root.p2Team.single { it.uuid == uuid(2) }
        assertEquals(105, garchomp.level)
        // Showdown: floor(floor(2*108 + 31 + 4/4 + 100) * 105 / 100) + 10 = 375.
        assertEquals(375, garchomp.maxHp)
        // floor((floor(2*102 + 31 + 252/4) * 105 / 100 + 5) * 1.1) = 348 for a Jolly Garchomp.
        assertEquals(348, garchomp.stats.getValue("spe"))
        assertEquals(101, root.p2Team.single { it.uuid == uuid(3) }.level)

        val next = worker.branch(root.snapshotJson, "move 1", "move 2")
        assertTrue(next.turn > root.turn) { "turn did not advance: ${root.turn} -> ${next.turn}" }
    }

    @Test
    fun `levels outside Showdown's clamp are still rejected`() {
        for (level in listOf(0, NativePokemonSet.MAX_SHOWDOWN_LEVEL + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                NativePokemonSet("Garchomp", "Garchomp", listOf("earthquake"), "Rough Skin", uuid(9), level = level)
            }
        }
    }
}
