package jbro.cobblemon.morebattlecontent.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeDamageRollLedgerTest {
    @Test
    fun `native observed branch exposes Showdown direct damage roll support`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(battle())

            val first = engine.branchWithDamageEvidence(before.snapshotJson, "move 1", "move 1")
            assertTrue(first.executedDamageRolls.isNotEmpty(), first.log.joinToString("\n"))
            val roll = first.executedDamageRolls.single()

            assertEquals(1, roll.turn)
            assertEquals(ALLY, roll.attackerPokemonUuid)
            assertEquals(OPPONENT, roll.targetPokemonUuid)
            assertEquals("bulletpunch", roll.moveId)
            assertEquals(16, roll.possibleHpLosses.size)
            assertTrue(roll.actualHpLoss in roll.possibleHpLosses)
            assertEquals(before.p2Active.single().hp, roll.hpBefore)
            assertEquals(before.p2Active.single().maxHp, roll.maxHp)

            val second = engine.branchWithDamageEvidence(first.snapshotJson, "move 1", "move 1")
            assertEquals(1, second.executedDamageRolls.size)
            assertEquals(2, second.executedDamageRolls.single().turn)
            assertTrue(
                "mbcExecutedDamageRolls" !in second.snapshotJson,
                "Observation diagnostics must not contaminate retained Showdown state",
            )
        }
    }

    private fun battle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(17, 29, 41, 53),
        p1Team = listOf(NativePokemonSet(
            name = "Actor",
            species = "Scizor",
            moves = listOf("bulletpunch"),
            ability = "technician",
            uuid = ALLY,
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Target",
            species = "Mew",
            moves = listOf("splash"),
            ability = "synchronize",
            uuid = OPPONENT,
        )),
    )

    private fun extractBundledShowdown(targetRoot: Path): Path {
        Files.createDirectories(targetRoot)
        val resource = requireNotNull(javaClass.getResourceAsStream("/data/cobblemon/showdown.zip")) {
            "Cobblemon embedded Showdown is missing from the test runtime"
        }
        var extracted = 0L
        ZipInputStream(resource).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = EmbeddedShowdownOracle.entryPath(targetRoot, entry.name)
                if (!entry.isDirectory && (entry.name.endsWith(".js") || entry.name.endsWith(".json"))) {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, CREATE_NEW).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extracted += count
                            require(extracted <= 128L * 1024 * 1024) {
                                "Embedded archive exceeds extraction limit"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return targetRoot.toAbsolutePath().normalize()
    }

    private companion object {
        const val ALLY = "00000000-0000-0000-0000-000000000001"
        const val OPPONENT = "00000000-0000-0000-0000-000000000002"
    }
}
