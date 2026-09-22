package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalDynamicTurnOrderTest {
    @Test
    fun `rain dance immediately reorders the remaining swift swim action`() {
        val state = BattleStateView(
            battleId = UUID.randomUUID(),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(RAIN_SETTER, BattleSide.ALLY, 0, speed = 200),
                pokemon(SWIMMER, BattleSide.ALLY, 1, speed = 80, ability = "swiftswim"),
                pokemon(FAST_FOE, BattleSide.OPPONENT, 0, speed = 130),
                pokemon(SLOW_FOE, BattleSide.OPPONENT, 1, speed = 110),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val rain = statusMove(
            "raindance",
            actorSlot = 0,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.WEATHER,
                        target = BattleMoveEffectTarget.FIELD,
                        probability = 1.0,
                        valueId = "raindance",
                    ),
                ),
                scriptedBehavior = false,
            ),
        )
        val allyJoint = joint("ally", rain, statusMove("ally_wait", 1))
        val opponentJoint = joint(
            "opponent",
            statusMove("fast_foe_wait", 0),
            statusMove("slow_foe_wait", 1),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = state,
            candidates = listOf(allyJoint),
            deadlineEpochMillis = Long.MAX_VALUE,
        )

        val outcomes = PublicSingleTurnProjector.project(state, allyJoint, opponentJoint, context)

        assertEquals(1.0, outcomes.sumOf { it.probability * it.orderProbability }, 1e-9)
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach { outcome ->
            assertEquals(
                listOf(RAIN_SETTER, SWIMMER, FAST_FOE, SLOW_FOE),
                outcome.actionOrderPokemonIds,
            )
        }
    }

    @Test
    fun `tailwind immediately reorders the remaining ally action`() {
        val state = BattleStateView(
            battleId = UUID.randomUUID(),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(RAIN_SETTER, BattleSide.ALLY, 0, speed = 200),
                pokemon(SWIMMER, BattleSide.ALLY, 1, speed = 80),
                pokemon(FAST_FOE, BattleSide.OPPONENT, 0, speed = 130),
                pokemon(SLOW_FOE, BattleSide.OPPONENT, 1, speed = 110),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val tailwind = statusMove(
            "tailwind",
            actorSlot = 0,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.SIDE_CONDITION,
                        target = BattleMoveEffectTarget.USER_SIDE,
                        probability = 1.0,
                        valueId = "tailwind",
                    ),
                ),
                scriptedBehavior = false,
            ),
        )
        val allyJoint = joint("ally", tailwind, statusMove("ally_wait", 1))
        val opponentJoint = joint(
            "opponent",
            statusMove("fast_foe_wait", 0),
            statusMove("slow_foe_wait", 1),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = state,
            candidates = listOf(allyJoint),
            deadlineEpochMillis = Long.MAX_VALUE,
        )

        val outcomes = PublicSingleTurnProjector.project(state, allyJoint, opponentJoint, context)

        assertEquals(1.0, outcomes.sumOf { it.probability * it.orderProbability }, 1e-9)
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach { outcome ->
            assertEquals(
                listOf(RAIN_SETTER, SWIMMER, FAST_FOE, SLOW_FOE),
                outcome.actionOrderPokemonIds,
            )
        }
    }

    private fun statusMove(
        id: String,
        actorSlot: Int,
        effects: BattleMoveEffectsView? = null,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(BattleSide.ALLY, actorSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = effects,
        ),
    )

    private fun joint(id: String, vararg actions: BattleActionCandidate) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = actions.map { it.actionId },
        componentActions = actions.toList(),
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        slot: Int,
        speed: Int,
        ability: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = slot,
        speciesId = "showdown:probe",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = ability,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(160, 100, 100, 100, 100, speed)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(160, 160),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(speed, speed),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private companion object {
        val RAIN_SETTER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d101")
        val SWIMMER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d102")
        val FAST_FOE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d103")
        val SLOW_FOE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d104")
    }
}
