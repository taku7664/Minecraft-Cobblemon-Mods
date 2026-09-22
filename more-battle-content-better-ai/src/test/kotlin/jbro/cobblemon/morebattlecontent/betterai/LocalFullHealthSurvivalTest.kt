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
    fun `a possible hidden ability prevents inferring guaranteed sturdy survival`() {
        val unresolved = knockout(item = null, ability = null, inferredOrdinary = listOf("sturdy"),
            inferredHidden = listOf("weakarmor"))
        assertEquals(BattleKnockoutAssessment.GUARANTEED, unresolved.first,
            "Keep the base damage projection instead of asserting unrevealed Sturdy; dynamic modifiers remain unknown")
        val revealed = knockout(item = null, ability = "sturdy", inferredOrdinary = listOf("sturdy"),
            inferredHidden = listOf("weakarmor"))
        assertEquals(BattleKnockoutAssessment.IMPOSSIBLE, revealed.first)
    }

    @Test
    fun `small projected damage still breaks full health survival`() {
        for (hp in listOf(0.999, 0.9995, 0.999999)) {
            for ((item, ability) in listOf("focussash" to null, null to "sturdy")) {
                val result = knockout(item = item, ability = ability, hpFraction = hp)
                assertEquals(BattleKnockoutAssessment.GUARANTEED, result.first,
                    "hp=$hp item=$item ability=$ability")
                assertEquals(1.0, result.second)
            }
        }
    }

    @Test
    fun `magic room restores knockout chance against sash but not sturdy`() {
        for (room in listOf("cobblemon:magic_room", "trickroom")) {
            for (ability in listOf(null, "sturdy")) {
                val result = knockout(item = "cobblemon:focus_sash", ability = ability, room = room)
                val survives = room == "trickroom" || ability == "sturdy"
                assertEquals(if (survives) BattleKnockoutAssessment.IMPOSSIBLE else BattleKnockoutAssessment.GUARANTEED,
                    result.first, "room=$room ability=$ability")
                assertEquals(if (survives) 0.0 else 1.0, result.second)
            }
        }
    }

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

    @Test
    fun `mold breaker bypasses sturdy but still permits a focus sash`() {
        assertEquals(
            BattleKnockoutAssessment.GUARANTEED,
            knockout(item = null, ability = "sturdy", actorAbility = "moldbreaker").first,
        )
        assertEquals(
            BattleKnockoutAssessment.IMPOSSIBLE,
            knockout(item = "focussash", ability = "sturdy", actorAbility = "moldbreaker").first,
        )
    }

    @Test
    fun `neutralizing gas suppresses sturdy unless ability shield protects it`() {
        assertEquals(
            BattleKnockoutAssessment.GUARANTEED,
            knockout(item = null, ability = "sturdy", neutralizingGas = true).first,
        )
        assertEquals(
            BattleKnockoutAssessment.IMPOSSIBLE,
            knockout(item = "abilityshield", ability = "sturdy", neutralizingGas = true).first,
        )
    }

    private fun knockout(
        item: String?,
        ability: String?,
        inferredOrdinary: List<String> = emptyList(),
        inferredHidden: List<String> = emptyList(),
        hpFraction: Double = 1.0,
        room: String? = null,
        actorAbility: String? = null,
        neutralizingGas: Boolean = false,
    ): Pair<BattleKnockoutAssessment?, Double?> {
        val ally = mon(BattleSide.ALLY, null, actorAbility, 1.0)
        val opponent = mon(BattleSide.OPPONENT, item, ability, hpFraction)
        val gasPartner = mon(BattleSide.ALLY, null, "neutralizinggas", 1.0, activeSlot = 1)
        val opponentPartner = mon(BattleSide.OPPONENT, null, null, 1.0, activeSlot = 1)
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
                battleId = UUID.randomUUID(), format = if (neutralizingGas) BattleFormat.DOUBLE else BattleFormat.SINGLE,
                turn = 2,
                pokemon = if (neutralizingGas) listOf(ally, gasPartner, opponent, opponentPartner) else listOf(ally, opponent),
                field = BattleFieldStateView(null, null,
                    room?.let { listOf(BattleTimedEffectView(it, null)) }.orEmpty(),
                    emptyList(), BattleSide.entries.associateWith { emptyList() }),
                remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
                observedEvents = emptyList(),
                inferences = (inferredOrdinary.map { it to BattleAbilityAvailability.REGULAR } +
                    inferredHidden.map { it to BattleAbilityAvailability.HIDDEN }).map { (id, availability) ->
                    BattleInferenceView(
                        subjectPokemonId = opponent.battlePokemonId,
                        categoryId = "ability", candidateId = id,
                        confidence = BattleInferenceConfidence.POSSIBLE,
                        basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
                        abilityAvailability = availability,
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

    private fun mon(
        side: BattleSide,
        item: String?,
        ability: String?,
        hpFraction: Double,
        activeSlot: Int = 0,
    ) =
        BattlePokemonStateView(
            battlePokemonId = UUID.randomUUID(), side = side, activeSlot = activeSlot,
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
