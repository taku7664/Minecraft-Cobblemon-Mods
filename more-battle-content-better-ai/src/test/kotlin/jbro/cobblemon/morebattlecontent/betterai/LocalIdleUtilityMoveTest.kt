package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalNonDamagingMoveEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A utility move that cannot change anything is worth nothing.
 *
 * Every status move the evaluator does not recognise falls through to one flat value, which is a
 * sound default and a poor one for moves whose entire worth depends on the board. Defog on an empty
 * field, Haze against nobody boosted, Toxic into a Steel type: all three read as ordinary plays and
 * were chosen over real attacks whenever the attacks looked mediocre.
 */
class LocalIdleUtilityMoveTest {
    @Test
    fun `hazard removal is worthless with nothing to remove and worth something once there is`() {
        assertEquals(0.0, pressureOf(statusMove("defog"), hazard = null), "Nothing to clear.")
        assertTrue(pressureOf(statusMove("defog"), hazard = "stealthrock") > 0.0, "Now it has a job.")
    }

    @Test
    fun `a stat stage reset is worthless while nobody is boosted`() {
        assertEquals(0.0, pressureOf(statusMove("haze"), hazard = null), "No stages to clear.")
        assertTrue(
            pressureOf(statusMove("haze"), hazard = null, opponentAttackStage = 2) > 0.0,
            "A boosted opponent is exactly what it answers.",
        )
    }

    @Test
    fun `a major status is not projected onto a type that cannot take it`() {
        assertEquals(
            null,
            statusProbability(defenderTypes = setOf("steel")),
            "Toxic cannot poison a Steel type, so there is no status chance to report.",
        )
        assertTrue(
            (statusProbability(defenderTypes = setOf("water")) ?: 0.0) > 0.0,
            "Against a type that can be poisoned it stays a real play.",
        )
    }

    private fun statusProbability(defenderTypes: Set<String>): Double? {
        val toxic = BattleActionCandidate(
            actionId = "toxic", kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:toxic", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView(
                typeId = "poison", damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0,
                accuracy = 90.0, priority = 0, currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
                effects = BattleMoveEffectsView(
                    BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    listOf(
                        BattleMoveEffectView(
                            BattleMoveEffectKind.STATUS, BattleMoveEffectTarget.SELECTED_TARGET,
                            probability = 1.0, valueId = "tox",
                        ),
                    ),
                    false,
                ),
            ),
        )
        return PublicBattleTacticalCalculator
            .calculate(context(listOf(toxic), defenderTypes = defenderTypes))
            .candidates.single().facts?.statusEffectProbability
    }

    private fun pressureOf(
        candidate: BattleActionCandidate,
        hazard: String?,
        opponentAttackStage: Int = 0,
    ): Double {
        val context = context(listOf(candidate), hazard = hazard, opponentAttackStage = opponentAttackStage)
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        return LocalNonDamagingMoveEvaluator.pressure(calculated.candidates.single(), calculated, accuracy = 1.0)
    }

    private fun statusMove(id: String) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "cobblemon:$id", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal", damageCategory = BattleMoveDamageCategory.STATUS, power = 0.0,
            accuracy = 100.0, priority = 0, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            effects = BattleMoveEffectsView(
                BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                listOf(
                    BattleMoveEffectView(
                        BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.SELECTED_TARGET,
                        probability = 1.0, statStages = mapOf("evasion" to -1),
                    ),
                ),
                false,
            ),
        ),
    )

    private fun context(
        candidates: List<BattleActionCandidate>,
        defenderTypes: Set<String> = setOf("water"),
        hazard: String? = null,
        opponentAttackStage: Int = 0,
    ): BattleDecisionContext {
        val ally = mon(BattleSide.ALLY, setOf("normal"), 0)
        val opponent = mon(BattleSide.OPPONENT, defenderTypes, opponentAttackStage)
        val conditions = BattleSide.entries.associateWith { side ->
            if (side == BattleSide.ALLY && hazard != null) {
                listOf(BattleTimedEffectView(effectId = "cobblemon:$hazard", remainingTurns = null))
            } else {
                emptyList()
            }
        }
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 3,
                pokemon = listOf(ally, opponent),
                field = BattleFieldStateView(
                    weather = null, terrain = null, roomEffects = emptyList(),
                    globalEffects = emptyList(), sideConditions = conditions,
                ),
                remainingPokemonBySide = BattleSide.entries.associateWith { 3 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = candidates, deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
    }

    private fun mon(side: BattleSide, types: Set<String>, attackStage: Int) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
        speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = 1.0,
        statusId = null,
        statStages = if (attackStage == 0) emptyMap() else mapOf("cobblemon:attack" to attackStage),
        knownMoveIds = emptySet(), knownAbilityId = null, knownHeldItemId = null,
        fainted = false, knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(160, 120, 100, 110, 100, 100)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(150, 190), BattleIntegerRange(90, 150), BattleIntegerRange(80, 135),
                BattleIntegerRange(90, 150), BattleIntegerRange(80, 135), BattleIntegerRange(60, 110),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )
}
