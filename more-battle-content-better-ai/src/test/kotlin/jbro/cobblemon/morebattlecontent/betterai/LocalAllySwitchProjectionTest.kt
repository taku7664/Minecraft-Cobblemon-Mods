package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.state.LocalDirectDamageRecipient
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveActionHistory
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveHistoryProjector
import jbro.cobblemon.morebattlecontent.betterai.state.RecursiveSnapshotActionConstraints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalAllySwitchProjectionTest {
    @Test
    fun `ally switch swaps positions before a slower targeted attack`() {
        val state = state()
        val allyJoint = joint("ally", allySwitch(), attack("partner_attack", 1, BattleSide.OPPONENT, 1))
        val opponentJoint = joint(
            "opponent",
            attack("target_slot_zero", 0, BattleSide.ALLY, 0),
            wait("foe_wait", 1, BattleSide.OPPONENT),
        )
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(allyJoint), Long.MAX_VALUE)

        val outcomes = PublicSingleTurnProjector.project(state, allyJoint, opponentJoint, context)

        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach { outcome ->
            val switcher = outcome.state.pokemon.single { it.battlePokemonId == SWITCHER }
            val partner = outcome.state.pokemon.single { it.battlePokemonId == PARTNER }
            assertEquals(1, switcher.activeSlot)
            assertEquals(0, partner.activeSlot)
            assertEquals(1.0, switcher.hpFraction, 1e-9)
            assertTrue(partner.hpFraction < 1.0)
            assertTrue(outcome.directDamage.amounts.containsKey(LocalDirectDamageRecipient(PARTNER, OTHER_FOE)))
            assertTrue(!outcome.directDamage.amounts.containsKey(LocalDirectDamageRecipient(SWITCHER, OTHER_FOE)))
        }
    }

    @Test
    fun `a consecutive ally switch succeeds on one third of projected branches`() {
        val state = state()
        val allyJoint = joint("ally", allySwitch(), wait("partner_wait", 1, BattleSide.ALLY))
        val opponentJoint = joint(
            "opponent",
            wait("foe_wait", 0, BattleSide.OPPONENT),
            wait("other_foe_wait", 1, BattleSide.OPPONENT),
        )
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(allyJoint), Long.MAX_VALUE)
        val history = RecursiveActionHistory(allySwitchChainByPokemon = mapOf(SWITCHER to 1))

        val outcomes = PublicSingleTurnProjector.project(state, allyJoint, opponentJoint, context, history)
        val success = outcomes.filter {
            it.state.pokemon.single { pokemon -> pokemon.battlePokemonId == SWITCHER }.activeSlot == 1
        }.sumOf { it.probability * it.orderProbability }

        assertEquals(1.0 / 3.0, success, 1e-9)
        assertEquals(1.0, outcomes.sumOf { it.probability * it.orderProbability }, 1e-9)
        val failed = outcomes.single {
            it.state.pokemon.single { pokemon -> pokemon.battlePokemonId == SWITCHER }.activeSlot == 0
        }
        val nextHistory = RecursiveHistoryProjector.project(history, state, failed, allyJoint, opponentJoint)
        assertTrue(SWITCHER !in nextHistory.allySwitchChainByPokemon)
    }

    @Test
    fun `a publicly failed ally switch resets the next attempt`() {
        val used = BattleObservedEventView(
            sequence = 1,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = SWITCHER,
            publicValueId = "allyswitch",
            actorSlot = 0,
        )
        val successfulSeed = RecursiveSnapshotActionConstraints.seed(state(listOf(used)))
        assertEquals(1, successfulSeed.allySwitchChainByPokemon[SWITCHER])

        val failed = BattleObservedEventView(
            sequence = 2,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_OUTCOME,
            targetPokemonIds = listOf(SWITCHER),
            moveOutcome = BattleMoveOutcomeView(BattleMoveOutcomeKind.FAILED, moveId = "allyswitch"),
        )
        val failedSeed = RecursiveSnapshotActionConstraints.seed(state(listOf(used, failed)))
        assertTrue(SWITCHER !in failedSeed.allySwitchChainByPokemon)
    }

    @Test
    fun `switching out and returning resets the ally switch success chain`() {
        val used = BattleObservedEventView(
            sequence = 1,
            turn = 1,
            kind = BattleObservedEventKind.MOVE_USED,
            actorPokemonId = SWITCHER,
            publicValueId = "allyswitch",
            actorSlot = 0,
        )
        val returned = BattleObservedEventView(
            sequence = 2,
            turn = 3,
            kind = BattleObservedEventKind.SWITCHED,
            actorPokemonId = SWITCHER,
            actorSlot = 0,
        )

        val history = RecursiveSnapshotActionConstraints.seed(state(listOf(used, returned), turn = 3))

        assertTrue(SWITCHER !in history.allySwitchChainByPokemon)
    }

    @Test
    fun `a pending self move follows its user to the swapped slot`() {
        val state = state()
        val allyJoint = joint("ally", allySwitch(), selfBoost("partner_boost", 1))
        val opponentJoint = joint(
            "opponent",
            wait("foe_wait", 0, BattleSide.OPPONENT),
            wait("other_foe_wait", 1, BattleSide.OPPONENT),
        )
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(allyJoint), Long.MAX_VALUE)

        val outcome = PublicSingleTurnProjector.project(state, allyJoint, opponentJoint, context).single()
        val switcher = outcome.state.pokemon.single { it.battlePokemonId == SWITCHER }
        val partner = outcome.state.pokemon.single { it.battlePokemonId == PARTNER }

        assertEquals(0, switcher.statStages["attack"] ?: 0)
        assertEquals(1, partner.statStages["attack"])
    }

    private fun state(
        observedEvents: List<BattleObservedEventView> = emptyList(),
        turn: Int = 1,
    ) = BattleStateView(
        battleId = UUID.randomUUID(),
        format = BattleFormat.DOUBLE,
        turn = turn,
        pokemon = listOf(
            pokemon(SWITCHER, BattleSide.ALLY, 0, 80),
            pokemon(PARTNER, BattleSide.ALLY, 1, 70),
            pokemon(ATTACKER, BattleSide.OPPONENT, 0, 100),
            pokemon(OTHER_FOE, BattleSide.OPPONENT, 1, 60),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
        observedEvents = observedEvents,
        inferences = emptyList(),
    )

    private fun allySwitch() = move(
        id = "allyswitch",
        actorSlot = 0,
        category = BattleMoveDamageCategory.STATUS,
        power = 0.0,
        priority = 2,
        targetSide = BattleSide.ALLY,
        targetSlot = 0,
        targetPattern = BattleMoveTargetPattern.SELF,
        scripted = true,
    )

    private fun wait(id: String, actorSlot: Int, side: BattleSide) = move(
        id, actorSlot, BattleMoveDamageCategory.STATUS, 0.0, 0,
        side, actorSlot, BattleMoveTargetPattern.SELF, false,
    )

    private fun attack(id: String, actorSlot: Int, targetSide: BattleSide, targetSlot: Int) = move(
        id, actorSlot, BattleMoveDamageCategory.PHYSICAL, 100.0, 0,
        targetSide, targetSlot, BattleMoveTargetPattern.SELECTED_OPPONENT, false,
    )

    private fun selfBoost(id: String, actorSlot: Int) = BattleActionCandidate(
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
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.STAT_STAGE,
                        target = BattleMoveEffectTarget.USER,
                        probability = 1.0,
                        statStages = mapOf("attack" to 1),
                    ),
                ),
                false,
            ),
        ),
    )

    private fun move(
        id: String,
        actorSlot: Int,
        category: BattleMoveDamageCategory,
        power: Double,
        priority: Int,
        targetSide: BattleSide,
        targetSlot: Int,
        targetPattern: BattleMoveTargetPattern,
        scripted: Boolean,
    ) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(targetSide, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = category,
            power = power,
            accuracy = 100.0,
            priority = priority,
            currentPp = 10,
            targetPattern = targetPattern,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                emptyList(),
                scripted,
            ),
        ),
    )

    private fun joint(id: String, vararg actions: BattleActionCandidate) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = actions.map { it.actionId },
        componentActions = actions.toList(),
    )

    private fun pokemon(id: UUID, side: BattleSide, slot: Int, speed: Int) = BattlePokemonStateView(
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
        knownAbilityId = null,
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
        val SWITCHER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a501")
        val PARTNER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a502")
        val ATTACKER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a503")
        val OTHER_FOE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a504")
    }
}
