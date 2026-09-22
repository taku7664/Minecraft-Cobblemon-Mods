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

    @Test
    fun `after you makes its pending target act next`() {
        val state = BattleStateView(
            battleId = UUID.randomUUID(),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(RAIN_SETTER, BattleSide.ALLY, 0, speed = 200),
                pokemon(SWIMMER, BattleSide.ALLY, 1, speed = 50),
                pokemon(FAST_FOE, BattleSide.OPPONENT, 0, speed = 150),
                pokemon(SLOW_FOE, BattleSide.OPPONENT, 1, speed = 100),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val afterYou = afterYouMove()
        val allyJoint = joint("ally", afterYou, statusMove("ally_wait", 1))
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
    fun `after you does not promote its target when the user cannot act`() {
        val state = BattleStateView(
            battleId = UUID.randomUUID(),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(RAIN_SETTER, BattleSide.ALLY, 0, speed = 200, status = "slp"),
                pokemon(SWIMMER, BattleSide.ALLY, 1, speed = 50),
                pokemon(FAST_FOE, BattleSide.OPPONENT, 0, speed = 150),
                pokemon(SLOW_FOE, BattleSide.OPPONENT, 1, speed = 100),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val afterYou = afterYouMove()
        val allyJoint = joint("ally", afterYou, statusMove("ally_wait", 1))
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
        outcomes.forEach { outcome ->
            assertEquals(
                listOf(RAIN_SETTER, FAST_FOE, SLOW_FOE, SWIMMER),
                outcome.actionOrderPokemonIds,
            )
        }
    }

    @Test
    fun `quash makes its pending target act last`() {
        val state = BattleStateView(
            battleId = UUID.randomUUID(),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(RAIN_SETTER, BattleSide.ALLY, 0, speed = 200),
                pokemon(SWIMMER, BattleSide.ALLY, 1, speed = 100),
                pokemon(FAST_FOE, BattleSide.OPPONENT, 0, speed = 150),
                pokemon(SLOW_FOE, BattleSide.OPPONENT, 1, speed = 50),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val quash = queueMove("quash", BattleSide.OPPONENT, 0, BattleMoveTargetPattern.SELECTED_OPPONENT)
        val allyJoint = joint("ally", quash, statusMove("ally_wait", 1))
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
                listOf(RAIN_SETTER, SWIMMER, SLOW_FOE, FAST_FOE),
                outcome.actionOrderPokemonIds,
            )
        }
    }

    @Test
    fun `quash only postpones its target on the hit branch`() {
        val state = BattleStateView(
            battleId = UUID.randomUUID(),
            format = BattleFormat.DOUBLE,
            turn = 1,
            pokemon = listOf(
                pokemon(RAIN_SETTER, BattleSide.ALLY, 0, speed = 200),
                pokemon(SWIMMER, BattleSide.ALLY, 1, speed = 100),
                pokemon(FAST_FOE, BattleSide.OPPONENT, 0, speed = 150),
                pokemon(SLOW_FOE, BattleSide.OPPONENT, 1, speed = 50),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val quash = queueMove(
            "quash",
            BattleSide.OPPONENT,
            0,
            BattleMoveTargetPattern.SELECTED_OPPONENT,
            accuracy = 50.0,
        )
        val allyJoint = joint("ally", quash, statusMove("ally_wait", 1))
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
        val postponed = listOf(RAIN_SETTER, SWIMMER, SLOW_FOE, FAST_FOE)
        val unchanged = listOf(RAIN_SETTER, FAST_FOE, SWIMMER, SLOW_FOE)

        assertEquals(1.0, outcomes.sumOf { it.probability * it.orderProbability }, 1e-9)
        assertEquals(0.5, outcomes.filter { it.actionOrderPokemonIds == postponed }
            .sumOf { it.probability * it.orderProbability }, 1e-9)
        assertEquals(0.5, outcomes.filter { it.actionOrderPokemonIds == unchanged }
            .sumOf { it.probability * it.orderProbability }, 1e-9)
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

    private fun afterYouMove() = queueMove(
        id = "afteryou",
        targetSide = BattleSide.ALLY,
        targetSlot = 1,
        targetPattern = BattleMoveTargetPattern.SELECTED_ALLY,
    )

    private fun queueMove(
        id: String,
        targetSide: BattleSide,
        targetSlot: Int,
        targetPattern: BattleMoveTargetPattern,
        accuracy: Double = 100.0,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(targetSide, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = if (id == "quash") "dark" else "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = accuracy,
            priority = 0,
            currentPp = 10,
            targetPattern = targetPattern,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = true,
            ),
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
        status: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = slot,
        speciesId = "showdown:probe",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = status,
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
