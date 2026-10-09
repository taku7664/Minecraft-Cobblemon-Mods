package jbro.cobblemon.mcc.betterai.engine

import jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleSituation
import jbro.cobblemon.mcc.betterai.simulation.NativeDamageStat
import jbro.cobblemon.mcc.betterai.simulation.NativeEffectSituation
import jbro.cobblemon.mcc.betterai.simulation.NativeForcedDamageRoll
import jbro.cobblemon.mcc.betterai.simulation.NativeStatChange
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSituation
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownPublicHp
import jbro.cobblemon.mcc.betterai.simulation.NativeShowdownRequestActionFactory
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A mid-battle position rebuilt over a started battle, as native search does after the public board left its worlds. */
class EngineBranchWorkerSituationTest {
    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private val definition = NativeBattleDefinition(
        formatId = "cobblemonsingles",
        seed = listOf(11, 22, 33, 44),
        p1Team = listOf(
            NativePokemonSet("Garchomp", "Garchomp", listOf("earthquake", "dragonclaw", "stoneedge", "swordsdance"), "Rough Skin", uuid(1), item = "Choice Band", nature = "Jolly"),
            NativePokemonSet("Rotom-Wash", "Rotom-Wash", listOf("hydropump", "voltswitch", "willowisp", "protect"), "Levitate", uuid(2), item = "Leftovers"),
        ),
        p2Team = listOf(
            NativePokemonSet("Gyarados", "Gyarados", listOf("waterfall", "dragondance", "icefang", "taunt"), "Intimidate", uuid(3), item = "Sitrus Berry"),
            NativePokemonSet("Kingambit", "Kingambit", listOf("kowtowcleave", "suckerpunch", "ironhead", "swordsdance"), "Defiant", uuid(4), item = "Black Glasses"),
        ),
        situation = NativeBattleSituation(
            turn = 7,
            pokemon = listOf(
                NativePokemonSituation(uuid(1), hp = 101, hpFraction = 101.0 / 183, status = "brn", boosts = mapOf("atk" to 1),
                    movePp = mapOf("earthquake" to 3), choiceLockedMove = "earthquake", movedSinceSwitchIn = true),
                NativePokemonSituation(uuid(2), hp = 125, hpFraction = 1.0),
                NativePokemonSituation(uuid(3), hp = null, hpFraction = 0.5, item = ""),
                NativePokemonSituation(uuid(4), hp = 0, hpFraction = 0.0),
            ),
            weather = NativeEffectSituation("raindance", remainingTurns = 3),
            p1SideConditions = listOf(NativeEffectSituation("reflect", remainingTurns = 2)),
            p2SideConditions = listOf(NativeEffectSituation("spikes", layers = 2), NativeEffectSituation("stealthrock")),
        ),
    )

    @Test
    fun `the public board replaces what the start produced`() {
        val root = EngineBranchWorker().createBattle(definition)

        assertEquals(7, root.turn)
        val garchomp = root.p1Team.single { it.uuid == uuid(1) }
        assertEquals(101, garchomp.hp)
        assertEquals("brn", garchomp.status)
        // Gyarados' Intimidate at the start is overwritten by the public stages.
        assertEquals(1, garchomp.boosts["atk"])
        val gyarados = root.p2Team.single { it.uuid == uuid(3) }
        assertEquals(0.5, NativeShowdownPublicHp.fraction(gyarados.hp, gyarados.maxHp))
        assertEquals("", gyarados.item)
        assertEquals(0, root.p2Team.single { it.uuid == uuid(4) }.hp)
        assertEquals("raindance", root.field.weather?.id)
        assertEquals(3, root.field.weather?.remainingTurns)
        assertEquals(2, root.field.p1SideConditions.single { it.id == "reflect" }.remainingTurns)
        assertEquals(2, root.field.p2SideConditions.single { it.id == "spikes" }.stacks)
        assertTrue(root.field.p2SideConditions.any { it.id == "stealthrock" })
    }

    @Test
    fun `a confused active is rebuilt confused and snaps out within two moves`() {
        val confused = definition.copy(situation = definition.situation!!.copy(pokemon = definition.situation!!.pokemon.map {
            if (it.uuid == uuid(3)) it.copy(confused = true) else it
        }))
        val worker = EngineBranchWorker()
        var frame = worker.createBattle(confused)
        assertTrue("confusion" in frame.p2Team.single { it.uuid == uuid(3) }.volatiles)
        repeat(2) {
            val ally = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, frame, allowedMechanics = emptySet()).first()
            val opponent = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, frame)
                .first { it.kind == BattleActionKind.USE_MOVE }
            frame = worker.branch(frame.snapshotJson,
                jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder.encode(ally, BattleSide.ALLY, frame),
                jbro.cobblemon.mcc.betterai.simulation.NativeShowdownChoiceEncoder.encode(opponent, BattleSide.OPPONENT, frame))
        }
        assertTrue("confusion" !in frame.p2Team.single { it.uuid == uuid(3) }.volatiles)
    }

    @Test
    fun `a Mega Evolved Pokemon is rebuilt in its Mega forme and its side cannot Mega Evolve again`() {
        val worker = EngineBranchWorker()
        val stone = definition.copy(p1Team = definition.p1Team.map { if (it.uuid == uuid(1)) it.copy(item = "Garchompite") else it })
        fun situation(megaEvolved: Boolean) = stone.copy(situation = stone.situation!!.copy(pokemon = stone.situation!!.pokemon.map {
            if (it.uuid == uuid(1)) it.copy(choiceLockedMove = null, megaEvolved = megaEvolved) else it
        }))
        val base = worker.createBattle(situation(megaEvolved = false))
        val mega = worker.createBattle(situation(megaEvolved = true))
        val before = base.p1Team.single { it.uuid == uuid(1) }
        val after = mega.p1Team.single { it.uuid == uuid(1) }

        assertTrue(after.species.contains("mega", ignoreCase = true), after.species)
        assertEquals("sandforce", after.ability.lowercase().filter(Char::isLetter))
        assertTrue(after.stats.getValue("atk") > before.stats.getValue("atk"))
        assertTrue(NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, base).any { it.mechanic != null })
        assertTrue(NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, mega).none { it.mechanic != null })
    }

    @Test
    fun `a bounded voluntary switch brings in the bench Pokemon the switch preference rates highest`() {
        val worker = EngineBranchWorker()
        val three = definition.copy(
            p1Team = definition.p1Team + NativePokemonSet("Ferrothorn", "Ferrothorn",
                listOf("powerwhip", "gyroball", "leechseed", "protect"), "Iron Barbs", uuid(5), item = "Leftovers"),
            situation = definition.situation!!.copy(pokemon = definition.situation!!.pokemon.map {
                if (it.uuid == uuid(1)) it.copy(choiceLockedMove = null) else it
            } + NativePokemonSituation(uuid(5), hp = null, hpFraction = 1.0)),
        )
        val root = worker.createBattle(three)
        fun switchTargets(preference: ((Int, String) -> Double?)?) =
            NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root, 1, allowedMechanics = emptySet(),
                switchPreference = preference).filter { it.kind == BattleActionKind.SWITCH }.map { it.switchPokemonId.toString() }
        val byType = switchTargets(null).single()
        val other = listOf(uuid(2), uuid(5)).single { it != byType }

        assertEquals(listOf(other), switchTargets { _, bench -> if (bench == other) 0.9 else 0.1 })
        // A Pokemon the preference knows nothing about keeps the type order behind the ones it rates.
        assertEquals(listOf(byType), switchTargets { _, bench -> if (bench == byType) 0.2 else null })
    }

    @Test
    fun `the request follows the installed lock and the fainted bench`() {
        val root = EngineBranchWorker().createBattle(definition)

        val ally = NativeShowdownRequestActionFactory.actions(BattleSide.ALLY, root, allowedMechanics = emptySet())
        assertEquals(listOf("earthquake"), ally.filter { it.kind == BattleActionKind.USE_MOVE }.mapNotNull { it.moveId }.distinct())
        val opponent = NativeShowdownRequestActionFactory.actions(BattleSide.OPPONENT, root)
        assertTrue(opponent.none { it.kind == BattleActionKind.SWITCH }, "A fainted bench cannot be switched in")
    }

    @Test
    fun `a hit reports the stats it read and a corrected stat changes its damage`() {
        val worker = EngineBranchWorker()
        val root = worker.createBattle(definition)
        val roll = worker.branchWithDamageEvidence(root.snapshotJson, "move 1", "move 1")
            .executedDamageRolls.single { it.moveId == "waterfall" }
        assertEquals(NativeDamageStat(uuid(3), "atk"), roll.offense)
        assertEquals(NativeDamageStat(uuid(1), "def"), roll.defense)
        assertTrue(roll.critical != null)

        val attack = root.p2Team.single { it.uuid == uuid(3) }.stats.getValue("atk")
        val range = requireNotNull(worker.statRange(root.snapshotJson, uuid(3), "atk"))
        assertTrue(attack in range && range.first < range.last, "$attack in $range")
        val stronger = worker.restat(root.snapshotJson, listOf(NativeStatChange(uuid(3), "atk", range.last * 2)))
        assertEquals(range.last * 2, stronger.p2Team.single { it.uuid == uuid(3) }.stats.getValue("atk"))
        fun loss(start: String) = worker.branchWithForcedDamage(start, "move 1", "move 1",
            listOf(NativeForcedDamageRoll(roll.damageCallIndex, 100))).executedDamageRolls.single { it.moveId == "waterfall" }.actualHpLoss
        assertTrue(loss(stronger.snapshotJson) > loss(root.snapshotJson))
        // The step is part of the token: a fresh worker replays the same stat.
        assertEquals(range.last * 2, EngineBranchWorker().branch(stronger.snapshotJson, "move 1", "move 1")
            .p2Team.single { it.uuid == uuid(3) }.stats.getValue("atk"))
    }

    @Test
    fun `a reseeded root keeps the position but draws other chance outcomes`() {
        val worker = EngineBranchWorker()
        val root = worker.createBattle(definition)
        val reseeded = worker.reseed(root.snapshotJson, 1)

        assertEquals(root.p1Team.map { it.hp }, reseeded.p1Team.map { it.hp })
        // Gyarados' Waterfall into Garchomp: the roll (and a critical hit) comes from the stream.
        val outcomes = (1..12).map { salt ->
            val start = worker.reseed(root.snapshotJson, salt)
            worker.branch(start.snapshotJson, "move 1", "move 1").p1Team.single { it.uuid == uuid(1) }.hp
        }.toSet()
        assertNotEquals(1, outcomes.size, "Different streams must reach different damage rolls")
        // A token with the reseed step replays to the same frame.
        val replayed = EngineBranchWorker().branch(reseeded.snapshotJson, "move 1", "move 1")
        assertEquals(worker.branch(reseeded.snapshotJson, "move 1", "move 1").p1Team.map { it.hp }, replayed.p1Team.map { it.hp })
    }
}
