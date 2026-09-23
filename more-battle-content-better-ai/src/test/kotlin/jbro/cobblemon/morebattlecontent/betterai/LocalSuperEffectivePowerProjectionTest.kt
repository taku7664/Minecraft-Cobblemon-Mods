package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMoveDamageInputs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalSuperEffectivePowerProjectionTest {
    @Test
    fun `collision course receives its exact public super effective boost`() {
        assertPower("collisioncourse", "fighting", setOf("normal"), 133)
        assertPower("collisioncourse", "fighting", setOf("normal", "flying"), 100)
        assertPower("collisioncourse", "fighting", setOf("ghost"), 100)
    }

    @Test
    fun `electro drift receives its exact public super effective boost`() {
        assertPower("electrodrift", "electric", setOf("water"), 133)
        assertPower("electrodrift", "electric", setOf("water", "grass"), 100)
        assertPower("electrodrift", "electric", setOf("water", "ground"), 100)
    }

    private fun assertPower(
        moveId: String,
        moveType: String,
        targetTypes: Set<String>,
        expected: Int,
    ) {
        val state = state(targetTypes)
        val actor = state.pokemon.single { it.side == BattleSide.ALLY }
        val target = state.pokemon.single { it.side == BattleSide.OPPONENT }
        val resolved = requireNotNull(
            LocalPublicMoveDamageInputs.resolve(move(moveId, moveType), actor, target, state),
        )

        assertEquals(setOf(expected), resolved.powers)
    }

    private fun move(id: String, type: String) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = type,
            damageCategory = if (id == "collisioncourse") {
                BattleMoveDamageCategory.PHYSICAL
            } else {
                BattleMoveDamageCategory.SPECIAL
            },
            power = 100.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 5,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = true,
                mechanicFlags = setOf("dynamic_base_power"),
            ),
        ),
    )

    private fun state(targetTypes: Set<String>) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY_ID, BattleSide.ALLY, setOf("normal")),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT, targetTypes),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(id: UUID, side: BattleSide, types: Set<String>) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "cobblemon:test",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = types,
        combatStats = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(200, 200),
            attack = BattleIntegerRange(100, 100),
            defence = BattleIntegerRange(100, 100),
            specialAttack = BattleIntegerRange(100, 100),
            specialDefence = BattleIntegerRange(100, 100),
            speed = BattleIntegerRange(100, 100),
            knowledge = if (side == BattleSide.ALLY) {
                BattleCombatStatKnowledge.EXACT_OWN
            } else {
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE
            },
        ),
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cf00")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cf01")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000cf02")
    }
}
