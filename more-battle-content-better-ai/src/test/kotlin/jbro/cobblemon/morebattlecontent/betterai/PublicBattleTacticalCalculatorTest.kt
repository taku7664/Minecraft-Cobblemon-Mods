package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicBattleTacticalCalculatorTest {
    @Test
    fun `calculator publishes standard damage and knockout ranges from bounded public stats`() {
        val calculated = PublicBattleTacticalCalculator.calculate(
            context(opponentTypes = setOf("grass"), withCombatStats = true),
        )
        val facts = requireNotNull(calculated.candidates.single().facts)

        assertEquals(0.8, facts.baseAccuracyProbability)
        assertEquals(2.0, facts.typeChartMultiplier)
        assertEquals(1.5, facts.baseSameTypeAttackBonus)
        assertEquals(BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL, facts.standardDamageModel)
        assertTrue(requireNotNull(facts.standardDamageFractionRange).minimum > 0.0)
        assertEquals(BattleKnockoutAssessment.POSSIBLE, facts.standardKnockoutAssessment)
        assertEquals(BattleFractionRange(0.0, 1.0), facts.standardDamageRollKoProbabilityRange)
        assertEquals(BattleCalculationCoverage.PARTIAL, facts.calculationCoverage)
        assertTrue(BattleCalculationBasis.PUBLIC_STAT_RANGES in facts.basis)
        assertTrue(BattleCalculationBasis.SHOWDOWN_GEN9_FORMULA in facts.basis)
        assertTrue(BattleCalculationUnknown.DYNAMIC_DAMAGE_MODIFIERS in facts.unknowns)
    }

    @Test
    fun `missing combat stats leave standard damage unknown instead of inventing a range`() {
        val facts = requireNotNull(
            PublicBattleTacticalCalculator.calculate(context(setOf("grass"), withCombatStats = false))
                .candidates.single().facts,
        )

        assertNull(facts.standardDamageModel)
        assertNull(facts.standardDamageFractionRange)
        assertNull(facts.standardDamageRollKoProbabilityRange)
        assertTrue(BattleCalculationUnknown.ATTACKER_OFFENSIVE_STATS in facts.unknowns)
        assertTrue(BattleCalculationUnknown.OPPONENT_DEFENSIVE_STATS in facts.unknowns)
    }

    @Test
    fun `unknown target typing remains unknown instead of being treated as neutral`() {
        val facts = requireNotNull(
            PublicBattleTacticalCalculator.calculate(context(opponentTypes = emptySet(), withCombatStats = true))
                .candidates.single().facts,
        )

        assertNull(facts.typeChartMultiplier)
        assertTrue(BattleCalculationUnknown.TARGET_TYPES in facts.unknowns)
    }

    @Test
    fun `calculation depends only on the decision context and is idempotent`() {
        val source = context(opponentTypes = setOf("grass", "steel"), withCombatStats = true)
        val first = PublicBattleTacticalCalculator.calculate(source)
        val second = PublicBattleTacticalCalculator.calculate(first)

        assertEquals(first.candidates.single().facts, second.candidates.single().facts)
        assertEquals(source.requestId, second.requestId)
        assertEquals(source.memory, second.memory)
    }

    @Test
    fun `public stat stages change facts without selecting an action`() {
        val neutral = requireNotNull(
            PublicBattleTacticalCalculator.calculate(context(setOf("grass"), true))
                .candidates.single().facts?.standardDamageFractionRange,
        )
        val boosted = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(setOf("grass"), true, allyStages = mapOf("cobblemon:special_attack" to 2)),
            ).candidates.single().facts?.standardDamageFractionRange,
        )

        assertTrue(boosted.minimum > neutral.minimum)
    }

    @Test
    fun `mechanic and spread moves remain unprojected until their dynamic modifiers are resolved`() {
        val mechanicFacts = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(setOf("grass"), true, mechanic = BattleMechanicCandidate("tera", null, null)),
            ).candidates.single().facts,
        )
        val spreadFacts = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(setOf("grass"), true, targetPattern = BattleMoveTargetPattern.ALL_OPPONENTS),
            ).candidates.single().facts,
        )

        assertNull(mechanicFacts.standardDamageModel)
        assertNull(spreadFacts.standardDamageModel)
        assertTrue(BattleCalculationUnknown.DYNAMIC_DAMAGE_MODIFIERS in mechanicFacts.unknowns)
    }

    @Test
    fun `declarative move effects become facts without pretending full move resolution`() {
        val effects = BattleMoveEffectsView(
            coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            effects = listOf(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.HEAL_FRACTION,
                    target = BattleMoveEffectTarget.USER,
                    probability = 1.0,
                    fractionRange = BattleFractionRange(0.5, 0.5),
                ),
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.STATUS,
                    target = BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 0.3,
                    valueId = "brn",
                ),
            ),
            scriptedBehavior = true,
        )

        val facts = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(setOf("grass"), true, effects = effects),
            ).candidates.single().facts,
        )

        assertEquals(BattleFractionRange(0.5, 0.5), facts.selfHealingFractionRange)
        assertEquals(0.24, facts.statusEffectProbability)
        assertTrue(BattleCalculationUnknown.MOVE_EFFECTS in facts.unknowns)
        assertEquals(BattleCalculationCoverage.PARTIAL, facts.calculationCoverage)
    }

    private fun context(
        opponentTypes: Set<String>,
        withCombatStats: Boolean,
        allyStages: Map<String, Int> = emptyMap(),
        mechanic: BattleMechanicCandidate? = null,
        targetPattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED,
        effects: BattleMoveEffectsView? = null,
    ): BattleDecisionContext {
        val ally = UUID.randomUUID()
        val opponent = UUID.randomUUID()
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = UUID.randomUUID(),
                format = BattleFormat.SINGLE,
                turn = 3,
                pokemon = listOf(
                    pokemon(
                        ally,
                        BattleSide.ALLY,
                        setOf("fire"),
                        ownStats().takeIf { withCombatStats },
                        allyStages,
                    ),
                    pokemon(opponent, BattleSide.OPPONENT, opponentTypes, opponentStats().takeIf { withCombatStats }),
                ),
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 3),
                observedEvents = emptyList(),
                inferences = emptyList(),
            ),
            candidates = listOf(
                BattleActionCandidate(
                    actionId = "move",
                    kind = BattleActionKind.USE_MOVE,
                    actorSlot = 0,
                    moveSlot = 0,
                    moveId = "cobblemon:flamethrower",
                    targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                    mechanic = mechanic,
                    moveDetails = BattleMoveCandidateView(
                        typeId = "fire",
                        damageCategory = BattleMoveDamageCategory.SPECIAL,
                        power = 90.0,
                        accuracy = 80.0,
                        priority = 0,
                        currentPp = 10,
                        targetPattern = targetPattern,
                        effects = effects,
                    ),
                ),
            ),
            deadlineEpochMillis = Long.MAX_VALUE,
        )
    }

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        types: Set<String>,
        combatStats: BattleCombatStatRangesView?,
        statStages: Map<String, Int> = emptyMap(),
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
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
        knownTypeIds = types,
        combatStats = combatStats,
    )

    private fun ownStats() = BattleCombatStatRangesView.exact(200, 180, 120, 200, 120, 140)

    private fun opponentStats() = BattleCombatStatRangesView(
        maxHp = BattleIntegerRange(160, 220),
        attack = BattleIntegerRange(100, 180),
        defence = BattleIntegerRange(100, 170),
        specialAttack = BattleIntegerRange(100, 180),
        specialDefence = BattleIntegerRange(100, 170),
        speed = BattleIntegerRange(90, 160),
        knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
    )
}
