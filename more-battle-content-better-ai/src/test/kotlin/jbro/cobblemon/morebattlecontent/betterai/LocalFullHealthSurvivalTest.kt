package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A knockout the rolls call guaranteed is not one when the defender cannot be knocked out.
 *
 * Almost everything decided from the front rests on the knockout assessment - attack or set up, is a
 * patient line affordable, is it time to switch - so a wrong "guaranteed" is not one bad number, it is
 * a wrong premise under the whole turn. Focus Sash appears on 325 of the battle tower's sets, and the
 * projector applied it while the facts the ranking is built from did not.
 */
class LocalFullHealthSurvivalTest {
    @Test
    fun `a revealed focus sash removes the knockout but not the damage`() {
        val plain = knockout(item = null, ability = null)
        assertEquals(BattleKnockoutAssessment.GUARANTEED, plain.first, "The hit does kill an ordinary defender.")

        val sashed = knockout(item = "cobblemon:focus_sash", ability = null)
        assertEquals(BattleKnockoutAssessment.IMPOSSIBLE, sashed.first, "The Sash holder survives on one.")
        assertEquals(0.0, sashed.second, "So there is no knockout chance to report either.")
    }

    @Test
    fun `sturdy is honoured as the species' only ordinary ability`() {
        val sturdy = knockout(item = null, ability = null, inferredOrdinary = listOf("sturdy"))
        assertEquals(
            BattleKnockoutAssessment.IMPOSSIBLE, sturdy.first,
            "Sturdy needs no reveal when it is the only ordinary ability the species has.",
        )
    }

    @Test
    fun `a defender already below full health is knocked out normally`() {
        val worn = knockout(item = "cobblemon:focus_sash", ability = null, hpFraction = 0.6)
        assertEquals(
            BattleKnockoutAssessment.GUARANTEED, worn.first,
            "A Sash only holds from full health; below it the knockout stands.",
        )
    }

    private fun knockout(
        item: String?,
        ability: String?,
        inferredOrdinary: List<String> = emptyList(),
        hpFraction: Double = 1.0,
    ): Pair<BattleKnockoutAssessment?, Double?> {
        val ally = mon(BattleSide.ALLY, null, null, 1.0)
        val opponent = mon(BattleSide.OPPONENT, item, ability, hpFraction)
        val move = BattleActionCandidate(
            actionId = "bigmove", kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:bigmove", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView(
                typeId = "normal", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 250.0,
                accuracy = 100.0, priority = 0, currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            ),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 2,
                pokemon = listOf(ally, opponent),
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
                observedEvents = emptyList(),
                inferences = inferredOrdinary.map {
                    BattleInferenceView(
                        subjectPokemonId = opponent.battlePokemonId,
                        categoryId = "ability", candidateId = it,
                        confidence = BattleInferenceConfidence.POSSIBLE,
                        basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
                        abilityAvailability = BattleAbilityAvailability.REGULAR,
                    )
                },
            ),
            candidates = listOf(move), deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
        val facts = PublicBattleTacticalCalculator.calculate(context).candidates.single().facts
        return facts?.standardKnockoutAssessment to facts?.standardDamageRollKoProbabilityRange?.maximum
    }

    private fun mon(side: BattleSide, item: String?, ability: String?, hpFraction: Double) =
        BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
            speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = hpFraction,
            statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
            knownAbilityId = ability, knownHeldItemId = item, fainted = false,
            knownTypeIds = setOf("normal"),
            combatStats = if (side == BattleSide.ALLY) {
                BattleCombatStatRangesView.exact(160, 200, 100, 100, 100, 100)
            } else {
                BattleCombatStatRangesView(
                    BattleIntegerRange(100, 110), BattleIntegerRange(80, 100), BattleIntegerRange(40, 50),
                    BattleIntegerRange(80, 100), BattleIntegerRange(40, 50), BattleIntegerRange(60, 80),
                    BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
                )
            },
        )
}
