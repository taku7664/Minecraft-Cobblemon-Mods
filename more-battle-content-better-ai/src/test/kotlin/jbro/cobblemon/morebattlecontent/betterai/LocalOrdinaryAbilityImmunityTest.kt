package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * An immunity every ordinary member of a species has is public knowledge.
 *
 * Orthworm is the case that produced this. Its pool is Earth Eater plus a hidden Sand Veil, so a rule
 * that only acted on a single candidate found two, concluded nothing, and let trainers fire Earthquake
 * into a Ground absorber turn after turn - healing it. No player reads the position that way: the
 * hidden ability is the exception, not the expectation.
 *
 * The individual's ability stays unknown until revealed. What is used here is the species pool, which
 * is public data, and only its ordinary entries.
 */
class LocalOrdinaryAbilityImmunityTest {
    @Test
    fun `a ground move is dead against a species whose ordinary ability absorbs ground`() {
        val facts = factsFor(hiddenSandVeil = true)
        assertEquals(
            0.0,
            facts.getValue("earthquake").let { (it.minimum + it.maximum) / 2.0 },
            "Earth Eater is the only ordinary ability Orthworm has, so Earthquake does nothing. " +
                "A hidden ability in the pool must not be what keeps the attack looking live.",
        )
        assertTrue(
            facts.getValue("ironhead").let { (it.minimum + it.maximum) / 2.0 } > 0.0,
            "Only the absorbed type is affected; everything else still connects.",
        )
    }

    @Test
    fun `an immunity only one of two ordinary abilities grants is not assumed`() {
        // The other half of the contract. Where the species could ordinarily have either ability, the
        // AI has no basis to act as though it has the immune one, and must not.
        val facts = factsFor(hiddenSandVeil = false, extraOrdinaryAbility = "sturdy")
        assertTrue(
            facts.getValue("earthquake").let { (it.minimum + it.maximum) / 2.0 } > 0.0,
            "With a second ordinary ability that grants no immunity, the attack has to stay live.",
        )
    }

    private fun factsFor(
        hiddenSandVeil: Boolean,
        extraOrdinaryAbility: String? = null,
    ): Map<String, BattleDamageFractionRange> {
        val ally = mon(BattleSide.ALLY, setOf("ground"))
        val opponent = mon(BattleSide.OPPONENT, setOf("steel"))
        val moves = listOf(move("earthquake", "ground"), move("ironhead", "steel"))
        val inferences = buildList {
            add(abilityInference(opponent.battlePokemonId, "eartheater", BattleAbilityAvailability.REGULAR))
            if (hiddenSandVeil) {
                add(abilityInference(opponent.battlePokemonId, "sandveil", BattleAbilityAvailability.HIDDEN))
            }
            extraOrdinaryAbility?.let {
                add(abilityInference(opponent.battlePokemonId, it, BattleAbilityAvailability.REGULAR))
            }
        }
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = BattleFormat.SINGLE, turn = 2,
                pokemon = listOf(ally, opponent), field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 3 },
                observedEvents = emptyList(), inferences = inferences,
            ),
            candidates = moves, deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
        return PublicBattleTacticalCalculator.calculate(context).candidates.associate {
            it.actionId to (it.facts?.standardDamageFractionRange ?: BattleDamageFractionRange(0.0, 0.0))
        }
    }

    private fun abilityInference(subject: UUID, abilityId: String, availability: BattleAbilityAvailability) =
        BattleInferenceView(
            subjectPokemonId = subject,
            categoryId = "ability",
            candidateId = abilityId,
            confidence = BattleInferenceConfidence.POSSIBLE,
            basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
            abilityAvailability = availability,
        )

    private fun move(id: String, type: String) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "cobblemon:$id", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = type, damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 100.0,
            accuracy = 100.0, priority = 0, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        ),
    )

    private fun mon(side: BattleSide, types: Set<String>) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = 0,
        speciesId = "cobblemon:probe", formId = null, level = 50, hpFraction = 1.0,
        statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null,
        knownHeldItemId = null, fainted = false, knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(160, 130, 100, 90, 100, 90)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(150, 190), BattleIntegerRange(100, 160), BattleIntegerRange(90, 140),
                BattleIntegerRange(70, 120), BattleIntegerRange(80, 130), BattleIntegerRange(60, 110),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )
}
