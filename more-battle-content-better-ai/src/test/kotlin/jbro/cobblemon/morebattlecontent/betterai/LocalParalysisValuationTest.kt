package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Nuzzle must not beat Thunderbolt.
 *
 * This is a real choice a trainer made in a live battle: thirty damage with a guaranteed paralysis
 * over a hundred and forty-seven, both resisted the same way. It was recorded as a suspicion that
 * paralysis is priced too highly, and a suspicion is not a finding - so this reproduces the position
 * and reads the score decomposition rather than arguing about the constant.
 *
 * A trainer may reasonably prefer the status when the damage is close. The claim here is only that a
 * fivefold difference in damage is not close.
 */
class LocalParalysisValuationTest {
    @Test
    fun `a fivefold damage advantage outranks a guaranteed paralysis`() {
        val breakdown = LocalDecisionInstrumentation.inspect(context())
        println(breakdown.format("nuzzle-vs-thunderbolt"))
        assertEquals(
            "thunderbolt", breakdown.chosenByRanking?.actionId,
            "Thirty damage and a paralysis is not worth a hundred and forty-seven: " +
                breakdown.candidates.joinToString(" | ") { "${it.actionId}=${it.comparisonValue}" },
        )
    }

    private fun context(): BattleDecisionContext {
        val ally = mon(BattleSide.ALLY, setOf("electric"), maxHp = 250, specialAttack = 160, attack = 90)
        val opponent = mon(BattleSide.OPPONENT, setOf("grass"), maxHp = 300, specialAttack = 100, attack = 100)
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 3,
                pokemon = listOf(ally, opponent), field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 3 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = listOf(
                attack("nuzzle", power = 20.0, category = BattleMoveDamageCategory.PHYSICAL, paralysisChance = 1.0),
                attack("thunderbolt", power = 90.0, category = BattleMoveDamageCategory.SPECIAL, paralysisChance = 0.1),
            ),
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
    }

    private fun attack(
        id: String,
        power: Double,
        category: BattleMoveDamageCategory,
        paralysisChance: Double,
    ) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "cobblemon:$id", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "electric", damageCategory = category, power = power,
            accuracy = 100.0, priority = 0, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                scriptedBehavior = false,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.STATUS,
                        target = BattleMoveEffectTarget.SELECTED_TARGET,
                        valueId = "par",
                        probability = paralysisChance,
                    ),
                ),
            ),
        ),
    )

    private fun mon(
        side: BattleSide,
        types: Set<String>,
        maxHp: Int,
        specialAttack: Int,
        attack: Int,
    ) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
        speciesId = "cobblemon:probe_${side.name.lowercase()}", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(maxHp, attack, 100, specialAttack, 100, 110)
        } else {
            publicExactStats(maxHp, attack, 100, specialAttack, 100, 110)
        },
    )
}
