package jbro.cobblemon.morebattlecontent.api.ai

import java.util.Collections

enum class BattleTrainerTier {
    INTRODUCTORY,
    STANDARD,
    ADVANCED,
    BOSS,
}

data class BattleDifficultyProfile(
    val id: String,
    val tier: BattleTrainerTier,
    val maximumHypothesesPerPokemon: Int,
    val lookaheadPlies: Int,
    val doubleCandidateLimitPerSlot: Int,
) {
    init {
        require(RESOURCE_ID.matches(id)) { "Difficulty profile id must be a lowercase namespaced id" }
        require(maximumHypothesesPerPokemon > 0)
        require(lookaheadPlies >= 0)
        require(doubleCandidateLimitPerSlot > 0)
    }

    private companion object {
        val RESOURCE_ID = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")
    }
}

object BattleDifficultyProfiles {
    @JvmField
    val INTRODUCTORY = BattleDifficultyProfile(
        id = "cobblemon_more_battle_content:introductory",
        tier = BattleTrainerTier.INTRODUCTORY,
        maximumHypothesesPerPokemon = 3,
        lookaheadPlies = 1,
        doubleCandidateLimitPerSlot = 3,
    )

    @JvmField
    val STANDARD = BattleDifficultyProfile(
        id = "cobblemon_more_battle_content:standard",
        tier = BattleTrainerTier.STANDARD,
        maximumHypothesesPerPokemon = 6,
        lookaheadPlies = 2,
        doubleCandidateLimitPerSlot = 5,
    )

    @JvmField
    val ADVANCED = BattleDifficultyProfile(
        id = "cobblemon_more_battle_content:advanced",
        tier = BattleTrainerTier.ADVANCED,
        maximumHypothesesPerPokemon = 10,
        lookaheadPlies = 3,
        doubleCandidateLimitPerSlot = 8,
    )

    @JvmField
    val BOSS = BattleDifficultyProfile(
        id = "cobblemon_more_battle_content:boss",
        tier = BattleTrainerTier.BOSS,
        maximumHypothesesPerPokemon = 16,
        lookaheadPlies = 4,
        doubleCandidateLimitPerSlot = 12,
    )

    @JvmField
    val entries: List<BattleDifficultyProfile> = Collections.unmodifiableList(
        listOf(INTRODUCTORY, STANDARD, ADVANCED, BOSS),
    )

    @JvmStatic
    fun forTier(tier: BattleTrainerTier): BattleDifficultyProfile = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> INTRODUCTORY
        BattleTrainerTier.STANDARD -> STANDARD
        BattleTrainerTier.ADVANCED -> ADVANCED
        BattleTrainerTier.BOSS -> BOSS
    }

    @JvmStatic
    fun forSkillLevel(skillLevel: Int): BattleDifficultyProfile {
        require(skillLevel in 0..5) { "Battle trainer skill level must be between 0 and 5" }
        return when (skillLevel) {
            0, 1 -> INTRODUCTORY
            2 -> STANDARD
            3, 4 -> ADVANCED
            else -> BOSS
        }
    }
}

data class BattleTrainerPersonality(
    val aggression: Double,
    val caution: Double,
    val switching: Double,
    val information: Double,
    val planPersistence: Double,
    val riskTolerance: Double,
) {
    init {
        values().forEach { require(it.isFinite() && it in 0.0..1.0) }
    }

    private fun values() = listOf(aggression, caution, switching, information, planPersistence, riskTolerance)

    companion object {
        @JvmStatic
        fun balanced(): BattleTrainerPersonality = BattleTrainerPersonality(
            aggression = 0.5,
            caution = 0.5,
            switching = 0.5,
            information = 0.5,
            planPersistence = 0.5,
            riskTolerance = 0.5,
        )
    }
}

data class BattleTrainerProfile @JvmOverloads constructor(
    val skillLevel: Int,
    val personality: BattleTrainerPersonality,
    val difficulty: BattleDifficultyProfile = BattleDifficultyProfiles.forSkillLevel(skillLevel),
) {
    init {
        require(skillLevel in 0..5) { "Battle trainer skill level must be between 0 and 5" }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun balanced(
            skillLevel: Int = 0,
            difficulty: BattleDifficultyProfile = BattleDifficultyProfiles.forSkillLevel(skillLevel),
        ): BattleTrainerProfile = BattleTrainerProfile(skillLevel, BattleTrainerPersonality.balanced(), difficulty)

        @JvmStatic
        fun boss(skillLevel: Int = 5): BattleTrainerProfile =
            BattleTrainerProfile(skillLevel, BattleTrainerPersonality.balanced(), BattleDifficultyProfiles.BOSS)

        @JvmStatic
        fun champion(skillLevel: Int = 5): BattleTrainerProfile = BattleTrainerProfile(
            skillLevel = skillLevel,
            personality = BattleTrainerPersonality(
                aggression = 0.70,
                caution = 0.55,
                switching = 0.65,
                information = 0.80,
                planPersistence = 0.70,
                riskTolerance = 0.45,
            ),
            difficulty = BattleDifficultyProfiles.BOSS,
        )
    }
}

data class BattleFractionRange(val minimum: Double, val maximum: Double) {
    init {
        require(minimum.isFinite() && maximum.isFinite())
        require(minimum in 0.0..1.0 && maximum in 0.0..1.0)
        require(minimum <= maximum)
    }
}

data class BattleDamageFractionRange(val minimum: Double, val maximum: Double) {
    init {
        require(minimum.isFinite() && maximum.isFinite())
        require(minimum >= 0.0 && maximum >= 0.0)
        require(minimum <= maximum)
    }
}

data class BattleIntegerRange(val minimum: Int, val maximum: Int) {
    init {
        require(minimum > 0 && maximum > 0)
        require(minimum <= maximum)
    }
}

enum class BattleCombatStatKnowledge { EXACT_OWN, PUBLIC_SPECIES_RANGE }

data class BattleCombatStatRangesView(
    val maxHp: BattleIntegerRange,
    val attack: BattleIntegerRange,
    val defence: BattleIntegerRange,
    val specialAttack: BattleIntegerRange,
    val specialDefence: BattleIntegerRange,
    val speed: BattleIntegerRange,
    val knowledge: BattleCombatStatKnowledge,
) {
    init {
        if (knowledge == BattleCombatStatKnowledge.EXACT_OWN) {
            require(listOf(maxHp, attack, defence, specialAttack, specialDefence, speed).all {
                it.minimum == it.maximum
            }) { "Exact own combat stats cannot contain a range" }
        }
    }

    companion object {
        @JvmStatic
        fun exact(
            maxHp: Int,
            attack: Int,
            defence: Int,
            specialAttack: Int,
            specialDefence: Int,
            speed: Int,
        ) = BattleCombatStatRangesView(
            maxHp = BattleIntegerRange(maxHp, maxHp),
            attack = BattleIntegerRange(attack, attack),
            defence = BattleIntegerRange(defence, defence),
            specialAttack = BattleIntegerRange(specialAttack, specialAttack),
            specialDefence = BattleIntegerRange(specialDefence, specialDefence),
            speed = BattleIntegerRange(speed, speed),
            knowledge = BattleCombatStatKnowledge.EXACT_OWN,
        )
    }
}

enum class BattleCalculationCoverage { EXACT, PARTIAL, UNKNOWN }

enum class BattleStandardDamageModel { SHOWDOWN_GEN9_BASE_NON_CRITICAL }

enum class BattleKnockoutAssessment { GUARANTEED, POSSIBLE, IMPOSSIBLE }

enum class BattleCalculationUnknown {
    ATTACKER_OFFENSIVE_STATS,
    OPPONENT_DEFENSIVE_STATS,
    TARGET_CURRENT_HP,
    TARGET_TYPES,
    ACCURACY_MODIFIERS,
    ACTION_ORDER,
    DAMAGE_ENGINE,
    DYNAMIC_DAMAGE_MODIFIERS,
    MOVE_EFFECTS,
    ENTRY_EFFECTS,
}

enum class BattleCalculationBasis {
    MOVE_TEMPLATE,
    PUBLIC_TYPES,
    PUBLIC_STAT_RANGES,
    SHOWDOWN_GEN9_FORMULA,
    SERVER_PROVIDED_MECHANICS,
}

/**
 * Mechanical facts only. This contract deliberately has no utility, rank, recommendation, or
 * tactical evidence label: interpreting these dimensions belongs to the selected BattleBrain.
 */
class BattleCandidateFactsView(
    val baseAccuracyProbability: Double? = null,
    val typeChartMultiplier: Double? = null,
    val baseSameTypeAttackBonus: Double? = null,
    val standardDamageModel: BattleStandardDamageModel? = null,
    val standardDamageFractionRange: BattleDamageFractionRange? = null,
    val standardDamageRollKoProbabilityRange: BattleFractionRange? = null,
    val standardKnockoutAssessment: BattleKnockoutAssessment? = null,
    val actsFirstProbability: Double? = null,
    val selfHealingFractionRange: BattleFractionRange? = null,
    val selfRecoilFractionRange: BattleFractionRange? = null,
    val statusEffectProbability: Double? = null,
    val switchEntryHpLossFraction: Double? = null,
    val calculationCoverage: BattleCalculationCoverage = BattleCalculationCoverage.UNKNOWN,
    unknowns: Set<BattleCalculationUnknown> = emptySet(),
    basis: Set<BattleCalculationBasis> = emptySet(),
) {
    val unknowns: Set<BattleCalculationUnknown> = Collections.unmodifiableSet(LinkedHashSet(unknowns))
    val basis: Set<BattleCalculationBasis> = Collections.unmodifiableSet(LinkedHashSet(basis))

    init {
        requireProbability(baseAccuracyProbability, "base accuracy probability")
        requireProbability(actsFirstProbability, "first-action probability")
        requireProbability(statusEffectProbability, "status-effect probability")
        requireProbability(switchEntryHpLossFraction, "switch entry HP loss")
        require(typeChartMultiplier == null || typeChartMultiplier.isFinite() && typeChartMultiplier >= 0.0)
        require(baseSameTypeAttackBonus == null || baseSameTypeAttackBonus.isFinite() && baseSameTypeAttackBonus >= 1.0)
        val standardFields = listOf(
            standardDamageFractionRange,
            standardDamageRollKoProbabilityRange,
            standardKnockoutAssessment,
        )
        require(
            if (standardDamageModel == null) standardFields.all { it == null }
            else standardFields.all { it != null },
        ) {
            "Standard damage fields must be complete and require one explicit standard damage model"
        }
        require(calculationCoverage != BattleCalculationCoverage.EXACT || this.unknowns.isEmpty()) {
            "An exact calculation cannot retain unknown inputs"
        }
    }

    fun copy(
        baseAccuracyProbability: Double? = this.baseAccuracyProbability,
        typeChartMultiplier: Double? = this.typeChartMultiplier,
        baseSameTypeAttackBonus: Double? = this.baseSameTypeAttackBonus,
        standardDamageModel: BattleStandardDamageModel? = this.standardDamageModel,
        standardDamageFractionRange: BattleDamageFractionRange? = this.standardDamageFractionRange,
        standardDamageRollKoProbabilityRange: BattleFractionRange? = this.standardDamageRollKoProbabilityRange,
        standardKnockoutAssessment: BattleKnockoutAssessment? = this.standardKnockoutAssessment,
        actsFirstProbability: Double? = this.actsFirstProbability,
        selfHealingFractionRange: BattleFractionRange? = this.selfHealingFractionRange,
        selfRecoilFractionRange: BattleFractionRange? = this.selfRecoilFractionRange,
        statusEffectProbability: Double? = this.statusEffectProbability,
        switchEntryHpLossFraction: Double? = this.switchEntryHpLossFraction,
        calculationCoverage: BattleCalculationCoverage = this.calculationCoverage,
        unknowns: Set<BattleCalculationUnknown> = this.unknowns,
        basis: Set<BattleCalculationBasis> = this.basis,
    ) = BattleCandidateFactsView(
        baseAccuracyProbability,
        typeChartMultiplier,
        baseSameTypeAttackBonus,
        standardDamageModel,
        standardDamageFractionRange,
        standardDamageRollKoProbabilityRange,
        standardKnockoutAssessment,
        actsFirstProbability,
        selfHealingFractionRange,
        selfRecoilFractionRange,
        statusEffectProbability,
        switchEntryHpLossFraction,
        calculationCoverage,
        unknowns,
        basis,
    )

    override fun equals(other: Any?): Boolean = other is BattleCandidateFactsView &&
        baseAccuracyProbability == other.baseAccuracyProbability &&
        typeChartMultiplier == other.typeChartMultiplier &&
        baseSameTypeAttackBonus == other.baseSameTypeAttackBonus &&
        standardDamageModel == other.standardDamageModel &&
        standardDamageFractionRange == other.standardDamageFractionRange &&
        standardDamageRollKoProbabilityRange == other.standardDamageRollKoProbabilityRange &&
        standardKnockoutAssessment == other.standardKnockoutAssessment &&
        actsFirstProbability == other.actsFirstProbability &&
        selfHealingFractionRange == other.selfHealingFractionRange &&
        selfRecoilFractionRange == other.selfRecoilFractionRange &&
        statusEffectProbability == other.statusEffectProbability &&
        switchEntryHpLossFraction == other.switchEntryHpLossFraction &&
        calculationCoverage == other.calculationCoverage &&
        unknowns == other.unknowns && basis == other.basis

    override fun hashCode(): Int = listOf(
        baseAccuracyProbability,
        typeChartMultiplier,
        baseSameTypeAttackBonus,
        standardDamageModel,
        standardDamageFractionRange,
        standardDamageRollKoProbabilityRange,
        standardKnockoutAssessment,
        actsFirstProbability,
        selfHealingFractionRange,
        selfRecoilFractionRange,
        statusEffectProbability,
        switchEntryHpLossFraction,
        calculationCoverage,
        unknowns,
        basis,
    ).hashCode()

    private companion object {
        fun requireProbability(value: Double?, label: String) {
            require(value == null || value.isFinite() && value in 0.0..1.0) { "$label must be between 0 and 1" }
        }
    }
}

enum class BattlePlanIntent {
    APPLY_PRESSURE,
    CREATE_SAFE_ENTRY,
    PRESERVE_CORE,
    ESTABLISH_FIELD,
    DENY_SETUP,
    CLOSE_GAME,
}

enum class BattlePlanAbortCondition {
    TARGET_ROLE_UNAVAILABLE,
    ACTIVE_BELOW_CRITICAL_HP,
    OPPONENT_BOARD_CHANGED,
    WIN_PATH_CHANGED,
}

class BattlePlanView(
    val intent: BattlePlanIntent,
    val targetRole: BattleTeamRole? = null,
    val expiresAtTurn: Int,
    abortIf: Set<BattlePlanAbortCondition> = emptySet(),
) {
    val abortIf: Set<BattlePlanAbortCondition> = Collections.unmodifiableSet(LinkedHashSet(abortIf))

    init {
        require(expiresAtTurn >= 0)
    }
}

enum class BattlePlanUpdateOperation { KEEP, REPLACE, CLEAR }

enum class BattlePlanOwner { PRIMARY_BRAIN, LOCAL_BRAIN }

class BattlePlanUpdate(
    val operation: BattlePlanUpdateOperation,
    val plan: BattlePlanView? = null,
) {
    init {
        require((operation == BattlePlanUpdateOperation.REPLACE) == (plan != null)) {
            "Only a replacement plan may carry plan data"
        }
    }

    companion object {
        @JvmStatic fun keep() = BattlePlanUpdate(BattlePlanUpdateOperation.KEEP)
        @JvmStatic fun clear() = BattlePlanUpdate(BattlePlanUpdateOperation.CLEAR)
        @JvmStatic fun replace(plan: BattlePlanView) = BattlePlanUpdate(BattlePlanUpdateOperation.REPLACE, plan)
    }
}

enum class BattlePredictedResponse { MOVE, SWITCH, OTHER, UNKNOWN }

data class BattlePrediction(
    val response: BattlePredictedResponse,
    val confidence: Double,
    val actorSlot: Int?,
) {
    constructor(response: BattlePredictedResponse, confidence: Double) : this(response, confidence, null)

    init {
        require(actorSlot == null || actorSlot >= 0)
        require(response != BattlePredictedResponse.UNKNOWN || confidence == 0.0 && actorSlot == null)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

enum class BattleDecisionReason {
    PRESERVE_WIN_CONDITION,
    SAFE_AGAINST_SECOND_RESPONSE,
    EXPECTED_SWITCH,
    EXPECTED_MOVE,
    PLAN_CONTINUATION,
    MINIMUM_VARIANCE,
    MATCHUP_REVERSAL,
}

enum class BattleMindGameIntent { NONE, PREDICT_SWITCH, CONDITION_THEN_BREAK, BAIT, INFORMATION_DENIAL }

class BattleDecisionAdvice(
    val prediction: BattlePrediction? = null,
    val planUpdate: BattlePlanUpdate = BattlePlanUpdate.keep(),
    reasonCodes: Set<BattleDecisionReason> = emptySet(),
    val mindGameIntent: BattleMindGameIntent = BattleMindGameIntent.NONE,
) {
    val reasonCodes: Set<BattleDecisionReason> = Collections.unmodifiableSet(LinkedHashSet(reasonCodes))
}

enum class BattleSituation { GENERAL, UNDER_KO_THREAT, AFTER_SETUP, LOW_HP, FASTER, MECHANIC_AVAILABLE, DOUBLE_FOCUS_TARGET }

data class BattleTendencyView(
    val situation: BattleSituation,
    val response: BattlePredictedResponse,
    val samples: Int,
    val recentWeight: Double,
    val estimatedRate: Double,
) {
    init {
        require(samples >= 0)
        require(recentWeight.isFinite() && recentWeight >= 0.0)
        require(estimatedRate.isFinite() && estimatedRate in 0.0..1.0)
    }
}

data class BattlePredictionCalibrationView @JvmOverloads constructor(
    val samples: Int,
    val hits: Int,
    val consecutiveMisses: Int,
    val topResponseBrierScore: Double? = null,
    val alwaysMoveBrierScore: Double? = null,
) {
    init {
        require(samples >= 0 && hits in 0..samples && consecutiveMisses in 0..samples)
        require(topResponseBrierScore == null ||
            topResponseBrierScore.isFinite() && topResponseBrierScore in 0.0..1.0)
        require(alwaysMoveBrierScore == null ||
            alwaysMoveBrierScore.isFinite() && alwaysMoveBrierScore in 0.0..1.0)
        require(samples > 0 || topResponseBrierScore == null && alwaysMoveBrierScore == null) {
            "Prediction scores require at least one sample"
        }
    }

    val hitRate: Double? get() = if (samples == 0) null else hits.toDouble() / samples
    val brierSkillScoreAgainstAlwaysMove: Double?
        get() = alwaysMoveBrierScore?.takeIf { it > 0.0 }?.let { baseline ->
            topResponseBrierScore?.let { score -> 1.0 - score / baseline }
        }
}

class BattleTacticalMemoryView(
    val activePlan: BattlePlanView? = null,
    val activePlanOwner: BattlePlanOwner? = null,
    tendencies: List<BattleTendencyView> = emptyList(),
    val predictionCalibration: BattlePredictionCalibrationView = BattlePredictionCalibrationView(0, 0, 0),
    val turnsSinceLastSwitch: Int? = null,
    val switchesThisBattle: Int = 0,
    val switchPressure: Double = 0.0,
    val lastMoveId: String? = null,
    val sameMoveRepeatCount: Int = 0,
    val patternExposureCount: Int = 0,
    val patternResponseShiftEvidence: Double = 0.0,
    val opponentResponseVolatility: Double = 0.0,
    val nonProgressControlStreak: Int = 0,
) {
    val tendencies: List<BattleTendencyView> = Collections.unmodifiableList(ArrayList(tendencies))

    init {
        require(turnsSinceLastSwitch == null || turnsSinceLastSwitch >= 0)
        require(switchesThisBattle >= 0)
        require(switchPressure.isFinite() && switchPressure >= 0.0)
        require(lastMoveId == null || lastMoveId.isNotBlank())
        require(sameMoveRepeatCount >= 0)
        require((lastMoveId == null) == (sameMoveRepeatCount == 0))
        require(patternExposureCount in 0..sameMoveRepeatCount)
        require(activePlanOwner == null || activePlan != null)
        require(patternResponseShiftEvidence.isFinite() && patternResponseShiftEvidence in 0.0..1.0)
        require(opponentResponseVolatility.isFinite() && opponentResponseVolatility in 0.0..1.0)
        require(nonProgressControlStreak >= 0)
    }

    companion object {
        @JvmStatic fun empty() = BattleTacticalMemoryView()
    }
}
