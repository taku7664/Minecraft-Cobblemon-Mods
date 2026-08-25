package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionOutcome
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalRootDecisionPolicy
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalSwitchVetoReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalRootDecisionPolicyTest {
    @Test
    fun `double composite switch does not discard setup when the same partner line can stay`() {
        val activeId = UUID.fromString("00000000-0000-0000-0000-000000000041")
        val partnerId = UUID.fromString("00000000-0000-0000-0000-000000000042")
        val benchId = UUID.fromString("00000000-0000-0000-0000-000000000043")
        val opponentId = UUID.fromString("00000000-0000-0000-0000-000000000044")
        val switch = BattleActionCandidate("switch", BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = benchId)
        val stay = move("stay", 0)
        val partner = move("partner", 1)
        val switchTurn = composite("switch_turn", switch, partner)
        val stayTurn = composite("stay_turn", stay, partner)
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(),
                format = BattleFormat.DOUBLE,
                turn = 3,
                pokemon = listOf(
                    pokemon(activeId, BattleSide.ALLY, 0, statStages = mapOf("attack" to 2)),
                    pokemon(partnerId, BattleSide.ALLY, 1),
                    pokemon(benchId, BattleSide.ALLY, null),
                    pokemon(opponentId, BattleSide.OPPONENT, 0),
                ),
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 1),
                observedEvents = emptyList(),
                inferences = emptyList(),
            ),
            candidates = listOf(switchTurn, stayTurn),
            deadlineEpochMillis = Long.MAX_VALUE,
        )
        val switchComponentOutcome = outcome(switch, survivalImprovement = 0.5)
        val stayComponentOutcome = outcome(stay, damageActions = 1)
        val partnerOutcome = outcome(partner, damageActions = 1)
        val ranks = listOf(
            rank(switchTurn, 100.0, listOf(switchComponentOutcome, partnerOutcome)),
            rank(stayTurn, 90.0, listOf(stayComponentOutcome, partnerOutcome)),
        )

        val result = LocalRootDecisionPolicy.refine(ranks, context)

        assertEquals(listOf("stay_turn"), result.ranked.map { it.outcome.candidate.actionId })
        assertTrue(LocalSwitchVetoReason.PRESERVE_SETUP in result.switchVetoes)
    }

    private fun composite(id: String, vararg actions: BattleActionCandidate) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = actions.map(BattleActionCandidate::actionId),
        componentActions = actions.toList(),
    )

    private fun move(id: String, slot: Int) = BattleActionCandidate(
        actionId = id,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = slot,
        moveSlot = 0,
        moveId = "cobblemon:$id",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.PHYSICAL, 80.0, 100.0, 0, 10),
    )

    private fun outcome(
        candidate: BattleActionCandidate,
        damageActions: Int = 0,
        survivalImprovement: Double? = null,
        components: List<LocalBattleActionOutcome> = emptyList(),
    ) = LocalBattleActionOutcome(
        candidate = candidate,
        tacticalUtility = 0.0,
        expectedDamageFraction = 0.0,
        secureStandardKnockouts = 0,
        executableDamageActions = damageActions,
        publiclyInert = false,
        entryFaints = false,
        switchPostEntryHp = null,
        currentDefensiveExposure = null,
        resultingDefensiveExposure = null,
        survivalPositionImprovement = survivalImprovement,
        componentOutcomes = components,
    )

    private fun rank(
        candidate: BattleActionCandidate,
        score: Double,
        components: List<LocalBattleActionOutcome>,
    ) = LocalBattleActionRank(
        outcome = outcome(candidate, components = components),
        decisionTier = 3,
        comparisonValue = score,
        executionProbability = 1.0,
        worstResponseHpRetention = 1.0,
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        slot: Int?,
        statStages: Map<String, Int> = emptyMap(),
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = slot,
        speciesId = "showdown:test",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = statStages,
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(100, 100, 100, 100, 100, 100)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )
}
