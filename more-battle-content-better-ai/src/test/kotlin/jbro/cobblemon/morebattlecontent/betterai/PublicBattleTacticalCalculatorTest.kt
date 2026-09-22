package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalTacticalScorer
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
    fun `body press uses the users defence and defence stage as its offensive stat`() {
        val weakAttack = BattleCombatStatRangesView.exact(200, 40, 240, 80, 120, 100)
        val neutral = damage(
            context(
                setOf("normal"), true, moveId = "bodypress", moveType = "fighting",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0, allyStats = weakAttack,
            ),
        )
        val boosted = damage(
            context(
                setOf("normal"), true, moveId = "bodypress", moveType = "fighting",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0, allyStats = weakAttack,
                allyStages = mapOf("defense" to 2),
            ),
        )
        val ordinary = damage(
            context(
                setOf("normal"), true, moveId = "tackle", moveType = "normal",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0, allyStats = weakAttack,
            ),
        )
        val poisonedGuts = damage(
            context(
                setOf("normal"), true, moveId = "bodypress", moveType = "fighting",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0, allyStats = weakAttack,
                allyStatus = "psn", allyAbility = "guts",
            ),
        )

        assertTrue(neutral.minimum > ordinary.minimum * 4.0)
        assertTrue(boosted.minimum > neutral.minimum * 1.9)
        assertEquals(neutral, poisonedGuts)
    }

    @Test
    fun `foul play uses the targets attack and attack stage`() {
        val weakActor = BattleCombatStatRangesView.exact(200, 30, 120, 80, 120, 100)
        val neutral = damage(
            context(
                setOf("normal"), true, moveId = "foulplay", moveType = "dark",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 95.0, allyStats = weakActor,
            ),
        )
        val boostedTarget = damage(
            context(
                setOf("normal"), true, moveId = "foulplay", moveType = "dark",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 95.0, allyStats = weakActor,
                opponentStages = mapOf("attack" to 2),
            ),
        )
        val ordinary = damage(
            context(
                setOf("normal"), true, moveId = "bite", moveType = "dark",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 95.0, allyStats = weakActor,
            ),
        )

        assertTrue(neutral.minimum > ordinary.minimum * 2.5)
        assertTrue(boostedTarget.minimum > neutral.minimum * 1.9)
    }

    @Test
    fun `stored power resolves its public positive stat stages into base power`() {
        val neutral = damage(
            context(
                setOf("normal"), true, moveId = "storedpower", moveType = "psychic",
                damageCategory = BattleMoveDamageCategory.SPECIAL, power = 20.0,
            ),
        )
        val boosted = damage(
            context(
                setOf("normal"), true, moveId = "storedpower", moveType = "psychic",
                damageCategory = BattleMoveDamageCategory.SPECIAL, power = 20.0,
                allyStages = mapOf("special_attack" to 2, "special_defense" to 1, "speed" to -1),
            ),
        )

        assertTrue(boosted.minimum > neutral.minimum * 4.5)
    }

    @Test
    fun `public status and hp conditions resolve conditional base power`() {
        val facadePlain = damage(
            context(
                setOf("normal"), true, moveId = "facade", moveType = "normal",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 70.0,
            ),
        )
        val facadeStatused = damage(
            context(
                setOf("normal"), true, moveId = "facade", moveType = "normal",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 70.0, allyStatus = "brn",
            ),
        )
        val hexPlain = damage(
            context(
                setOf("fire"), true, moveId = "hex", moveType = "ghost",
                damageCategory = BattleMoveDamageCategory.SPECIAL, power = 65.0,
            ),
        )
        val hexStatused = damage(
            context(
                setOf("fire"), true, moveId = "hex", moveType = "ghost",
                damageCategory = BattleMoveDamageCategory.SPECIAL, power = 65.0, opponentStatus = "par",
            ),
        )
        val brinePlain = damage(
            context(
                setOf("normal"), true, moveId = "brine", moveType = "water",
                damageCategory = BattleMoveDamageCategory.SPECIAL, power = 65.0,
            ),
        )
        val brineWeakened = damage(
            context(
                setOf("normal"), true, moveId = "brine", moveType = "water",
                damageCategory = BattleMoveDamageCategory.SPECIAL, power = 65.0, opponentHp = 0.5,
            ),
        )

        assertTrue(facadeStatused.minimum > facadePlain.minimum * 1.9)
        assertTrue(hexStatused.minimum > hexPlain.minimum * 1.9)
        assertTrue(brineWeakened.minimum > brinePlain.minimum * 1.9)
    }

    @Test
    fun `unavailable cumulative inputs do not publish a false exact rage fist range`() {
        val calculated = PublicBattleTacticalCalculator.calculate(
            context(
                setOf("normal"), true, moveId = "ragefist", moveType = "ghost",
                damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 50.0,
            ),
        )
        val facts = requireNotNull(calculated.candidates.single().facts)

        assertNull(facts.standardDamageFractionRange)
        assertNull(facts.standardKnockoutAssessment)
        assertTrue(BattleCalculationUnknown.DAMAGE_ENGINE in facts.unknowns)
        assertEquals(0.0, LocalTacticalScorer.unprojectedPressureOf(calculated.candidates.single(), calculated))
    }

    @Test
    fun `psyshock uses defence and wonder room swaps the defensive stats`() {
        val targetStats = BattleCombatStatRangesView(
            BattleIntegerRange(200, 200), BattleIntegerRange(100, 100), BattleIntegerRange(50, 50),
            BattleIntegerRange(100, 100), BattleIntegerRange(300, 300), BattleIntegerRange(100, 100),
            BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        )
        val special = damage(context(
            setOf("normal"), true, moveType = "psychic", opponentCombatStats = targetStats,
        ))
        val psyshockEffects = BattleMoveEffectsView(
            BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            emptyList(),
            scriptedBehavior = false,
            mechanicFlags = setOf("override_defensive_stat:defence"),
        )
        val psyshock = damage(context(
            setOf("normal"), true, moveId = "psyshock", moveType = "psychic",
            effects = psyshockEffects, opponentCombatStats = targetStats,
        ))
        val specialInWonderRoom = damage(context(
            setOf("normal"), true, moveType = "psychic", opponentCombatStats = targetStats, room = "wonderroom",
        ))
        val psyshockInWonderRoom = damage(context(
            setOf("normal"), true, moveId = "psyshock", moveType = "psychic",
            effects = psyshockEffects, opponentCombatStats = targetStats, room = "wonderroom",
        ))

        assertTrue(psyshock.minimum > special.minimum * 4.0)
        assertTrue(specialInWonderRoom.minimum > psyshockInWonderRoom.minimum * 4.0)

        val defensiveAttacker = BattleCombatStatRangesView.exact(200, 100, 50, 100, 300, 100)
        val bodyPress = damage(context(
            setOf("normal"), true, moveId = "bodypress", moveType = "fighting",
            damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0, allyStats = defensiveAttacker,
        ))
        val bodyPressInWonderRoom = damage(context(
            setOf("normal"), true, moveId = "bodypress", moveType = "fighting",
            damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 80.0, allyStats = defensiveAttacker,
            room = "wonderroom",
        ))
        assertTrue(bodyPressInWonderRoom.minimum > bodyPress.minimum * 4.0)
    }

    @Test
    fun `magic room suppresses public choice item damage`() {
        val ordinary = damage(context(setOf("normal"), true))
        val specs = damage(context(setOf("normal"), true, allyItem = "choicespecs"))
        val specsInMagicRoom = damage(context(
            setOf("normal"), true, allyItem = "choicespecs", room = "magicroom",
        ))

        assertTrue(specs.minimum > ordinary.minimum * 1.4)
        assertEquals(ordinary, specsInMagicRoom)
    }

    @Test
    fun `declarative dynamic damage flags cannot fall back to template power`() {
        val effects = BattleMoveEffectsView(
            BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            emptyList(),
            scriptedBehavior = true,
            mechanicFlags = setOf("dynamic_base_power"),
        )
        val calculated = PublicBattleTacticalCalculator.calculate(context(
            setOf("normal"), true, moveId = "customcallback", effects = effects, power = 120.0,
        ))
        val candidate = calculated.candidates.single()

        assertNull(candidate.facts?.standardDamageFractionRange)
        assertEquals(0.0, LocalTacticalScorer.unprojectedPressureOf(candidate, calculated))
    }

    @Test
    fun `unresolved mechanics stay unprojected while spread moves use their explicit public target`() {
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
        assertEquals(BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL, spreadFacts.standardDamageModel)
        assertTrue(spreadFacts.standardDamageFractionRange != null)
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

    @Test
    fun `ignore type immunity removes only the immune type contribution`() {
        val effects = BattleMoveEffectsView(
            coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
            effects = listOf(
                BattleMoveEffectView(
                    kind = BattleMoveEffectKind.IGNORE_TYPE_IMMUNITY,
                    target = BattleMoveEffectTarget.SELECTED_TARGET,
                    probability = 1.0,
                ),
            ),
            scriptedBehavior = false,
        )

        val facts = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(
                    opponentTypes = setOf("flying", "fire"),
                    withCombatStats = true,
                    effects = effects,
                    moveType = "ground",
                    damageCategory = BattleMoveDamageCategory.PHYSICAL,
                ),
            ).candidates.single().facts,
        )

        assertEquals(2.0, facts.typeChartMultiplier)
        assertTrue(requireNotNull(facts.standardDamageFractionRange).minimum > 0.0)
    }

    @Test
    fun `freeze dry is super effective against water instead of using the ordinary ice chart`() {
        val ordinaryIce = PublicBattleTacticalCalculator.calculate(
            context(
                opponentTypes = setOf("water"),
                withCombatStats = true,
                moveId = "icebeam",
                moveType = "ice",
                power = 70.0,
            ),
        ).candidates.single()
        val freezeDry = PublicBattleTacticalCalculator.calculate(
            context(
                opponentTypes = setOf("water"),
                withCombatStats = true,
                moveId = "freezedry",
                moveType = "ice",
                power = 70.0,
            ),
        ).candidates.single()

        assertEquals(0.5, ordinaryIce.facts?.typeChartMultiplier)
        assertEquals(2.0, freezeDry.facts?.typeChartMultiplier)
        val ordinaryDamage = requireNotNull(ordinaryIce.facts?.standardDamageFractionRange)
        val freezeDryDamage = requireNotNull(freezeDry.facts?.standardDamageFractionRange)
        assertTrue(freezeDryDamage.minimum > ordinaryDamage.minimum * 3.9)
    }

    @Test
    fun `always critical ignores harmful offensive and helpful defensive stages and gains crit damage`() {
        val ordinary = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(
                    setOf("normal"),
                    true,
                    allyStages = mapOf("attack" to -2),
                    opponentStages = mapOf("defense" to 2),
                    damageCategory = BattleMoveDamageCategory.PHYSICAL,
                ),
            ).candidates.single().facts?.standardDamageFractionRange,
        )
        val critical = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(
                    setOf("normal"),
                    true,
                    allyStages = mapOf("attack" to -2),
                    opponentStages = mapOf("defense" to 2),
                    damageCategory = BattleMoveDamageCategory.PHYSICAL,
                    effects = singleEffect(BattleMoveEffectKind.ALWAYS_CRITICAL),
                ),
            ).candidates.single().facts?.standardDamageFractionRange,
        )

        assertTrue(critical.minimum > ordinary.minimum * 3.0)
        assertTrue(critical.maximum > ordinary.maximum * 3.0)
    }

    @Test
    fun `ignore defensive stages bypasses only the targets defence boost`() {
        val ordinary = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(
                    setOf("normal"),
                    true,
                    opponentStages = mapOf("defense" to 4),
                    damageCategory = BattleMoveDamageCategory.PHYSICAL,
                ),
            ).candidates.single().facts?.standardDamageFractionRange,
        )
        val ignored = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(
                    setOf("normal"),
                    true,
                    opponentStages = mapOf("defense" to 4),
                    damageCategory = BattleMoveDamageCategory.PHYSICAL,
                    effects = singleEffect(BattleMoveEffectKind.IGNORE_DEFENSIVE_STAGES),
                ),
            ).candidates.single().facts?.standardDamageFractionRange,
        )

        assertTrue(ignored.minimum > ordinary.minimum * 2.0)
        assertTrue(ignored.maximum > ordinary.maximum * 2.0)
    }

    @Test
    fun `future move slot condition is not published as immediate damage`() {
        val facts = requireNotNull(
            PublicBattleTacticalCalculator.calculate(
                context(
                    setOf("normal"),
                    true,
                    effects = BattleMoveEffectsView(
                        BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                        listOf(
                            BattleMoveEffectView(
                                BattleMoveEffectKind.SLOT_CONDITION,
                                BattleMoveEffectTarget.SELECTED_TARGET,
                                valueId = "futuremove",
                            ),
                        ),
                        scriptedBehavior = true,
                    ),
                ),
            ).candidates.single().facts,
        )

        assertNull(facts.standardDamageFractionRange)
        assertNull(facts.standardKnockoutAssessment)
    }

    private fun context(
        opponentTypes: Set<String>,
        withCombatStats: Boolean,
        allyStages: Map<String, Int> = emptyMap(),
        mechanic: BattleMechanicCandidate? = null,
        targetPattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        effects: BattleMoveEffectsView? = null,
        moveType: String = "fire",
        damageCategory: BattleMoveDamageCategory = BattleMoveDamageCategory.SPECIAL,
        opponentStages: Map<String, Int> = emptyMap(),
        moveId: String = "flamethrower",
        power: Double = 90.0,
        allyStats: BattleCombatStatRangesView = ownStats(),
        allyStatus: String? = null,
        opponentStatus: String? = null,
        opponentHp: Double = 1.0,
        allyAbility: String? = null,
        allyItem: String? = null,
        opponentItem: String? = null,
        opponentCombatStats: BattleCombatStatRangesView = opponentStats(),
        room: String? = null,
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
                        allyStats.takeIf { withCombatStats },
                        allyStages,
                        statusId = allyStatus,
                        knownAbilityId = allyAbility,
                        knownHeldItemId = allyItem,
                    ),
                    pokemon(
                        opponent,
                        BattleSide.OPPONENT,
                        opponentTypes,
                        opponentCombatStats.takeIf { withCombatStats },
                        opponentStages,
                        statusId = opponentStatus,
                        hpFraction = opponentHp,
                        knownHeldItemId = opponentItem,
                    ),
                ),
                field = BattleFieldStateView(
                    weather = null,
                    terrain = null,
                    roomEffects = room?.let { listOf(BattleTimedEffectView(it, 3)) }.orEmpty(),
                    globalEffects = emptyList(),
                    sideConditions = BattleSide.entries.associateWith { emptyList() },
                ),
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
                    moveId = "cobblemon:$moveId",
                    targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                    mechanic = mechanic,
                    moveDetails = BattleMoveCandidateView(
                        typeId = moveType,
                        damageCategory = damageCategory,
                        power = power,
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
        statusId: String? = null,
        hpFraction: Double = 1.0,
        knownAbilityId: String? = null,
        knownHeldItemId: String? = null,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = 0,
        speciesId = "showdown:test",
        formId = null,
        level = 50,
        hpFraction = hpFraction,
        statusId = statusId,
        statStages = statStages,
        knownMoveIds = emptySet(),
        knownAbilityId = knownAbilityId,
        knownHeldItemId = knownHeldItemId,
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

    private fun damage(context: BattleDecisionContext) = requireNotNull(
        PublicBattleTacticalCalculator.calculate(context).candidates.single().facts?.standardDamageFractionRange,
    )

    private fun singleEffect(kind: BattleMoveEffectKind) = BattleMoveEffectsView(
        BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
        listOf(BattleMoveEffectView(kind, BattleMoveEffectTarget.SELECTED_TARGET, probability = 1.0)),
        scriptedBehavior = false,
    )
}
