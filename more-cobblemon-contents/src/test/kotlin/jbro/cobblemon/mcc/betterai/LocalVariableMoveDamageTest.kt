package jbro.cobblemon.mcc.betterai

import java.util.UUID
import java.util.zip.ZipInputStream
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Damaging moves the public model used to score as zero damage: a callback flag made them "unresolved
 * dynamic damage" even when the damage is public (Knock Off, Ivy Cudgel) or not dynamic at all.
 */
class LocalVariableMoveDamageTest {
    private val flags: Map<String, Set<String>> by lazy {
        ZipInputStream(requireNotNull(javaClass.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("Missing data/moves.js")
                if (entry.name == "data/moves.js") {
                    return@lazy BattleDeclarativeMoveEffects.parse(zip.readBytes().toString(Charsets.UTF_8))
                        .mapValues { (_, effects) -> effects.mechanicFlags.filter { it.startsWith("dynamic_") }.toSet() }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            emptyMap()
        }
    }

    @Test
    fun `reading a field in a callback does not make the damage dynamic`() {
        for (move in listOf("suckerpunch", "thunderclap", "upperhand", "freezedry", "flyingpress")) {
            assertEquals(emptySet<String>(), flags.getValue(move), move)
        }
        // Callbacks that really write the field stay dynamic.
        assertTrue("dynamic_base_power" in flags.getValue("knockoff"))
        assertTrue("dynamic_move_type" in flags.getValue("ivycudgel"))
        assertTrue("dynamic_damage_category" in flags.getValue("photongeyser"))
        assertTrue(flags.getValue("terablast").isNotEmpty())
    }

    @Test
    fun `knock off is boosted only by a public item it can remove`() {
        assertEquals(damage(state(), fixed("dark", 65)), damage(state(), knockOff()))
        assertEquals(damage(state(opponentItem = ""), fixed("dark", 65)), damage(state(opponentItem = ""), knockOff()))
        assertEquals(damage(state(opponentItem = "leftovers"), fixed("dark", 97)), damage(state(opponentItem = "leftovers"), knockOff()))
        assertEquals(damage(state(opponentItem = "hearthflamemask"), fixed("dark", 65)),
            damage(state(opponentItem = "hearthflamemask"), knockOff()))
        assertEquals(damage(state(opponentItem = "eviolite"), fixed("dark", 97)), damage(state(opponentItem = "eviolite"), knockOff()))
    }

    @Test
    fun `acrobatics doubles power after a publicly confirmed item removal`() {
        val emptyHanded = state().derive(pokemon = state().pokemon.map {
            if (it.side == BattleSide.ALLY) it.copyState(knownHeldItemId = "") else it
        })
        assertEquals(damage(emptyHanded, fixed("flying", 110)),
            damage(emptyHanded, move("acrobatics", "flying", 55.0, emptySet())))
    }

    @Test
    fun `ivy cudgel takes the type of ogerpon's mask`() {
        val hearthflame = state(allySpecies = "cobblemon:ogerpon", allyForm = "hearthflame", opponentTypes = setOf("grass"))
        assertEquals(damage(hearthflame, fixed("fire", 100)), damage(hearthflame, ivyCudgel()))
        val teal = state(allySpecies = "cobblemon:ogerpon", opponentTypes = setOf("grass"))
        assertEquals(damage(teal, fixed("grass", 100)), damage(teal, ivyCudgel()))
        assertFalse(damage(hearthflame, ivyCudgel()).maximum == 0.0)
    }

    @Test
    fun `flying press multiplies the fighting and flying charts`() {
        assertEquals(2.0, StandardTypeEffectiveness.multiplierForMove("flyingpress", "fighting", setOf("grass")))
        assertEquals(4.0, StandardTypeEffectiveness.multiplierForMove("flyingpress", "fighting", setOf("normal", "grass")))
        assertEquals(0.0, StandardTypeEffectiveness.multiplierForMove("flyingpress", "fighting", setOf("ghost")))
    }

    private fun damage(state: BattleStateView, action: BattleActionCandidate): BattleDamageFractionRange {
        val calculated = PublicBattleTacticalCalculator.calculate(
            BattleDecisionContext(REQUEST_ID, state, listOf(action), Long.MAX_VALUE),
        ).candidates.single()
        return requireNotNull(calculated.facts?.standardDamageFractionRange) { "missing projected damage for ${action.moveId}" }
    }

    private fun knockOff() = move("knockoff", "dark", 65.0, setOf("dynamic_base_power"))

    private fun ivyCudgel() = move("ivycudgel", "grass", 100.0, setOf("dynamic_move_type"))

    private fun fixed(type: String, power: Int) = move("fixed_$type$power", type, power.toDouble(), emptySet())

    private fun move(id: String, type: String, power: Double, mechanicFlags: Set<String>) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = type,
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = power,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = mechanicFlags.isNotEmpty(),
                mechanicFlags = mechanicFlags,
            ),
        ),
    )

    private fun state(
        allySpecies: String = "cobblemon:test",
        allyForm: String? = null,
        opponentItem: String? = null,
        opponentTypes: Set<String> = setOf("normal"),
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY_ID, BattleSide.ALLY, allySpecies, allyForm, null, setOf("normal")),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT, "cobblemon:test", null, opponentItem, opponentTypes),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(id: UUID, side: BattleSide, species: String, form: String?, item: String?, types: Set<String>) =
        BattlePokemonStateView(
            battlePokemonId = id,
            side = side,
            activeSlot = 0,
            speciesId = species,
            formId = form,
            level = 50,
            hpFraction = 1.0,
            statusId = null,
            statStages = emptyMap(),
            knownMoveIds = emptySet(),
            knownAbilityId = null,
            knownHeldItemId = item,
            fainted = false,
            knownTypeIds = types,
            combatStats = BattleCombatStatRangesView(
                maxHp = BattleIntegerRange(200, 200),
                attack = BattleIntegerRange(100, 100),
                defence = BattleIntegerRange(100, 100),
                specialAttack = BattleIntegerRange(100, 100),
                specialDefence = BattleIntegerRange(100, 100),
                speed = BattleIntegerRange(100, 100),
                knowledge = if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            ),
        )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cc00")
        val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cc01")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cc02")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cc03")
    }
}
