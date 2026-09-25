package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.zip.ZipInputStream
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleOpeningState
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonOpeningState
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRuleRegistry
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRuleSource
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRulesGeneration
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownBranchEngine
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownChoiceEncoder
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeShowdownRequestActionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeShowdownBranchEngineTest {
    @Test
    fun `native request preserves choice lock after the first move`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(battle("Technician", "Choice Scarf"))
            val afterFirstMove = engine.branch(before.snapshotJson, "move 1", "move 1")

            val legal = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterFirstMove)

            assertEquals(setOf("bulletpunch"), legal.mapNotNull { it.moveId }.toSet())
            val encoded = legal.map { NativeShowdownChoiceEncoder.encode(it, BattleSide.ALLY, afterFirstMove) }.toSet()
            assertTrue(encoded.isNotEmpty() && encoded.all { it.startsWith("move 1") })
            assertTrue(encoded.none { it.startsWith("move 2") })
            assertEquals(3, engine.branch(afterFirstMove.snapshotJson, "move 1", "move 1").turn)
        }
    }

    @Test
    fun `native Tera choice changes type and survives the next branch depth`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(battle("Technician", teraType = "Fire"))
            val teraAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, before).single {
                it.moveId == "bulletpunch" && it.mechanic?.mechanicId == "tera"
            }
            val teraChoice = NativeShowdownChoiceEncoder.encode(teraAction, BattleSide.ALLY, before)

            assertEquals("Fire", before.p1Active.single().sourceSet?.teraType)
            assertEquals("move 1 terastallize", teraChoice)

            val afterTera = engine.branch(before.snapshotJson, teraChoice, "move 1")
            assertEquals(listOf("Fire"), afterTera.p1Active.single().types)
            assertEquals("Fire", afterTera.p1Active.single().sourceSet?.teraType)
            assertTrue(NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterTera).none {
                it.mechanic?.mechanicId == "tera"
            })

            val afterNextTurn = engine.branch(afterTera.snapshotJson, "move 1", "move 1")
            assertEquals(listOf("Fire"), afterNextTurn.p1Active.single().types)
            assertEquals("Fire", afterNextTurn.p1Active.single().sourceSet?.teraType)
        }
    }

    @Test
    fun `encoded double joint actions are accepted by native Showdown`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(doubleBattle())
            val p1Choices = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, before)
                .associateBy { NativeShowdownChoiceEncoder.encode(it, BattleSide.ALLY, before) }
            val p2Choices = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, before)
                .associateBy { NativeShowdownChoiceEncoder.encode(it, BattleSide.OPPONENT, before) }
            val p1Choice = "move 1 1, move 1 2"
            val p2Choice = "move 1, move 1"
            assertTrue(p1Choice in p1Choices, "Native p1 request must generate both selected targets")
            assertTrue(p2Choice in p2Choices, "Native p2 request must generate its self-target moves")

            val after = engine.branch(before.snapshotJson, p1Choice, p2Choice)

            assertEquals("move 1 1, move 1 2", p1Choice)
            assertEquals("move 1, move 1", p2Choice)
            assertEquals(2, after.turn)
            assertTrue(after.p2Team.any { it.hp < it.maxHp }, "Encoded target slots must reach native damage resolution")
        }
    }

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
            assertEquals(50, technician.p1Active.single().level)
            assertTrue(technician.p1Active.single().stats.values.all { it > 0 })
            assertEquals(70, technician.p1Active.single().sourceSet?.openingHp)
            assertEquals(145, technician.p1Active.single().sourceSet?.openingMaxHp)
            assertEquals("", technician.p1Active.single().sourceSet?.openingStatus)
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
            assertEquals("raindance", drizzle.field.weather?.id)

            val boosted = engine.branch(neutral.snapshotJson, "move 2", "move 1")
            assertEquals(2, boosted.p1Active.single().boosts["atk"])

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
    fun `native frame preserves source set after imposter mutates the live Pokemon`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val frame = engine.createBattle(imposterBattle())
            val transformed = frame.p1Active.single()

            assertEquals("ditto", transformed.sourceSet?.species?.lowercase())
            assertEquals("imposter", transformed.sourceSet?.ability?.lowercase())
            assertEquals(listOf("transform"), transformed.sourceSet?.moves?.map { it.lowercase() })
            assertEquals("serious", transformed.sourceSet?.nature?.lowercase())
            assertEquals("M", transformed.sourceSet?.gender)
            assertEquals(setOf("hp", "atk", "def", "spa", "spd", "spe"), transformed.sourceSet?.evs?.keys)
            assertEquals(setOf(0), transformed.sourceSet?.evs?.values?.toSet())
            assertEquals(setOf(31), transformed.sourceSet?.ivs?.values?.toSet())
            assertTrue(
                transformed.species != "ditto" || transformed.ability != "imposter" ||
                    transformed.moves.map { it.id } != listOf("transform"),
                "The test must exercise a callback-mutated live Pokemon",
            )
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
            assertEquals(technicianAfter.snapshotJson, technicianReplay.snapshotJson,
                "The same root, choices and PRNG state must produce one cache-stable snapshot")
            assertTrue(technicianAfter.log.filter { it.startsWith("|t:|") }.all { it == "|t:|0" })
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

    private fun battle(
        ability: String,
        item: String = "",
        species: String = "Scizor",
        teraType: String? = null,
    ) = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(17, 29, 41, 53),
        p1Team = listOf(
            NativePokemonSet(
                name = "Actor",
                species = species,
                moves = listOf("bulletpunch", "swordsdance"),
                ability = ability,
                item = item,
                teraType = teraType,
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

    private fun doubleBattle() = NativeBattleDefinition(
        formatId = "cobblemondoubles",
        seed = listOf(7, 11, 13, 17),
        p1Team = listOf(
            nativeSet("P1 Left", "Raichu", "tackle", "00000000-0000-0000-0000-000000000011"),
            nativeSet("P1 Right", "Pikachu", "tackle", "00000000-0000-0000-0000-000000000012"),
        ),
        p2Team = listOf(
            nativeSet("P2 Left", "Mew", "splash", "00000000-0000-0000-0000-000000000021"),
            nativeSet("P2 Right", "Mew", "splash", "00000000-0000-0000-0000-000000000022"),
        ),
    )

    private fun imposterBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(19, 23, 29, 31),
        p1Team = listOf(nativeSet("Imposter", "Ditto", "transform", "00000000-0000-0000-0000-000000000031", "Imposter")),
        p2Team = listOf(nativeSet("Target", "Mew", "splash", "00000000-0000-0000-0000-000000000032")),
    )

    private fun nativeSet(
        name: String,
        species: String,
        move: String,
        uuid: String,
        ability: String = "Synchronize",
    ) = NativePokemonSet(
        name = name,
        species = species,
        moves = listOf(move),
        ability = ability,
        uuid = uuid,
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
