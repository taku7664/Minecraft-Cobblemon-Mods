package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleOpeningState
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonOpeningState
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRuleRegistry
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRuleSource
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRulesGeneration
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeShowdownBranchEngineTest {
    @Test
    fun `public opening state rejects history-dependent status without counters`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            openingState("Technician", "Leftovers", status = "slp")
        }

        assertTrue(failure.message.orEmpty().contains("hidden counters"))
    }

    @Test
    fun `public opening state rejects backing set disagreement`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            battle("Technician", "Leftovers").copy(
                openingState = openingState("No Ability", ""),
            )
        }

        assertTrue(failure.message.orEmpty().contains("same public hypothesis"))
    }

    @Test
    fun `public opening state rejects max hp inconsistent with the synthetic set`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val failure = assertThrows(RuntimeException::class.java) {
                engine.createBattle(
                    battle("Technician").copy(
                        openingState = openingState("Technician", "", maxHp = 146),
                    ),
                )
            }

            assertTrue(failure.message.orEmpty().contains("Opening max HP disagrees with synthetic set"))
        }
    }

    @Test
    fun `public opening state reaches callbacks before the first request`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val technician = engine.createBattle(
                battle("Technician", "Leftovers").copy(openingState = openingState("Technician", "Leftovers")),
            )
            val neutral = engine.createBattle(
                battle("No Ability").copy(openingState = openingState("No Ability", "")),
            )
            val drizzle = engine.createBattle(
                battle("Drizzle").copy(openingState = openingState("Drizzle", "")),
            )
            val schooling = engine.createBattle(
                battle("Schooling", species = "Wishiwashi").copy(
                    openingState = openingState("Schooling", "", hp = 20, maxHp = 120),
                ),
            )

            assertEquals(1, technician.turn)
            assertEquals(70, technician.p1Active.single().hp)
            assertEquals(145, technician.p1Active.single().maxHp)
            assertEquals("technician", technician.p1Active.single().ability)
            assertEquals("leftovers", technician.p1Active.single().item)
            assertEquals("move", technician.requestState, "Showdown must create the request after opening callbacks")
            assertEquals(20, schooling.p1Active.single().hp, "Showdown must construct the lead at public pre-battle HP")
            assertEquals(
                "wishiwashi",
                schooling.p1Active.single().species,
                "HP-dependent opening callbacks must see the public pre-battle HP; log=${schooling.log}",
            )
            assertEquals(
                "raindance",
                JsonParser.parseString(drizzle.snapshotJson).asJsonObject
                    .getAsJsonObject("field").get("weather").asString,
                "Opening ability callbacks must be executed by native Showdown",
            )

            val technicianAfter = engine.branch(technician.snapshotJson, "move 1", "move 1")
            val neutralAfter = engine.branch(neutral.snapshotJson, "move 1", "move 1")
            val technicianDamage = technician.p2Active.single().hp - technicianAfter.p2Active.single().hp
            val neutralDamage = neutral.p2Active.single().hp - neutralAfter.p2Active.single().hp

            assertEquals(2, technicianAfter.turn)
            assertEquals(79, technicianAfter.p1Active.single().hp, "Hydrated Leftovers must run in native residuals")
            assertEquals(70, neutralAfter.p1Active.single().hp)
            assertTrue(technicianDamage > neutralDamage, "Hydrated Technician must run in native damage callbacks")
        }
    }

    @Test
    fun `product Graal branch executes bundled ability callbacks`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val technician = engine.createBattle(battle("Technician"))
            val swarm = engine.createBattle(battle("Swarm"))

            val technicianAfter = engine.branch(technician.snapshotJson, "move 1", "move 1")
            val technicianReplay = engine.branch(technician.snapshotJson, "move 1", "move 1")
            val swarmAfter = engine.branch(swarm.snapshotJson, "move 1", "move 1")
            val technicianDamage = technician.p2Active.single().hp - technicianAfter.p2Active.single().hp
            val swarmDamage = swarm.p2Active.single().hp - swarmAfter.p2Active.single().hp

            assertEquals("move", technician.requestState)
            assertEquals(1, technician.turn)
            assertEquals(2, technicianAfter.turn)
            assertEquals(technicianAfter.p1Active, technicianReplay.p1Active)
            assertEquals(technicianAfter.p2Active, technicianReplay.p2Active)
            assertTrue(technicianDamage > swarmDamage, "Technician must be executed by native Showdown")
        }
    }

    @Test
    fun `runtime rule source keeps JavaScript callback in isolated context`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        val rules = NativeRulesGeneration.capture(
            engineRoot,
            listOf(
                NativeRuleSource(
                    registry = NativeRuleRegistry.ABILITY,
                    id = "mbctestpower",
                    javaScript = """({
                        name: "MBC Test Power",
                        // A raw runtime source may contain line comments; replay must preserve line boundaries.
                        onModifyAtk(atk) { return this.chainModify(2); },
                        flags: {}, rating: 1, num: -999
                    })""".trimIndent(),
                ),
            ),
        )
        rules.use {
            Files.writeString(engineRoot.resolve("index.js"), "throw new Error('mutated source root');")
            NativeShowdownBranchEngine.open(engineRoot, rules).use { engine ->
                val boosted = engine.createBattle(battle("MBC Test Power"))
                val neutral = engine.createBattle(battle("No Ability"))
                val boostedAfter = engine.branch(boosted.snapshotJson, "move 1", "move 1")
                val neutralAfter = engine.branch(neutral.snapshotJson, "move 1", "move 1")

                val boostedDamage = boosted.p2Active.single().hp - boostedAfter.p2Active.single().hp
                val neutralDamage = neutral.p2Active.single().hp - neutralAfter.p2Active.single().hp
                assertEquals(rules.fingerprint, engine.rulesFingerprint)
                assertTrue(boostedDamage > neutralDamage, "Runtime ability callback must survive rule replay")
            }
        }
    }

    @Test
    fun `patched index receives specialized runtime registries without rewriting source`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        Files.writeString(
            engineRoot.resolve("index.js"),
            """
            globalThis.receiveScriptData = function(id, source) {
              if (!source.includes("\n")) throw new Error("SCRIPT source lost line boundaries");
            };
            globalThis.receiveConditionData = function(id, source) {
              if (!source.includes("\n")) throw new Error("CONDITION source lost line boundaries");
            };
            globalThis.receiveTypeChartData = function(id, source) {
              if (!source.includes("\n")) throw new Error("TYPE_CHART source lost line boundaries");
            };
            """.trimIndent(),
            APPEND,
        )
        val rules = NativeRulesGeneration.capture(
            engineRoot,
            listOf(
                NativeRuleSource(NativeRuleRegistry.SCRIPT, "gen9", "({\n// script\nonBegin() {}\n})"),
                NativeRuleSource(NativeRuleRegistry.CONDITION, "testcondition", "({\n// condition\nonStart() {}\n})"),
                NativeRuleSource(NativeRuleRegistry.TYPE_CHART, "testtype", "({\n// type\ndamageTaken: {}\n})"),
            ),
        )
        rules.use {
            NativeShowdownBranchEngine.open(engineRoot, rules).use { engine ->
                assertEquals("move", engine.createBattle(battle("Technician")).requestState)
            }
        }
    }

    @Test
    fun `specialized runtime registry is rejected when patched receiver is missing`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        val rules = NativeRulesGeneration.capture(
            engineRoot,
            listOf(NativeRuleSource(NativeRuleRegistry.SCRIPT, "gen9", "({ inherit: 'gen9' })")),
        )
        rules.use {
            val failure = assertThrows(RuntimeException::class.java) {
                NativeShowdownBranchEngine.open(engineRoot, rules).close()
            }
            assertTrue(failure.message.orEmpty().contains("receiveScriptData"))
        }
    }

    private fun battle(ability: String, item: String = "", species: String = "Scizor") = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(17, 29, 41, 53),
        p1Team = listOf(
            NativePokemonSet(
                name = "Actor",
                species = species,
                moves = listOf("bulletpunch", "swordsdance"),
                ability = ability,
                item = item,
                uuid = "00000000-0000-0000-0000-000000000001",
            ),
        ),
        p2Team = listOf(
            NativePokemonSet(
                name = "Target",
                species = "Mew",
                moves = listOf("splash"),
                ability = "Synchronize",
                uuid = "00000000-0000-0000-0000-000000000002",
            ),
        ),
    )

    private fun openingState(
        ability: String,
        item: String,
        hp: Int = 70,
        maxHp: Int = 145,
        status: String = "",
    ) = NativeBattleOpeningState(
        pokemon = listOf(
            NativePokemonOpeningState(
                uuid = "00000000-0000-0000-0000-000000000001",
                hp = hp,
                maxHp = maxHp,
                ability = ability,
                item = item,
                status = status,
            ),
            NativePokemonOpeningState(
                uuid = "00000000-0000-0000-0000-000000000002",
                hp = 175,
                maxHp = 175,
                ability = "Synchronize",
            ),
        ),
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
                            require(extracted <= 128L * 1024 * 1024) { "Embedded archive exceeds extraction limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return targetRoot.toAbsolutePath().normalize()
    }
}
