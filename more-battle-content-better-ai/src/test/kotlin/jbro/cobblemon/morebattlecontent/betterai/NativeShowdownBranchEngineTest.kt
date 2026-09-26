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
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveSetRebinding
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
    fun `native branch reports move recoil separately from damage`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val definition = battle("Technician").copy(
                p1Team = battle("Technician").p1Team.map { it.copy(moves = listOf("doubleedge")) },
            )
            val before = engine.createBattle(definition)
            val after = engine.branch(before.snapshotJson, "move 1", "move 1")

            assertTrue(after.p1Active.single().hp < before.p1Active.single().hp)
            assertEquals(
                (before.p1Active.single().hp - after.p1Active.single().hp).toDouble() / before.p1Active.single().maxHp,
                after.recoilLossP1,
                1e-9,
            )
            assertEquals(0.0, after.recoilLossP2, 1e-9)
        }
    }

    @Test
    fun `native snapshot retains the authoritative executed move order`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(battle("Technician"))

            val first = engine.branch(before.snapshotJson, "move 1", "move 1")
            val second = engine.branch(first.snapshotJson, "move 1", "move 1")

            assertEquals(
                listOf(
                    Triple(1, "00000000-0000-0000-0000-000000000001", "bulletpunch"),
                    Triple(1, "00000000-0000-0000-0000-000000000002", "splash"),
                ),
                first.executedMoveOrder.map { Triple(it.turn, it.pokemonUuid, it.moveId) },
            )
            assertEquals(
                listOf(1, 1, 2, 2),
                second.executedMoveOrder.map { it.turn },
                "The structured order ledger must survive Battle toJSON and fromJSON",
            )
        }
    }

    @Test
    fun `move hypothesis rebinding preserves PP and an existing choice lock`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(battle("Technician", "Choice Scarf"))
            val afterFirstMove = engine.branch(before.snapshotJson, "move 1", "move 1")
            val bulletPunchPp = afterFirstMove.p1Active.single().moves.single { it.id == "bulletpunch" }.pp
            val beforeRebindSnapshot = JsonParser.parseString(afterFirstMove.snapshotJson).asJsonObject

            val rebound = engine.rebindMoves(
                afterFirstMove.snapshotJson,
                listOf(NativeMoveSetRebinding(
                    pokemonUuid = "00000000-0000-0000-0000-000000000001",
                    expectedMoveIds = listOf("bulletpunch", "swordsdance"),
                    replacementMoveIds = listOf("bulletpunch", "protect"),
                )),
            )
            val afterRebindSnapshot = JsonParser.parseString(rebound.snapshotJson).asJsonObject
            val beforePokemon = afterFirstMove.p1Active.single()
            val afterPokemon = rebound.p1Active.single()

            assertEquals(listOf("bulletpunch", "protect"), rebound.p1Active.single().moves.map { it.id })
            assertEquals(
                bulletPunchPp,
                rebound.p1Active.single().moves.single { it.id == "bulletpunch" }.pp,
                "An unchanged revealed move must retain its consumed PP",
            )
            assertEquals(listOf("bulletpunch", "protect"), rebound.p1Active.single().sourceSet?.moves)
            assertEquals(
                setOf("bulletpunch"),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, rebound)
                    .mapNotNull { it.moveId }
                    .toSet(),
                "Rebinding another slot must not erase the active Choice lock",
            )
            assertTrue(
                beforeRebindSnapshot.has("prng"),
                "Native snapshots must expose the authoritative battle PRNG",
            )
            assertEquals(
                beforeRebindSnapshot.get("prng"),
                afterRebindSnapshot.get("prng"),
                "Request legality probing must not consume the authoritative battle PRNG",
            )
            assertEquals(afterFirstMove.turn, rebound.turn)
            assertEquals(afterFirstMove.field, rebound.field)
            assertEquals(afterFirstMove.p2Team, rebound.p2Team)
            assertEquals(beforePokemon.hp, afterPokemon.hp)
            assertEquals(beforePokemon.maxHp, afterPokemon.maxHp)
            assertEquals(beforePokemon.status, afterPokemon.status)
            assertEquals(beforePokemon.ability, afterPokemon.ability)
            assertEquals(beforePokemon.item, afterPokemon.item)
            assertEquals(beforePokemon.types, afterPokemon.types)
            assertEquals(beforePokemon.boosts, afterPokemon.boosts)
            assertEquals(beforePokemon.volatiles, afterPokemon.volatiles)
            assertEquals(beforePokemon.stats, afterPokemon.stats)
        }
    }

    @Test
    fun `move hypothesis rebinding rejects a transformed live move set`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val transformed = engine.createBattle(imposterBattle())

            val failure = assertThrows(RuntimeException::class.java) {
                engine.rebindMoves(
                    transformed.snapshotJson,
                    listOf(NativeMoveSetRebinding(
                        pokemonUuid = "00000000-0000-0000-0000-000000000031",
                        expectedMoveIds = listOf("transform"),
                        replacementMoveIds = listOf("protect"),
                    )),
                )
            }

            assertTrue(failure.message.orEmpty().contains("Unsafe history-sensitive move-set rebinding"))
        }
    }

    @Test
    fun `move hypothesis rebinding cannot erase a move referenced by native history`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(battle("Technician"))
            val afterBulletPunch = engine.branch(before.snapshotJson, "move 1", "move 1")

            val failure = assertThrows(RuntimeException::class.java) {
                engine.rebindMoves(
                    afterBulletPunch.snapshotJson,
                    listOf(NativeMoveSetRebinding(
                        pokemonUuid = "00000000-0000-0000-0000-000000000001",
                        expectedMoveIds = listOf("bulletpunch", "swordsdance"),
                        replacementMoveIds = listOf("protect", "swordsdance"),
                    )),
                )
            }

            assertTrue(failure.message.orEmpty().contains("erase referenced move history"))
        }
    }

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
    fun `native request maps a choice lock on the second source slot`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(battle("Technician", "Choice Scarf"))
            val afterSecondMove = engine.branch(before.snapshotJson, "move 2", "move 1")

            val legal = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterSecondMove)

            assertEquals(setOf("swordsdance"), legal.mapNotNull { it.moveId }.toSet())
            assertTrue(legal.map {
                NativeShowdownChoiceEncoder.encode(it, BattleSide.ALLY, afterSecondMove)
            }.all { it.startsWith("move 2") })
        }
    }

    @Test
    fun `switching clears a choice lock and the next used move creates a fresh lock`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val opening = engine.createBattle(choiceSwitchBattle())
            val locked = engine.branch(opening.snapshotJson, "move 1", "move 1")
            assertEquals(
                setOf("bulletpunch"),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, locked).mapNotNull { it.moveId }.toSet(),
            )

            val toBench = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, locked)
                .single { it.switchPokemonId?.toString()?.endsWith("0003") == true }
            val benched = engine.branch(
                locked.snapshotJson,
                NativeShowdownChoiceEncoder.encode(toBench, BattleSide.ALLY, locked),
                "move 1",
            )
            val back = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, benched)
                .single { it.switchPokemonId?.toString()?.endsWith("0001") == true }
            val returned = engine.branch(
                benched.snapshotJson,
                NativeShowdownChoiceEncoder.encode(back, BattleSide.ALLY, benched),
                "move 1",
            )

            assertEquals(
                setOf("bulletpunch", "swordsdance"),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, returned).mapNotNull { it.moveId }.toSet(),
                "A returned Choice holder must not retain its pre-switch lock",
            )
            val relocked = engine.branch(returned.snapshotJson, "move 2", "move 1")
            assertEquals(
                setOf("swordsdance"),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, relocked).mapNotNull { it.moveId }.toSet(),
            )
        }
    }

    @Test
    fun `trick removing a choice item immediately exposes every move next request`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val opening = engine.createBattle(choiceTrickBattle())
            assertEquals(
                setOf("trick", "psychic"),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, opening).mapNotNull { it.moveId }.toSet(),
                "A Choice item must not lock a move before the holder has acted",
            )

            val afterTrick = engine.branch(opening.snapshotJson, "move 1", "move 1")

            assertEquals("leftovers", afterTrick.p1Active.single().item)
            assertEquals("choicescarf", afterTrick.p2Active.single().item)
            assertEquals(
                setOf("trick", "psychic"),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterTrick).mapNotNull { it.moveId }.toSet(),
                "Losing the Choice item must stop its old lock from disabling moves",
            )
            assertEquals(
                setOf("splash"),
                NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, afterTrick).mapNotNull { it.moveId }.toSet(),
                "The recipient used Splash while holding the received Scarf and must be locked by native rules",
            )
        }
    }

    @Test
    fun `knock off removing a choice item clears its native move restriction`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val opening = engine.createBattle(choiceRemovalBattle())
            val afterRemoval = engine.branch(opening.snapshotJson, "move 1", "move 1")

            assertEquals("", afterRemoval.p1Active.single().item)
            assertEquals(
                setOf("tackle", "swordsdance"),
                NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterRemoval)
                    .mapNotNull { it.moveId }.toSet(),
                "A native Choice lock must stop disabling moves as soon as Knock Off removes the item",
            )
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
            assertEquals(setOf("Bug", "Steel"), afterTera.p1Active.single().baseStabTypes.toSet())
            assertEquals("Fire", afterTera.p1Active.single().terastallizedType)
            assertEquals("Fire", afterTera.p1Active.single().sourceSet?.teraType)
            assertTrue(NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, afterTera).none {
                it.mechanic?.mechanicId == "tera"
            })

            val afterNextTurn = engine.branch(afterTera.snapshotJson, "move 1", "move 1")
            assertEquals(listOf("Fire"), afterNextTurn.p1Active.single().types)
            assertEquals(setOf("Bug", "Steel"), afterNextTurn.p1Active.single().baseStabTypes.toSet())
            assertEquals("Fire", afterNextTurn.p1Active.single().terastallizedType)
            assertEquals("Fire", afterNextTurn.p1Active.single().sourceSet?.teraType)
        }
    }

    @Test
    fun `native Tera clears an added type from retained base stab`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(addedTypeBeforeTeraBattle())
            val setup = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, before).single {
                it.moveId == "swordsdance" && it.mechanic == null
            }
            val addType = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, before).single {
                it.moveId == "forestscurse" && it.mechanic == null
            }
            val withAddedType = engine.branch(
                before.snapshotJson,
                NativeShowdownChoiceEncoder.encode(setup, BattleSide.ALLY, before),
                NativeShowdownChoiceEncoder.encode(addType, BattleSide.OPPONENT, before),
            )

            assertEquals(setOf("Bug", "Steel", "Grass"), withAddedType.p1Active.single().types.toSet())
            assertEquals(setOf("Bug", "Steel", "Grass"), withAddedType.p1Active.single().baseStabTypes.toSet())

            val tera = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, withAddedType).single {
                it.moveId == "bulletpunch" && it.mechanic?.mechanicId == "tera"
            }
            val wait = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, withAddedType).single {
                it.moveId == "splash" && it.mechanic == null
            }
            val afterTera = engine.branch(
                withAddedType.snapshotJson,
                NativeShowdownChoiceEncoder.encode(tera, BattleSide.ALLY, withAddedType),
                NativeShowdownChoiceEncoder.encode(wait, BattleSide.OPPONENT, withAddedType),
            )

            assertEquals(listOf("Fire"), afterTera.p1Active.single().types)
            assertEquals(setOf("Bug", "Steel"), afterTera.p1Active.single().baseStabTypes.toSet())
            assertEquals("Fire", afterTera.p1Active.single().terastallizedType)
        }
    }

    @Test
    fun `native Stellar records each boosted move type only after its first use`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(stellarBattle())
            assertTrue(before.p1Active.single().stellarBoostedTypes.isEmpty())
            val firstPsychic = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, before).single {
                it.moveId == "psychic" && it.mechanic?.mechanicId == "tera"
            }
            val first = engine.branch(
                before.snapshotJson,
                NativeShowdownChoiceEncoder.encode(firstPsychic, BattleSide.ALLY, before),
                "move 1",
            )
            assertEquals("Stellar", first.p1Active.single().terastallizedType)
            assertEquals(listOf("Psychic"), first.p1Active.single().stellarBoostedTypes)

            val repeated = engine.branch(first.snapshotJson, "move 1", "move 1")
            assertEquals(listOf("Psychic"), repeated.p1Active.single().stellarBoostedTypes)

            val secondType = engine.branch(repeated.snapshotJson, "move 2", "move 1")
            assertEquals(setOf("Psychic", "Electric"), secondType.p1Active.single().stellarBoostedTypes.toSet())
        }
    }

    @Test
    fun `native Terapagos Stellar keeps every type boost reusable`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(terapagosStellarBattle())
            val firstTackle = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, before).single {
                it.moveId == "tackle" && it.mechanic?.mechanicId == "tera"
            }
            val first = engine.branch(
                before.snapshotJson,
                NativeShowdownChoiceEncoder.encode(firstTackle, BattleSide.ALLY, before),
                "move 1",
            )
            assertEquals("terapagosstellar", first.p1Active.single().species)
            assertEquals("Stellar", first.p1Active.single().terastallizedType)
            assertTrue(first.p1Active.single().stellarBoostedTypes.isEmpty())

            val repeated = engine.branch(first.snapshotJson, "move 1", "move 1")
            assertTrue(repeated.p1Active.single().stellarBoostedTypes.isEmpty())
        }
    }

    @Test
    fun `native stance change updates form without changing its base stab types`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(stanceChangeBattle())
            assertTrue(before.p1Active.single().species.contains("blade"))
            val kingShield = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, before).single {
                it.moveId == "kingsshield" && it.mechanic == null
            }

            val after = engine.branch(
                before.snapshotJson,
                NativeShowdownChoiceEncoder.encode(kingShield, BattleSide.ALLY, before),
                "move 1",
            )

            assertEquals("aegislash", after.p1Active.single().species)
            assertEquals(setOf("Steel", "Ghost"), after.p1Active.single().types.toSet())
            assertEquals(setOf("Steel", "Ghost"), after.p1Active.single().baseStabTypes.toSet())
            assertEquals("", after.p1Active.single().terastallizedType)
        }
    }

    @Test
    fun `native Ogerpon Tera changes form while preserving its base stab types`(@TempDir directory: Path) {
        val engineRoot = extractBundledShowdown(directory.resolve("showdown"))
        NativeShowdownBranchEngine.open(engineRoot).use { engine ->
            val before = engine.createBattle(ogerponTeraBattle())
            val teraAction = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, before).single {
                it.moveId == "ivycudgel" && it.mechanic?.mechanicId == "tera"
            }

            val after = engine.branch(
                before.snapshotJson,
                NativeShowdownChoiceEncoder.encode(teraAction, BattleSide.ALLY, before),
                "move 1",
            )

            assertEquals("ogerponhearthflametera", after.p1Active.single().species)
            assertEquals(listOf("Fire"), after.p1Active.single().types)
            assertEquals(setOf("Grass", "Fire"), after.p1Active.single().baseStabTypes.toSet())
            assertEquals("Fire", after.p1Active.single().terastallizedType)
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

    private fun choiceSwitchBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(307, 311, 313, 317),
        p1Team = listOf(
            NativePokemonSet(
                name = "Choice Actor",
                species = "Scizor",
                moves = listOf("bulletpunch", "swordsdance"),
                ability = "technician",
                item = "choicescarf",
                uuid = "00000000-0000-0000-0000-000000000001",
            ),
            NativePokemonSet(
                name = "Bench",
                species = "Mew",
                moves = listOf("splash"),
                ability = "synchronize",
                uuid = "00000000-0000-0000-0000-000000000003",
            ),
        ),
        p2Team = listOf(NativePokemonSet(
            name = "Observer",
            species = "Blissey",
            moves = listOf("splash"),
            ability = "naturalcure",
            uuid = "00000000-0000-0000-0000-000000000002",
        )),
    )

    private fun choiceTrickBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(331, 337, 347, 349),
        p1Team = listOf(NativePokemonSet(
            name = "Choice Trick",
            species = "Mew",
            moves = listOf("trick", "psychic"),
            ability = "synchronize",
            item = "choicescarf",
            uuid = "00000000-0000-0000-0000-000000000001",
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Recipient",
            species = "Blissey",
            moves = listOf("splash", "softboiled"),
            ability = "naturalcure",
            item = "leftovers",
            uuid = "00000000-0000-0000-0000-000000000002",
        )),
    )

    private fun choiceRemovalBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(353, 359, 367, 373),
        p1Team = listOf(NativePokemonSet(
            name = "Choice Holder",
            species = "Mew",
            moves = listOf("tackle", "swordsdance"),
            ability = "synchronize",
            item = "choicescarf",
            uuid = "00000000-0000-0000-0000-000000000001",
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Remover",
            species = "Shuckle",
            moves = listOf("knockoff"),
            ability = "sturdy",
            uuid = "00000000-0000-0000-0000-000000000002",
        )),
    )

    private fun stanceChangeBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(211, 223, 227, 229),
        p1Team = listOf(NativePokemonSet(
            name = "Blade",
            species = "Aegislash-Blade",
            moves = listOf("kingsshield", "shadowball", "ironhead", "powergem"),
            ability = "stancechange",
            uuid = "00000000-0000-0000-0000-000000000001",
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Observer",
            species = "Shuckle",
            moves = listOf("splash"),
            ability = "sturdy",
            uuid = "00000000-0000-0000-0000-000000000002",
        )),
    )

    private fun addedTypeBeforeTeraBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(71, 73, 79, 83),
        p1Team = listOf(NativePokemonSet(
            name = "Tera Actor",
            species = "Scizor",
            moves = listOf("bulletpunch", "swordsdance"),
            ability = "technician",
            teraType = "Fire",
            uuid = "00000000-0000-0000-0000-000000000001",
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Type Adder",
            species = "Mew",
            moves = listOf("forestscurse", "splash"),
            ability = "synchronize",
            uuid = "00000000-0000-0000-0000-000000000002",
        )),
    )

    private fun stellarBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(89, 97, 101, 103),
        p1Team = listOf(NativePokemonSet(
            name = "Stellar Actor",
            species = "Mew",
            moves = listOf("psychic", "thunderbolt"),
            ability = "synchronize",
            teraType = "Stellar",
            uuid = "00000000-0000-0000-0000-000000000001",
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Observer",
            species = "Blissey",
            moves = listOf("splash"),
            ability = "naturalcure",
            uuid = "00000000-0000-0000-0000-000000000002",
        )),
    )

    private fun terapagosStellarBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(107, 109, 113, 127),
        p1Team = listOf(NativePokemonSet(
            name = "Terapagos",
            species = "Terapagos-Terastal",
            moves = listOf("tackle"),
            ability = "terashell",
            teraType = "Stellar",
            uuid = "00000000-0000-0000-0000-000000000001",
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Observer",
            species = "Blissey",
            moves = listOf("splash"),
            ability = "naturalcure",
            uuid = "00000000-0000-0000-0000-000000000002",
        )),
    )

    private fun ogerponTeraBattle() = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(257, 263, 269, 271),
        p1Team = listOf(NativePokemonSet(
            name = "Mask",
            species = "Ogerpon-Hearthflame",
            moves = listOf("ivycudgel", "hornleech"),
            ability = "moldbreaker",
            item = "hearthflamemask",
            teraType = "Fire",
            uuid = "00000000-0000-0000-0000-000000000001",
        )),
        p2Team = listOf(NativePokemonSet(
            name = "Observer",
            species = "Shuckle",
            moves = listOf("splash"),
            ability = "sturdy",
            uuid = "00000000-0000-0000-0000-000000000002",
        )),
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
