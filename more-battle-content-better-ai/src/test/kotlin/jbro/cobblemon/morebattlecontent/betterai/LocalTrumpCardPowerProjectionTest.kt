package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMoveDamageInputs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalTrumpCardPowerProjectionTest {
    @Test
    fun `own trump card uses pp remaining after this use`() {
        val cases = listOf(
            1 to 200,
            2 to 80,
            3 to 60,
            4 to 50,
            5 to 40,
            8 to 40,
        )
        for ((currentPp, expected) in cases) {
            val state = state()
            val actor = state.pokemon.single { it.side == BattleSide.ALLY }
            val target = state.pokemon.single { it.side == BattleSide.OPPONENT }
            val resolved = requireNotNull(
                LocalPublicMoveDamageInputs.resolve(trumpCard(currentPp), actor, target, state),
            )

            assertEquals(setOf(expected), resolved.powers, "Trump Card at $currentPp PP before use")
        }
    }

    @Test
    fun `opponent trump card stays unresolved because public pp is only an estimate`() {
        val state = state()
        val actor = state.pokemon.single { it.side == BattleSide.OPPONENT }
        val target = state.pokemon.single { it.side == BattleSide.ALLY }

        assertNull(LocalPublicMoveDamageInputs.resolve(trumpCard(1), actor, target, state))
    }

    private fun trumpCard(currentPp: Int) = BattleActionCandidate(
        actionId = "trumpcard",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:trumpcard",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.SPECIAL,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = currentPp,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = true,
                mechanicFlags = setOf("dynamic_base_power"),
            ),
        ),
    )

    private fun state() = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY_ID, BattleSide.ALLY),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(id: UUID, side: BattleSide) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "cobblemon:test",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = setOf("cobblemon:trumpcard"),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
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
