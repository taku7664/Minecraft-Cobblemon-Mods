package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * An Electric move does not reach the slot beside a Lightning Rod.
 *
 * The redirecting abilities were modelled only as absorbers standing on their own slot - the whole of
 * what they do in singles, and half of what they do in doubles. The other half is that they take the
 * move away from the partner.
 *
 * Missing it is not a small mispricing. The AI projected full damage on the slot it aimed at, spent
 * the turn, dealt nothing, and handed the opponent a free Special Attack stage - and would have done
 * it again the next turn, because nothing about the failure appears in the public state it reads.
 *
 * These assert the resolved target through what the facts say about it, because that is what the
 * ranking sees. A multiplier of zero here means the move was resolved against the Rod holder and the
 * absorbing-ability reading then did its ordinary job.
 */
class LocalDoublesRedirectionTest {
    @Test
    fun `a revealed lightning rod pulls the move off its partner`() {
        val plain = facts(partnerAbility = null)
        assertEquals(1.0, plain?.typeChartMultiplier, "Without the Rod the declared target is hit normally.")

        val redirected = facts(partnerAbility = "cobblemon:lightning_rod")
        assertEquals(
            0.0, redirected?.typeChartMultiplier,
            "The move is resolved against the Rod holder, which absorbs it.",
        )
        assertEquals(
            0.0, redirected?.standardDamageFractionRange?.maximum,
            "So no damage reaches the slot the trainer was aiming at.",
        )
    }

    @Test
    fun `the species' only ordinary ability is enough`() {
        val inferred = facts(partnerAbility = null, partnerInferredOrdinary = listOf("lightningrod"))
        assertEquals(
            0.0, inferred?.typeChartMultiplier,
            "Same standard as every other ability reading: revealed, or the only ordinary one.",
        )
    }

    @Test
    fun `a storm drain does not touch an electric move`() {
        val drain = facts(partnerAbility = "cobblemon:storm_drain")
        assertEquals(
            1.0, drain?.typeChartMultiplier,
            "Storm Drain redirects Water. An Electric move goes where it was aimed.",
        )
    }

    @Test
    fun `singles has no partner to redirect to`() {
        val single = facts(partnerAbility = "cobblemon:lightning_rod", format = BattleFormat.SINGLE)
        assertEquals(
            1.0, single?.typeChartMultiplier,
            "With one opponent on the field there is nowhere for the move to be pulled.",
        )
    }

    @Test
    fun `a spread move is not redirected`() {
        val spread = facts(
            partnerAbility = "cobblemon:lightning_rod",
            pattern = BattleMoveTargetPattern.ALL_OPPONENTS,
            explicitTarget = false,
        )
        assertEquals(
            1.0, spread?.typeChartMultiplier,
            "A move that already hits every slot cannot be pulled to one of them.",
        )
    }

    private fun facts(
        partnerAbility: String?,
        partnerInferredOrdinary: List<String> = emptyList(),
        format: BattleFormat = BattleFormat.DOUBLE,
        pattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        explicitTarget: Boolean = true,
    ): BattleCandidateFactsView? {
        val actor = mon(BattleSide.ALLY, 0, null)
        val declared = mon(BattleSide.OPPONENT, 0, null)
        val partner = mon(BattleSide.OPPONENT, 1, partnerAbility)
        val doubles = format == BattleFormat.DOUBLE
        val pokemon = listOf(actor, declared) + if (doubles) listOf(partner) else emptyList()
        val move = BattleActionCandidate(
            actionId = "bolt", kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
            moveId = "cobblemon:thunderbolt",
            targets = if (explicitTarget) listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)) else emptyList(),
            moveDetails = BattleMoveCandidateView(
                typeId = "electric", damageCategory = BattleMoveDamageCategory.SPECIAL, power = 90.0,
                accuracy = 100.0, priority = 0, currentPp = 10, targetPattern = pattern,
            ),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(), format = format, turn = 2,
                pokemon = pokemon, field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { if (doubles) 4 else 3 },
                observedEvents = emptyList(),
                inferences = partnerInferredOrdinary.map {
                    BattleInferenceView(
                        subjectPokemonId = partner.battlePokemonId,
                        categoryId = "ability", candidateId = it,
                        confidence = BattleInferenceConfidence.POSSIBLE,
                        basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
                        abilityAvailability = BattleAbilityAvailability.REGULAR,
                    )
                }.filter { doubles },
            ),
            candidates = listOf(move), deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = BattlePublicActionCatalogView(emptyList()),
        )
        return PublicBattleTacticalCalculator.calculate(context).candidates.single().facts
    }

    private fun mon(side: BattleSide, slot: Int, abilityId: String?) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot,
        speciesId = "cobblemon:probe_${side.name.lowercase()}_$slot", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = abilityId, knownHeldItemId = null, fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 100, 100, 140, 100, 100)
        } else {
            publicExactStats(200, 100, 100, 100, 100, 90)
        },
    )
}
