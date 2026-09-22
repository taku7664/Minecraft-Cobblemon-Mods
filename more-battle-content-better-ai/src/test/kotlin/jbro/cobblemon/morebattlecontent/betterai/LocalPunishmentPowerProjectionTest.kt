package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMoveDamageInputs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalPunishmentPowerProjectionTest {
    @Test
    fun `punishment uses only the targets positive public stat stages and caps at two hundred`() {
        assertPower(emptyMap(), 60)
        assertPower(mapOf("attack" to 2, "speed" to 1, "defence" to -3), 120)
        assertPower(
            mapOf(
                "attack" to 6,
                "defence" to 6,
                "special_attack" to 6,
                "special_defence" to 6,
                "speed" to 6,
                "accuracy" to 6,
            ),
            200,
        )
    }

    private fun assertPower(targetStages: Map<String, Int>, expected: Int) {
        val state = state(targetStages)
        val actor = state.pokemon.single { it.side == BattleSide.ALLY }
        val target = state.pokemon.single { it.side == BattleSide.OPPONENT }
        val resolved = requireNotNull(LocalPublicMoveDamageInputs.resolve(punishment(), actor, target, state))

        assertEquals(setOf(expected), resolved.powers)
    }

    private fun punishment() = BattleActionCandidate(
        actionId = "punishment",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:punishment",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "dark",
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = 0.0,
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

    private fun state(targetStages: Map<String, Int>) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            pokemon(ALLY_ID, BattleSide.ALLY, emptyMap()),
            pokemon(OPPONENT_ID, BattleSide.OPPONENT, targetStages),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        stages: Map<String, Int>,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "cobblemon:test",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = stages,
        knownMoveIds = emptySet(),
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
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ce00")
        val ALLY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ce01")
        val OPPONENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ce02")
    }
}
