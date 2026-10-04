package jbro.cobblemon.mcc.betterai.engine

import java.util.Locale
import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How fast the engine branches, against the numbers that sank the GraalJS path (about 49 ms per branch,
 * 2.7 ms on Node). Prints the measurement; fails only if a branch gets slower than Node's Showdown.
 */
class EngineSpeedTest {
    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun set(n: Int, species: String, ability: String, item: String, vararg moves: String) =
        NativePokemonSet(species, species, moves.toList(), ability, uuid(n), item = item)

    private val definition = NativeBattleDefinition(
        formatId = "cobblemonsingles", seed = listOf(1, 2, 3, 4),
        p1Team = listOf(
            set(1, "Garchomp", "Rough Skin", "Life Orb", "earthquake", "dragonclaw", "stoneedge", "swordsdance"),
            set(2, "Rotom-Wash", "Levitate", "Leftovers", "hydropump", "voltswitch", "willowisp", "protect"),
            set(3, "Gholdengo", "Good as Gold", "Choice Specs", "makeitrain", "shadowball", "trick", "focusblast"),
        ),
        p2Team = listOf(
            set(4, "Gyarados", "Intimidate", "Sitrus Berry", "waterfall", "dragondance", "icefang", "taunt"),
            set(5, "Kingambit", "Defiant", "Black Glasses", "kowtowcleave", "suckerpunch", "ironhead", "swordsdance"),
            set(6, "Amoonguss", "Regenerator", "Rocky Helmet", "spore", "gigadrain", "clearsmog", "protect"),
        ),
    )

    @Test
    fun `a branch costs well under Node's Showdown`() {
        val choices = listOf("move 1", "move 2", "move 3", "move 4", "switch 2", "switch 3")
        val worker = EngineBranchWorker(cacheLimit = 100_000)
        val root = worker.createBattle(definition)
        var branches = 0
        repeat(3) { worker.branch(root.snapshotJson, "move 1", "move 1") } // warm-up
        val started = System.nanoTime()
        repeat(20) {
            for (a in choices) for (b in choices) {
                worker.branch(root.snapshotJson, a, b)
                branches++
            }
        }
        val perBranchMs = (System.nanoTime() - started) / 1e6 / branches
        println(String.format(Locale.ROOT, "engine branch: %.3f ms over %d branches (GraalJS ~49 ms, Node ~2.7 ms)", perBranchMs, branches))
        assertTrue(perBranchMs < 2.7) { "Engine branch took $perBranchMs ms" }
    }
}
