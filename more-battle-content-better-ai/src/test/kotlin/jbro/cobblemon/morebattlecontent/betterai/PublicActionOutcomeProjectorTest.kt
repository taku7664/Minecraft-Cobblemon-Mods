package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.outcome.PublicActionOutcomeProjector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PublicActionOutcomeProjectorTest {
    @Test
    fun `damage and recoil are capped by the hp that can actually be removed`() {
        val move = move(
            facts = damageFacts(0.50),
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.RECOIL_FRACTION,
                        target = BattleMoveEffectTarget.USER,
                        probability = 1.0,
                        fractionRange = BattleFractionRange(1.0 / 3.0, 1.0 / 3.0),
                    ),
                ),
                scriptedBehavior = false,
            ),
        )

        val projection = PublicActionOutcomeProjector.project(move, context(move, allyHp = 1.0, opponentHp = 0.10))

        assertEquals(BattleFractionRange(0.10, 0.10), projection.damageOnHitFractionRange)
        assertEquals(0.10, projection.expectedDamageFraction)
        assertEquals(BattleFractionRange(0.0, 0.0), projection.targetHpAfterHitRange)
        assertEquals(1.0 / 30.0, projection.expectedSelfRecoilFraction!!, 0.000_001)
        assertEquals(29.0 / 30.0, projection.actorExpectedHpAfterSelfEffects!!, 0.000_001)
        assertFalse(projection.opponentActionOrderResolved)
    }

    @Test
    fun `declared recovery cannot heal beyond missing hp`() {
        val recover = move(
            power = 0.0,
            damageCategory = BattleMoveDamageCategory.STATUS,
            facts = BattleCandidateFactsView(
                baseAccuracyProbability = 1.0,
                selfHealingFractionRange = BattleFractionRange(0.5, 0.5),
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            ),
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.HEAL_FRACTION,
                        target = BattleMoveEffectTarget.USER,
                        probability = 1.0,
                        fractionRange = BattleFractionRange(0.5, 0.5),
                    ),
                ),
                scriptedBehavior = false,
            ),
        )

        val projection = PublicActionOutcomeProjector.project(recover, context(recover, allyHp = 0.8))

        assertEquals(0.2, projection.expectedSelfHealingFraction!!, 0.000_001)
        assertEquals(1.0, projection.actorExpectedHpAfterSelfEffects!!, 0.000_001)
    }

    @Test
    fun `declared level damage is projected instead of becoming a zero damage move`() {
        val fixedDamage = move(
            power = 0.0,
            facts = BattleCandidateFactsView(
                baseAccuracyProbability = 1.0,
                typeChartMultiplier = 1.0,
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            ),
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.FIXED_DAMAGE_LEVEL,
                        target = BattleMoveEffectTarget.SELECTED_TARGET,
                        probability = 1.0,
                    ),
                ),
                scriptedBehavior = false,
            ),
        )

        val projection = PublicActionOutcomeProjector.project(fixedDamage, context(fixedDamage))

        assertEquals(BattleFractionRange(0.25, 0.25), projection.damageOnHitFractionRange)
        assertEquals(0.25, projection.expectedDamageFraction)
    }

    @Test
    fun `declared one hit knockout uses hit chance and current target hp`() {
        val oneHitKnockout = move(
            power = 0.0,
            facts = BattleCandidateFactsView(
                baseAccuracyProbability = 0.30,
                typeChartMultiplier = 1.0,
                calculationCoverage = BattleCalculationCoverage.PARTIAL,
            ),
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.ONE_HIT_KO,
                        target = BattleMoveEffectTarget.SELECTED_TARGET,
                        probability = 1.0,
                    ),
                ),
                scriptedBehavior = false,
            ),
        )

        val projection = PublicActionOutcomeProjector.project(
            oneHitKnockout,
            context(oneHitKnockout, opponentHp = 0.8),
        )

        assertEquals(BattleFractionRange(0.8, 0.8), projection.damageOnHitFractionRange)
        assertEquals(0.24, projection.expectedDamageFraction!!, 1e-9)
    }

    @Test
    fun `multi hit damage uses a representative public hit count and skill link uses the maximum`() {
        val multiHit = move(
            facts = damageFacts(0.10),
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = listOf(
                    BattleMoveEffectView(
                        kind = BattleMoveEffectKind.MULTI_HIT,
                        target = BattleMoveEffectTarget.SELECTED_TARGET,
                        probability = 1.0,
                        amountRange = BattleIntegerRange(2, 5),
                    ),
                ),
                scriptedBehavior = false,
            ),
        )

        val ordinary = PublicActionOutcomeProjector.project(multiHit, context(multiHit))
        val skillLink = PublicActionOutcomeProjector.project(
            multiHit,
            context(multiHit, allyAbility = "cobblemon:skill_link"),
        )

        val ordinaryRange = requireNotNull(ordinary.damageOnHitFractionRange)
        val skillLinkRange = requireNotNull(skillLink.damageOnHitFractionRange)
        assertEquals(0.30, ordinaryRange.minimum, 1e-9)
        assertEquals(0.30, ordinaryRange.maximum, 1e-9)
        assertEquals(0.50, skillLinkRange.minimum, 1e-9)
        assertEquals(0.50, skillLinkRange.maximum, 1e-9)
    }

    private fun context(
        candidate: BattleActionCandidate,
        allyHp: Double = 1.0,
        opponentHp: Double = 1.0,
        allyAbility: String? = null,
    ): BattleDecisionContext {
        val state = BattleStateView(
            battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            format = BattleFormat.SINGLE,
            turn = 3,
            pokemon = listOf(
                pokemon(BattleSide.ALLY, allyHp, 0, allyAbility),
                pokemon(BattleSide.OPPONENT, opponentHp, 0),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        return BattleDecisionContext(
            requestId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            state = state,
            candidates = listOf(candidate),
            deadlineEpochMillis = Long.MAX_VALUE,
        )
    }

    private fun pokemon(
        side: BattleSide,
        hp: Double,
        activeSlot: Int,
        ability: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(),
        side = side,
        activeSlot = activeSlot,
        speciesId = "showdown:test",
        formId = null,
        level = 50,
        hpFraction = hp,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = ability,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 100, 100, 100, 100, 100)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(200, 200),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleIntegerRange(100, 100),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun move(
        power: Double = 100.0,
        damageCategory: BattleMoveDamageCategory = BattleMoveDamageCategory.PHYSICAL,
        facts: BattleCandidateFactsView,
        effects: BattleMoveEffectsView,
    ) = BattleActionCandidate(
        actionId = "move",
        kind = BattleActionKind.USE_MOVE,
        actorSlot = 0,
        moveSlot = 0,
        moveId = "cobblemon:test",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(
            typeId = "normal",
            damageCategory = damageCategory,
            power = power,
            accuracy = 100.0,
            priority = 0,
            currentPp = 10,
            effects = effects,
        ),
        facts = facts,
    )

    private fun damageFacts(fraction: Double) = BattleCandidateFactsView(
        baseAccuracyProbability = 1.0,
        typeChartMultiplier = 1.0,
        standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
        standardDamageFractionRange = BattleDamageFractionRange(fraction, fraction),
        standardDamageRollKoProbabilityRange = BattleFractionRange(1.0, 1.0),
        standardKnockoutAssessment = BattleKnockoutAssessment.GUARANTEED,
        calculationCoverage = BattleCalculationCoverage.PARTIAL,
    )
}
