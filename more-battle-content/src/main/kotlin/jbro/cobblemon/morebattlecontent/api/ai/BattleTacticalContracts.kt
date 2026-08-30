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
    /**
     * How many competing readings of one opposing Pokemon a brain may carry.
     *
     * Router-side only, and deliberately so. It is sent in the prompt digest and the doctrine holds
     * the model to "inference within the supplied hypothesis budget", which is a real constraint on
     * a brain that reasons in prose. The local brain has nothing to bind it to: it does not enumerate
     * readings of an opponent at all, it represents everything unrevealed as a single reserve branch
     * and prices it. A sweep of 3, 10 and 16 against each other returned 29-29 three times over -
     * not a close result, the same battle replayed.
     *
     * Left in place rather than removed, because the Router contract is the reason it exists. Do not
     * sweep it as a local difficulty lever again; there is no local code path for it to travel.
     */
    val maximumHypothesesPerPokemon: Int,
    val lookaheadPlies: Int,
    val doubleCandidateLimitPerSlot: Int,
    /**
     * How far a tier trusts what the search found beyond the turn in front of it.
     *
     * Plies alone were measured to be a poor difficulty lever. A deeper search does reach a different
     * conclusion - it disagreed with the immediate-turn heuristic in about 60% of measured positions,
     * at every allowance - but the extra depth almost never survived into the final ranking, so the
     * tiers played the same battle. Depth decides how much foresight is *available*; this decides how
     * much of it is acted on, which is the part a player can feel.
     *
     * It never reaches the turn actually being played. Every tier keeps the full immediate evaluation,
     * including the value of status and utility moves, so a low setting produces a short-sighted
     * trainer rather than one that only attacks.
     */
    val foresightWeight: Double = 1.0,
    /**
     * How far behind the best action a tier will still consider an action at all.
     *
     * A multiplier on the regret band. The band is what decides whether the trainer has a choice to
     * make: actions further behind the best than the band allows are removed before any weighting
     * happens, so nothing downstream of it can reach them.
     *
     * This is the axis the measurements pointed at, by elimination. Depth two against one is 55.1%
     * +-3.7 and every other lever came back flat - the full foresight span 51.4% +-3.8, sharpness
     * 50.3% +-3.8. Sharpness is not flat because it does nothing: it swings how often the trainer
     * takes a non-best action from 44.1% of contested turns to 17.6%. It is flat because taking the
     * second action inside the band costs nothing. The band admits actions that are genuinely
     * interchangeable, which means it is well calibrated, and it also means no lever operating
     * *inside* it can ever be a difficulty setting.
     *
     * So a tier that is meant to be weaker has to be allowed outside it. Above one this widens what
     * the trainer will consider and it will sometimes play a real mistake; at one it is the shipped
     * band and the trainer never does. That is the intended difference. It was measured once before
     * as a *personality* lever and rejected with "that is not character, it is a worse player" -
     * correctly, because a persona should not be a handicap. A difficulty tier is exactly the place
     * where being a worse player is the point.
     */
    val decisionRegretBand: Double = 1.0,
    /**
     * How many close alternatives a tier will hold at once, as a multiplier on the shortlist width.
     *
     * The band decides how far behind the best an action may be; this decides how many of them
     * survive to be drawn from at all. They are separate limits and the second one binds first: the
     * shortlist takes 40% of the legal actions with a floor of two, so an ordinary singles position
     * with four or five candidates is cut to exactly two before the band is ever consulted.
     *
     * Measured, that cap is what stops the shortlist in 61.7% of positions at an eightfold band, and
     * it is why the bottom of the ladder is flat - Introductory at band 8.0 and Standard at 4.0 both
     * come down to "consider the top two", so they measured 51.0% +-5.1 against each other while the
     * rung where the cap binds least, Boss against Advanced, separated at 56.8%.
     *
     * Above one the tier can reach a third and fourth alternative; at one it is the shipped width.
     * The band still bounds how bad any of them may be, so widening the count admits more genuinely
     * close actions rather than worse ones.
     */
    val decisionShortlistWidth: Double = 1.0,
) {
    init {
        require(RESOURCE_ID.matches(id)) { "Difficulty profile id must be a lowercase namespaced id" }
        require(maximumHypothesesPerPokemon > 0)
        require(lookaheadPlies >= 0)
        require(doubleCandidateLimitPerSlot > 0)
        require(foresightWeight.isFinite() && foresightWeight in 0.0..1.0) {
            "Foresight weight must be between 0 and 1"
        }
        require(decisionRegretBand.isFinite() && decisionRegretBand > 0.0) {
            "Decision regret band must be a positive multiplier on the regret band"
        }
        require(decisionShortlistWidth.isFinite() && decisionShortlistWidth > 0.0) {
            "Decision shortlist width must be a positive multiplier on the shortlist fraction"
        }
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
        // At one ply there is no foresight to scale, so this tier's short-sightedness comes from the
        // depth itself. The weight is still stated rather than left at the default: it is what this
        // tier would trust if it ever searched further, and one ply per tower stage is a designed
        // contract that this change has no business rewriting.
        foresightWeight = 0.25,
        // Widest band on the ladder. This tier is the one a player meets first and the one that has
        // to be beatable, and it is the only place a genuine mistake is wanted.
        decisionRegretBand = 8.0,
        // The strongest handicap on the ladder, and the effect saturates just past this: narrow
        // against twofold is 64.2% +-3.8 and against threefold 63.3%, because the count limit is
        // fully released by then and there is nothing further to open.
        decisionShortlistWidth = 2.0,
    )

    @JvmField
    val STANDARD = BattleDifficultyProfile(
        id = "cobblemon_more_battle_content:standard",
        tier = BattleTrainerTier.STANDARD,
        maximumHypothesesPerPokemon = 6,
        lookaheadPlies = 2,
        doubleCandidateLimitPerSlot = 5,
        // Same depth as Introductory, so the difference between the two tiers is purely how much of
        // that foresight is acted on.
        foresightWeight = 0.60,
        decisionRegretBand = 4.0,
        decisionShortlistWidth = 1.6,
    )

    @JvmField
    val ADVANCED = BattleDifficultyProfile(
        id = "cobblemon_more_battle_content:advanced",
        tier = BattleTrainerTier.ADVANCED,
        maximumHypothesesPerPokemon = 10,
        lookaheadPlies = 3,
        doubleCandidateLimitPerSlot = 8,
        foresightWeight = 0.85,
        decisionRegretBand = 2.0,
        decisionShortlistWidth = 1.25,
    )

    @JvmField
    val BOSS = BattleDifficultyProfile(
        id = "cobblemon_more_battle_content:boss",
        tier = BattleTrainerTier.BOSS,
        maximumHypothesesPerPokemon = 16,
        lookaheadPlies = 4,
        doubleCandidateLimitPerSlot = 12,
        // Acts on everything it finds. Boss behaviour is unchanged by this lever.
        foresightWeight = 1.0,
        // The shipped band exactly. Boss is the tier that never plays a move outside what the
        // evaluation calls a genuinely close alternative, and that is now what makes it a Boss.
        decisionRegretBand = 1.0,
        decisionShortlistWidth = 1.0,
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
 * One target of a move that hits several slots at once.
 *
 * A spread move has no single defender, but every field on [BattleCandidateFactsView] that describes
 * damage describes exactly one. Publishing the extra slots separately keeps that contract intact:
 * the primary fields stay a statement about one Pokemon, and a Brain that wants the whole picture
 * reads every entry here instead of reinterpreting a field that was never a sum.
 *
 * The projection behind these values already carries the Gen 9 spread reduction, so they are what
 * each slot actually takes on this turn, not a single-target figure to be scaled afterwards.
 */
class BattleSpreadTargetFactsView(
    val side: BattleSide,
    val slot: Int,
    val typeChartMultiplier: Double? = null,
    val standardDamageFractionRange: BattleDamageFractionRange? = null,
    val standardDamageRollKoProbabilityRange: BattleFractionRange? = null,
    val standardKnockoutAssessment: BattleKnockoutAssessment? = null,
) {
    init {
        require(slot >= 0) { "Spread target slot must not be negative" }
        require(typeChartMultiplier == null || typeChartMultiplier.isFinite() && typeChartMultiplier >= 0.0)
    }

    override fun equals(other: Any?): Boolean = other is BattleSpreadTargetFactsView &&
        side == other.side && slot == other.slot &&
        typeChartMultiplier == other.typeChartMultiplier &&
        standardDamageFractionRange == other.standardDamageFractionRange &&
        standardDamageRollKoProbabilityRange == other.standardDamageRollKoProbabilityRange &&
        standardKnockoutAssessment == other.standardKnockoutAssessment

    override fun hashCode(): Int = listOf(
        side,
        slot,
        typeChartMultiplier,
        standardDamageFractionRange,
        standardDamageRollKoProbabilityRange,
        standardKnockoutAssessment,
    ).hashCode()
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
    spreadTargets: List<BattleSpreadTargetFactsView> = emptyList(),
) {
    val unknowns: Set<BattleCalculationUnknown> = Collections.unmodifiableSet(LinkedHashSet(unknowns))
    val basis: Set<BattleCalculationBasis> = Collections.unmodifiableSet(LinkedHashSet(basis))

    /**
     * Every slot this move hits, when it hits more than one.
     *
     * Empty for an ordinary single-target move, so nothing that reads only the primary fields has to
     * change. When it is populated the first entry is the same target the primary `standard*` fields
     * describe, which is what lets both readings stay correct at once.
     */
    val spreadTargets: List<BattleSpreadTargetFactsView> =
        Collections.unmodifiableList(ArrayList(spreadTargets))

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
        require(this.spreadTargets.size != 1) {
            "A single spread target is an ordinary single-target move; leave the list empty"
        }
        require(this.spreadTargets.distinctBy { it.side to it.slot }.size == this.spreadTargets.size) {
            "Spread targets must name distinct active slots"
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
        spreadTargets: List<BattleSpreadTargetFactsView> = this.spreadTargets,
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
        spreadTargets,
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
        unknowns == other.unknowns && basis == other.basis &&
        spreadTargets == other.spreadTargets

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
        spreadTargets,
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
