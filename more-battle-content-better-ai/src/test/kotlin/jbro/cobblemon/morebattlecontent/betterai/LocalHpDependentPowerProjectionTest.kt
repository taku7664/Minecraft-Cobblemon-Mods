package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMoveDamageInputs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalHpDependentPowerProjectionTest {
    @Test
    fun `eruption family floors exact public hp-scaled power and keeps one minimum power`() {
        for (id in listOf("eruption", "waterspout", "dragonenergy")) {
            assertPower(id, printedPower = 150, hp = 200, maxHp = 200, expected = 150)
            assertPower(id, printedPower = 150, hp = 199, maxHp = 200, expected = 149)
            assertPower(id, printedPower = 150, hp = 100, maxHp = 200, expected = 75)
            assertPower(id, printedPower = 150, hp = 1, maxHp = 200, expected = 1)
        }
    }

    @Test
    fun `flail and reversal preserve every official exact hp tier boundary`() {
        val cases = listOf(
            19 to 200,
            20 to 150,
            50 to 100,
            100 to 80,
            170 to 40,
            330 to 20,
        )
        for (id in listOf("flail", "reversal")) {
            for ((hp, expected) in cases) {
                assertPower(id, printedPower = 0, hp = hp, maxHp = 480, expected = expected)
            }
        }
    }

    @Test
    fun `modeled fractional hp without an integer public hypothesis stays unresolved`() {
        val state = state(
            hpFraction = 0.123456789,
            maxHp = BattleIntegerRange(200, 220),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        )
        val actor = state.pokemon.single { it.side == BattleSide.ALLY }
        val target = state.pokemon.single { it.side == BattleSide.OPPONENT }

        assertNull(LocalPublicMoveDamageInputs.resolve(move("eruption", 150), actor, target, state))
        assertNull(LocalPublicMoveDamageInputs.resolve(move("flail", 0), actor, target, state))
    }

    @Test
    fun `public max hp range resolves when every exact hypothesis gives the same power`() {
        val state = state(
            hpFraction = 0.5,
            maxHp = BattleIntegerRange(200, 220),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        )
        val actor = state.pokemon.single { it.side == BattleSide.ALLY }
        val target = state.pokemon.single { it.side == BattleSide.OPPONENT }

        val eruption = requireNotNull(LocalPublicMoveDamageInputs.resolve(move("eruption", 150), actor, target, state))
        val flail = requireNotNull(LocalPublicMoveDamageInputs.resolve(move("flail", 0), actor, target, state))

        assertEquals(setOf(75), eruption.powers)
        assertEquals(setOf(40), flail.powers)
    }

    private fun assertPower(id: String, printedPower: Int, hp: Int, maxHp: Int, expected: Int) {
        val state = state(
            hpFraction = hp.toDouble() / maxHp,
            maxHp = BattleIntegerRange(maxHp, maxHp),
            knowledge = BattleCombatStatKnowledge.EXACT_OWN,
        )
        val actor = state.pokemon.single { it.side == BattleSide.ALLY }
        val target = state.pokemon.single { it.side == BattleSide.OPPONENT }
        val resolved = requireNotNull(
            LocalPublicMoveDamageInputs.resolve(move(id, printedPower), actor, target, state),
        )

        assertEquals(setOf(expected), resolved.powers, "$id at $hp/$maxHp HP")
    }

    private fun move(id: String, power: Int) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.SPECIAL,
            power = power.toDouble(),
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = true,
                mechanicFlags = setOf("dynamic_base_power"),
            ),
        ),
    )

    private fun state(
        hpFraction: Double,
        maxHp: BattleIntegerRange,
        knowledge: BattleCombatStatKnowledge,
    ) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY_ID, BattleSide.ALLY, hpFraction, maxHp, knowledge),
            pokemon(
                OPPONENT_ID,
                BattleSide.OPPONENT,
                hpFraction = 1.0,
                maxHp = BattleIntegerRange(200, 240),
                knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            ),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        hpFraction: Double,
        maxHp: BattleIntegerRange,
        knowledge: BattleCombatStatKnowledge,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "cobblemon:test",
        formId = null,
        level = 50,
        hpFraction = hpFraction,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = BattleCombatStatRangesView(
            maxHp = maxHp,
            attack = BattleIntegerRange(100, if (knowledge == BattleCombatStatKnowledge.EXACT_OWN) 100 else 120),
            defence = BattleIntegerRange(100, if (knowledge == BattleCombatStatKnowledge.EXACT_OWN) 100 else 120),
            specialAttack = BattleIntegerRange(100, if (knowledge == BattleCombatStatKnowledge.EXACT_OWN) 100 else 120),
            specialDefence = BattleIntegerRange(100, if (knowledge == BattleCombatStatKnowledge.EXACT_OWN) 100 else 120),
            speed = BattleIntegerRange(100, if (knowledge == BattleCombatStatKnowledge.EXACT_OWN) 100 else 120),
            knowledge = knowledge,
        ),
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cd00")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cd01")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cd02")
    }
}
