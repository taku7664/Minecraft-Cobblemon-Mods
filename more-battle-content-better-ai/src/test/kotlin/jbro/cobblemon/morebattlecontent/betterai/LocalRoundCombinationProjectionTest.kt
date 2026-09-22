package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.morebattlecontent.betterai.state.LocalDirectDamageRecipient
import jbro.cobblemon.morebattlecontent.betterai.state.PublicTurnProjection
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalRoundCombinationProjectionTest {
    @Test
    fun `the second round follows the first before a faster intervening opponent`() {
        val outcome = project(state(), roundJoint(), opponentJoint())
        val order = outcome.actionOrderPokemonIds

        assertTrue(order.indexOf(FAST_ROUND) < order.indexOf(SLOW_ROUND))
        assertTrue(order.indexOf(SLOW_ROUND) < order.indexOf(ATTACKER))
    }

    @Test
    fun `the second round doubles before damage is projected`() {
        val outcome = project(state(), roundJoint(), opponentJoint())
        val firstDamage = damage(outcome, TARGET_ZERO, FAST_ROUND)
        val secondDamage = damage(outcome, TARGET_ONE, SLOW_ROUND)

        assertTrue(firstDamage > 0.0)
        assertTrue(
            secondDamage > firstDamage * 1.8,
            "first=$firstDamage second=$secondDamage ledger=${outcome.directDamage.amounts}",
        )
    }

    @Test
    fun `technician applies to the first round but not the doubled round`() {
        val outcome = project(
            state(fastAbility = "technician", slowAbility = "technician"),
            roundJoint(),
            opponentJoint(),
        )
        val firstDamage = damage(outcome, TARGET_ZERO, FAST_ROUND)
        val secondDamage = damage(outcome, TARGET_ONE, SLOW_ROUND)

        assertTrue(
            secondDamage > firstDamage,
            "first=$firstDamage second=$secondDamage ledger=${outcome.directDamage.amounts}",
        )
        assertTrue(secondDamage < firstDamage * 1.5, "first=$firstDamage second=$secondDamage")
    }

    @Test
    fun `a round user that cannot move does not start the chain`() {
        val outcome = project(
            state(fastStatus = "slp"),
            roundJoint(),
            opponentJoint(),
        )
        val order = outcome.actionOrderPokemonIds

        assertTrue(order.indexOf(ATTACKER) < order.indexOf(SLOW_ROUND), "order=$order")
        assertTrue(damage(outcome, TARGET_ZERO, FAST_ROUND) == 0.0)
        assertTrue(damage(outcome, TARGET_ONE, SLOW_ROUND) > 0.0)
    }

    private fun project(
        initial: BattleStateView,
        ally: BattleActionCandidate,
        opponent: BattleActionCandidate,
    ): PublicTurnProjection = PublicSingleTurnProjector.project(
        initial,
        ally,
        opponent,
        BattleDecisionContext(UUID.randomUUID(), initial, listOf(ally), Long.MAX_VALUE),
    ).single()

    private fun roundJoint() = joint(
        "round_pair",
        round("fast_round", actorSlot = 0, targetSlot = 0),
        round("slow_round", actorSlot = 1, targetSlot = 1),
    )

    private fun opponentJoint() = joint(
        "opponent_pair",
        attack("intervening_attack", actorSlot = 0, targetSlot = 0),
        wait("opponent_wait", actorSlot = 1, side = BattleSide.OPPONENT),
    )

    private fun round(id: String, actorSlot: Int, targetSlot: Int) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = "round",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.SPECIAL,
            power = 60.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 15,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                emptyList(),
                scriptedBehavior = true,
                mechanicFlags = setOf("dynamic_base_power", "protect", "sound", "bypasssub"),
            ),
        ),
    )

    private fun attack(id: String, actorSlot: Int, targetSlot: Int) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(BattleSide.ALLY, targetSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = 40.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                emptyList(),
                scriptedBehavior = false,
                mechanicFlags = setOf("protect"),
            ),
        ),
    )

    private fun wait(id: String, actorSlot: Int, side: BattleSide) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = 0,
        moveId = id,
        targets = listOf(BattleTargetSlot(side, actorSlot)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = BattleMoveDamageCategory.STATUS,
            power = 0.0,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELF,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), false),
        ),
    )

    private fun joint(id: String, vararg actions: BattleActionCandidate) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = actions.map { it.actionId },
        componentActions = actions.toList(),
    )

    private fun state(
        fastAbility: String? = null,
        slowAbility: String? = null,
        fastStatus: String? = null,
    ) = BattleStateView(
        battleId = BATTLE,
        format = BattleFormat.DOUBLE,
        turn = 1,
        pokemon = listOf(
            pokemon(FAST_ROUND, BattleSide.ALLY, 0, 120, fastAbility, fastStatus),
            pokemon(SLOW_ROUND, BattleSide.ALLY, 1, 40, slowAbility),
            pokemon(ATTACKER, BattleSide.OPPONENT, 0, 90),
            pokemon(TARGET_ONE, BattleSide.OPPONENT, 1, 20),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
        observedEvents = emptyList(),
        inferences = emptyList(),
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
        knownMoveIds = setOf("round"),
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

    private fun damage(outcome: PublicTurnProjection, target: UUID, source: UUID): Double =
        outcome.directDamage.amounts[LocalDirectDamageRecipient(source, target)] ?: 0.0

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c500")
        val FAST_ROUND: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c501")
        val SLOW_ROUND: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c502")
        val ATTACKER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c503")
        val TARGET_ONE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c504")
        val TARGET_ZERO: UUID = ATTACKER
    }
}
