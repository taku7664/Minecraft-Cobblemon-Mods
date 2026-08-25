package jbro.cobblemon.morebattlecontent.api.ai

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleTacticalContractTest {
    @Test
    fun `canonical trainer difficulties preserve the four approved stages`() {
        val profiles = BattleDifficultyProfiles.entries

        assertEquals(
            listOf(
                BattleTrainerTier.INTRODUCTORY to Triple(3, 1, 3),
                BattleTrainerTier.STANDARD to Triple(6, 2, 5),
                BattleTrainerTier.ADVANCED to Triple(10, 3, 8),
                BattleTrainerTier.BOSS to Triple(16, 4, 12),
            ),
            profiles.map {
                it.tier to Triple(
                    it.maximumHypothesesPerPokemon,
                    it.lookaheadPlies,
                    it.doubleCandidateLimitPerSlot,
                )
            },
        )
        assertEquals(BattleTrainerTier.INTRODUCTORY, BattleDifficultyProfiles.forSkillLevel(1).tier)
        assertEquals(BattleTrainerTier.STANDARD, BattleDifficultyProfiles.forSkillLevel(2).tier)
        assertEquals(BattleTrainerTier.ADVANCED, BattleDifficultyProfiles.forSkillLevel(4).tier)
        assertEquals(BattleTrainerTier.BOSS, BattleDifficultyProfiles.forSkillLevel(5).tier)
        assertThrows(IllegalArgumentException::class.java) {
            BattleDifficultyProfile("not_namespaced", BattleTrainerTier.BOSS, 16, 2, 12)
        }
    }

    @Test
    fun `trainer profile separates skill from structured personality`() {
        val personality = BattleTrainerPersonality(
            aggression = 0.8,
            caution = 0.2,
            switching = 0.4,
            information = 0.7,
            planPersistence = 0.9,
            riskTolerance = 0.6,
        )
        val profile = BattleTrainerProfile(skillLevel = 4, personality = personality)

        assertEquals(4, profile.skillLevel)
        assertEquals(0.7, profile.personality.information)
        assertEquals(BattleTrainerTier.ADVANCED, profile.difficulty.tier)
        val champion = BattleTrainerProfile.champion(skillLevel = 4)
        assertEquals(BattleTrainerTier.BOSS, champion.difficulty.tier)
        assertEquals(0.7, champion.personality.aggression)
        assertEquals(0.8, champion.personality.information)
        assertThrows(IllegalArgumentException::class.java) {
            BattleTrainerProfile(skillLevel = 6, personality = personality)
        }
        assertThrows(IllegalArgumentException::class.java) {
            personality.copy(riskTolerance = 1.1)
        }
    }

    @Test
    fun `candidate facts contain mechanics without utility or recommendation`() {
        val unknowns = linkedSetOf(BattleCalculationUnknown.OPPONENT_DEFENSIVE_STATS)
        val facts = BattleCandidateFactsView(
            baseAccuracyProbability = 0.8,
            typeChartMultiplier = 2.0,
            baseSameTypeAttackBonus = 1.5,
            standardDamageModel = BattleStandardDamageModel.SHOWDOWN_GEN9_BASE_NON_CRITICAL,
            standardDamageFractionRange = BattleDamageFractionRange(0.42, 0.51),
            standardDamageRollKoProbabilityRange = BattleFractionRange(0.25, 0.50),
            standardKnockoutAssessment = BattleKnockoutAssessment.POSSIBLE,
            actsFirstProbability = 0.8,
            switchEntryHpLossFraction = null,
            calculationCoverage = BattleCalculationCoverage.PARTIAL,
            unknowns = unknowns,
        )
        val candidate = BattleActionCandidate(
            actionId = "move:0:0",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:flamethrower",
            facts = facts,
        )

        unknowns.clear()

        assertEquals(setOf(BattleCalculationUnknown.OPPONENT_DEFENSIVE_STATS), candidate.facts?.unknowns)
        assertTrue(BattleCandidateFactsView::class.java.declaredFields.none {
            it.name.contains("utility", ignoreCase = true) ||
                it.name.contains("rank", ignoreCase = true) ||
                it.name.contains("recommend", ignoreCase = true)
        })
        assertThrows(IllegalArgumentException::class.java) {
            facts.copy(standardDamageRollKoProbabilityRange = BattleFractionRange(0.7, 0.6))
        }
        assertThrows(IllegalArgumentException::class.java) {
            facts.copy(standardKnockoutAssessment = null)
        }
    }

    @Test
    fun `combat stat ranges distinguish exact own knowledge from public opponent hypotheses`() {
        val exact = BattleCombatStatRangesView.exact(
            maxHp = 187,
            attack = 152,
            defence = 120,
            specialAttack = 110,
            specialDefence = 130,
            speed = 140,
        )
        val hypothesis = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(140, 187),
            attack = BattleIntegerRange(94, 167),
            defence = BattleIntegerRange(85, 150),
            specialAttack = BattleIntegerRange(80, 145),
            specialDefence = BattleIntegerRange(90, 155),
            speed = BattleIntegerRange(75, 140),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
        )

        assertEquals(BattleIntegerRange(187, 187), exact.maxHp)
        assertEquals(BattleCombatStatKnowledge.EXACT_OWN, exact.knowledge)
        assertEquals(94, hypothesis.attack.minimum)
        assertThrows(IllegalArgumentException::class.java) { BattleIntegerRange(10, 9) }
        assertThrows(IllegalArgumentException::class.java) {
            BattleCombatStatRangesView(
                maxHp = BattleIntegerRange(100, 120),
                attack = BattleIntegerRange(100, 100),
                defence = BattleIntegerRange(100, 100),
                specialAttack = BattleIntegerRange(100, 100),
                specialDefence = BattleIntegerRange(100, 100),
                speed = BattleIntegerRange(100, 100),
                knowledge = BattleCombatStatKnowledge.EXACT_OWN,
            )
        }
    }

    @Test
    fun `decision advice describes a plan without storing a future action id`() {
        val advice = BattleDecisionAdvice(
            prediction = BattlePrediction(
                response = BattlePredictedResponse.SWITCH,
                confidence = 0.7,
            ),
            planUpdate = BattlePlanUpdate.replace(
                BattlePlanView(
                    intent = BattlePlanIntent.CREATE_SAFE_ENTRY,
                    targetRole = BattleTeamRole.ACE,
                    expiresAtTurn = 8,
                    abortIf = setOf(BattlePlanAbortCondition.TARGET_ROLE_UNAVAILABLE),
                ),
            ),
            reasonCodes = setOf(BattleDecisionReason.PRESERVE_WIN_CONDITION),
            mindGameIntent = BattleMindGameIntent.PREDICT_SWITCH,
        )

        assertEquals(BattlePlanUpdateOperation.REPLACE, advice.planUpdate.operation)
        assertEquals(BattlePlanIntent.CREATE_SAFE_ENTRY, advice.planUpdate.plan?.intent)
        assertTrue(BattleDecisionAdvice::class.java.declaredFields.none { it.name.contains("actionId", ignoreCase = true) })
        assertThrows(IllegalArgumentException::class.java) {
            BattlePlanUpdate(BattlePlanUpdateOperation.KEEP, advice.planUpdate.plan)
        }
    }

    @Test
    fun `decision context exposes one immutable tactical memory snapshot to every brain`() {
        val memory = BattleTacticalMemoryView(
            activePlan = BattlePlanView(BattlePlanIntent.PRESERVE_CORE, BattleTeamRole.ACE, 6),
            tendencies = listOf(
                BattleTendencyView(
                    situation = BattleSituation.UNDER_KO_THREAT,
                    response = BattlePredictedResponse.SWITCH,
                    samples = 5,
                    recentWeight = 3.5,
                    estimatedRate = 0.7,
                ),
            ),
            predictionCalibration = BattlePredictionCalibrationView(
                samples = 4,
                hits = 3,
                consecutiveMisses = 0,
                topResponseBrierScore = 0.18,
                alwaysMoveBrierScore = 0.5,
            ),
            turnsSinceLastSwitch = 2,
            switchesThisBattle = 1,
            switchPressure = 1.5,
            lastMoveId = "cobblemon:shadowball",
            sameMoveRepeatCount = 3,
            patternExposureCount = 3,
            patternResponseShiftEvidence = 0.65,
            opponentResponseVolatility = 0.25,
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = emptyState(),
            candidates = listOf(BattleActionCandidate("wait", BattleActionKind.WAIT)),
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = memory,
        )

        assertEquals(memory, context.memory)
        assertEquals(0.7, context.memory.tendencies.single().estimatedRate)
        assertEquals(0.64, context.memory.predictionCalibration.brierSkillScoreAgainstAlwaysMove)
        assertEquals(1.5, context.memory.switchPressure)
        assertEquals(3, context.memory.patternExposureCount)
        assertEquals(0.65, context.memory.patternResponseShiftEvidence)
        assertThrows(IllegalArgumentException::class.java) {
            BattleTacticalMemoryView(switchPressure = -0.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BattleTacticalMemoryView(switchPressure = Double.NaN)
        }
    }

    @Test
    fun `future action catalog separates exact own moves from publicly revealed opponent moves`() {
        val allyId = UUID.randomUUID()
        val opponentId = UUID.randomUUID()
        val allyMoves = arrayListOf(
            BattlePublicMoveOptionView(
                moveId = "cobblemon:surf",
                details = moveDetails("water", 90.0),
                knowledge = BattlePublicMoveKnowledge.EXACT_OWN,
            ),
        )
        val entries = arrayListOf(
            BattlePokemonActionCatalogView(allyId, allyMoves),
            BattlePokemonActionCatalogView(
                opponentId,
                listOf(
                    BattlePublicMoveOptionView(
                        moveId = "cobblemon:shadow_ball",
                        details = moveDetails("ghost", 80.0),
                        knowledge = BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                    ),
                ),
            ),
        )
        val catalog = BattlePublicActionCatalogView(entries)

        allyMoves.clear()
        entries.clear()

        assertEquals("cobblemon:surf", catalog.forPokemon(allyId).single().moveId)
        assertEquals(BattlePublicMoveKnowledge.PUBLICLY_REVEALED, catalog.forPokemon(opponentId).single().knowledge)
        assertTrue(catalog.forPokemon(UUID.randomUUID()).isEmpty())
    }

    private fun moveDetails(typeId: String, power: Double) = BattleMoveCandidateView(
        typeId = typeId,
        damageCategory = BattleMoveDamageCategory.SPECIAL,
        power = power,
        accuracy = 100.0,
        priority = 0,
        currentPp = 1,
    )

    private fun emptyState() = BattleStateView(
        battleId = UUID.randomUUID(),
        format = BattleFormat.SINGLE,
        turn = 3,
        pokemon = emptyList(),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = BattleSide.entries.associateWith { 0 },
        observedEvents = emptyList(),
        inferences = emptyList(),
    )
}
