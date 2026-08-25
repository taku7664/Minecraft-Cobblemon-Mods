package jbro.cobblemon.morebattlecontent.betterai.policy

import java.util.SplittableRandom
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import kotlin.math.roundToInt

/** Battle-long psychology derived only from trainer identity, public state, and committed memory. */
internal data class LocalTrainerStyle(
    val riskOffset: Double,
    val mixupDisposition: Double,
) {
    init {
        require(riskOffset.isFinite() && riskOffset in -MAXIMUM_RISK_OFFSET..MAXIMUM_RISK_OFFSET)
        require(mixupDisposition.isFinite() && mixupDisposition in 0.0..1.0)
    }

    companion object {
        const val MAXIMUM_RISK_OFFSET = 0.08
        val BALANCED = LocalTrainerStyle(0.0, 0.5)
    }
}

internal object LocalTrainerStyleModel {
    fun derive(trainerPersonaId: String?, battleId: UUID): LocalTrainerStyle {
        val identitySeed = trainerPersonaId?.let(::stableStringSeed)
            ?: seed(battleId, IDENTITY_SALT)
        val identity = sample(identitySeed)
        val mood = sample(seed(battleId, MOOD_SALT))
        return LocalTrainerStyle(
            riskOffset = (
                identity.riskOffset * IDENTITY_SHARE + mood.riskOffset * MOOD_SHARE
                ).coerceIn(-LocalTrainerStyle.MAXIMUM_RISK_OFFSET, LocalTrainerStyle.MAXIMUM_RISK_OFFSET),
            mixupDisposition = (
                identity.mixupDisposition * IDENTITY_SHARE + mood.mixupDisposition * MOOD_SHARE
                ).coerceIn(0.0, 1.0),
        )
    }

    fun fromSeed(seed: Long): LocalTrainerStyle = sample(seed)

    private fun sample(seed: Long): LocalTrainerStyle {
        val random = SplittableRandom(seed)
        return LocalTrainerStyle(
            riskOffset = (random.nextDouble() - 0.5) * LocalTrainerStyle.MAXIMUM_RISK_OFFSET * 2.0,
            mixupDisposition = random.nextDouble(),
        )
    }

    private fun seed(id: UUID, salt: Long): Long = id.mostSignificantBits xor
        java.lang.Long.rotateLeft(id.leastSignificantBits, 23) xor salt

    private fun stableStringSeed(value: String): Long {
        var hash = FNV_OFFSET_BASIS
        value.forEach { character -> hash = (hash xor character.code.toLong()) * FNV_PRIME }
        return hash xor IDENTITY_SALT
    }

    private const val IDENTITY_SHARE = 0.80
    private const val MOOD_SHARE = 0.20
    private const val IDENTITY_SALT = 0x4f1bbcdc6a3d5e79L
    private const val MOOD_SALT = 0x21d45a9e37bc064fL
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
}

internal object LocalPositionRiskBudget {
    fun resolve(
        personalityRisk: Double,
        positionAdvantage: Double,
        styleRiskOffset: Double,
    ): Double {
        require(personalityRisk in 0.0..1.0)
        require(positionAdvantage.isFinite() && positionAdvantage in -1.0..1.0)
        require(styleRiskOffset.isFinite())
        return (personalityRisk + styleRiskOffset - positionAdvantage * POSITION_RISK_SWING).coerceIn(0.0, 1.0)
    }

    private const val POSITION_RISK_SWING = 0.25
}

internal data class LocalBattleMindState(
    val trainerStyle: LocalTrainerStyle,
    val positionAdvantage: Double,
    val riskBudget: Double,
)

internal object LocalBattleMind {
    fun assess(
        trainerPersonaId: String?,
        battleId: UUID,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
    ): LocalBattleMindState {
        val style = LocalTrainerStyleModel.derive(trainerPersonaId, battleId)
        val advantage = positionAdvantage(context.state)
        return LocalBattleMindState(
            trainerStyle = style,
            positionAdvantage = advantage,
            riskBudget = LocalPositionRiskBudget.resolve(
                profile.personality.riskTolerance,
                advantage,
                style.riskOffset,
            ),
        )
    }

    fun situations(state: BattleStateView, ownAction: BattleActionCandidate): Set<BattleSituation> = buildSet {
        add(BattleSituation.GENERAL)
        val opponents = state.pokemon.filter {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0
        }
        val opponentHp = opponents.minOfOrNull(BattlePokemonStateView::hpFraction)
        if (opponentHp != null && opponentHp <= LOW_HP_THRESHOLD) add(BattleSituation.LOW_HP)
        if (opponents.any { it.statStages.values.any { stage -> stage > 0 } }) add(BattleSituation.AFTER_SETUP)
        val atomic = ownAction.atomicActions()
        if (atomic.any(::credibleKnockoutThreat)) add(BattleSituation.UNDER_KO_THREAT)
        if (atomic.any { (it.facts?.actsFirstProbability ?: 0.0) >= FASTER_PROBABILITY_THRESHOLD }) {
            add(BattleSituation.FASTER)
        }
        if (atomic.any { it.mechanic != null }) add(BattleSituation.MECHANIC_AVAILABLE)
        val targets = atomic.flatMap(BattleActionCandidate::targets).filter { it.side == BattleSide.OPPONENT }
        if (targets.size >= 2 && targets.map { it.slot }.distinct().size == 1) {
            add(BattleSituation.DOUBLE_FOCUS_TARGET)
        }
    }

    fun advice(
        selected: LocalBattleActionRank,
        context: BattleDecisionContext,
        strategy: BattleStrategyBrief?,
        profile: BattleTrainerProfile,
    ): BattleDecisionAdvice {
        if (profile.difficulty.tier == BattleTrainerTier.INTRODUCTORY) {
            return BattleDecisionAdvice(planUpdate = BattlePlanUpdate.clear())
        }
        val memory = context.memory
        val selectedMoveId = selected.outcome.candidate.primaryMoveId()
        val mindGame = if (
            memory.patternResponseShiftEvidence >= MINIMUM_RESPONSE_SHIFT_EVIDENCE &&
            memory.patternExposureCount >= MINIMUM_PATTERN_EXPOSURE &&
            selectedMoveId != null && selectedMoveId != memory.lastMoveId
        ) {
            BattleMindGameIntent.CONDITION_THEN_BREAK
        } else {
            BattleMindGameIntent.NONE
        }
        val candidate = selected.outcome.candidate
        val currentPlan = memory.activePlan
        if (currentPlan != null && aligns(currentPlan.intent, candidate, memory)) {
            return BattleDecisionAdvice(
                planUpdate = BattlePlanUpdate.keep(),
                reasonCodes = setOf(BattleDecisionReason.PLAN_CONTINUATION),
                mindGameIntent = mindGame,
            )
        }

        val activeAllyHp = context.state.pokemon.filter {
            it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted
        }.minOfOrNull(BattlePokemonStateView::hpFraction) ?: 1.0
        val intent = when {
            selected.outcome.secureStandardKnockouts > 0 -> BattlePlanIntent.CLOSE_GAME
            candidate.containsSwitch() && activeAllyHp <= PRESERVE_HP_THRESHOLD -> BattlePlanIntent.PRESERVE_CORE
            candidate.containsSwitch() -> BattlePlanIntent.CREATE_SAFE_ENTRY
            candidate.hasFieldEstablishingEffect() -> BattlePlanIntent.ESTABLISH_FIELD
            else -> null
        }
        if (intent == null) {
            return BattleDecisionAdvice(
                planUpdate = BattlePlanUpdate.clear(),
                mindGameIntent = mindGame,
            )
        }
        val targetRole = candidate.switchPokemonId?.let { switchId ->
            val species = context.state.pokemon.firstOrNull { it.battlePokemonId == switchId }?.speciesId
            strategy?.members?.firstOrNull { canonicalId(it.speciesId) == canonicalId(species.orEmpty()) }
                ?.roles?.firstOrNull()
        }
        val planTurns = (MINIMUM_PLAN_TURNS + profile.personality.planPersistence * PLAN_PERSISTENCE_TURNS)
            .roundToInt()
        val abortIf = buildSet {
            add(BattlePlanAbortCondition.ACTIVE_BELOW_CRITICAL_HP)
            add(BattlePlanAbortCondition.OPPONENT_BOARD_CHANGED)
            add(BattlePlanAbortCondition.WIN_PATH_CHANGED)
            if (targetRole != null) add(BattlePlanAbortCondition.TARGET_ROLE_UNAVAILABLE)
        }
        return BattleDecisionAdvice(
            planUpdate = BattlePlanUpdate.replace(
                BattlePlanView(
                    intent = intent,
                    targetRole = targetRole,
                    expiresAtTurn = context.state.turn + planTurns,
                    abortIf = abortIf,
                ),
            ),
            reasonCodes = if (intent == BattlePlanIntent.PRESERVE_CORE) {
                setOf(BattleDecisionReason.PRESERVE_WIN_CONDITION)
            } else {
                emptySet()
            },
            mindGameIntent = mindGame,
        )
    }

    fun planAlignment(candidate: BattleActionCandidate, memory: BattleTacticalMemoryView): Double {
        val plan = memory.activePlan ?: return 1.0
        return if (aligns(plan.intent, candidate, memory)) PLAN_ALIGNMENT_MULTIPLIER else 1.0
    }

    private fun aligns(
        intent: BattlePlanIntent,
        candidate: BattleActionCandidate,
        memory: BattleTacticalMemoryView,
    ): Boolean {
        val repeatedNonDamage = candidate.isNonDamagingMove() && memory.sameMoveRepeatCount >= 1 &&
            candidate.primaryMoveId() == memory.lastMoveId
        if (repeatedNonDamage) return false
        return when (intent) {
            BattlePlanIntent.APPLY_PRESSURE, BattlePlanIntent.CLOSE_GAME -> candidate.hasDamagingMove()
            BattlePlanIntent.CREATE_SAFE_ENTRY, BattlePlanIntent.PRESERVE_CORE ->
                candidate.containsSwitch() && memory.turnsSinceLastSwitch?.let { it > 1 } != false
            BattlePlanIntent.ESTABLISH_FIELD -> candidate.hasFieldEstablishingEffect()
            BattlePlanIntent.DENY_SETUP -> candidate.hasDamagingMove() || candidate.hasOpponentStatDrop()
        }
    }

    private fun positionAdvantage(state: BattleStateView): Double {
        fun strength(side: BattleSide): Double {
            val roster = state.pokemon.filter { it.side == side }
            val remaining = state.remainingPokemonBySide[side] ?: roster.count { !it.fainted }
            val knownLiving = roster.filter { !it.fainted && it.hpFraction > 0.0 }
            val unseenLiving = (remaining - knownLiving.size).coerceAtLeast(0)
            val capacity = maxOf(roster.count(BattlePokemonStateView::fainted) + remaining, roster.size, 1)
            val health = (knownLiving.sumOf(BattlePokemonStateView::hpFraction) + unseenLiving) / capacity
            val alive = remaining.toDouble() / capacity
            return (health + alive) / 2.0
        }
        return (strength(BattleSide.ALLY) - strength(BattleSide.OPPONENT)).coerceIn(-1.0, 1.0)
    }

    private fun BattleActionCandidate.atomicActions(): List<BattleActionCandidate> =
        componentActions.ifEmpty { listOf(this) }.flatMap { action ->
            if (action.componentActions.isEmpty()) listOf(action) else action.atomicActions()
        }

    private fun BattleActionCandidate.primaryMoveId(): String? =
        moveId ?: componentActions.firstNotNullOfOrNull { it.primaryMoveId() }

    private fun BattleActionCandidate.containsSwitch(): Boolean =
        kind == BattleActionKind.SWITCH || componentActions.any { it.containsSwitch() }

    private fun BattleActionCandidate.hasDamagingMove(): Boolean = atomicActions().any {
        it.kind == BattleActionKind.USE_MOVE && it.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS &&
            (it.moveDetails?.power ?: 0.0) > 0.0
    }

    private fun BattleActionCandidate.isNonDamagingMove(): Boolean = atomicActions().any {
        it.kind == BattleActionKind.USE_MOVE && it.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS
    } && !hasDamagingMove()

    private fun BattleActionCandidate.hasFieldEstablishingEffect(): Boolean = atomicActions().any { action ->
        action.moveDetails?.effects?.effects?.any { effect ->
            effect.kind in FIELD_EFFECT_KINDS || effect.target in FIELD_EFFECT_TARGETS
        } == true
    }

    private fun BattleActionCandidate.hasOpponentStatDrop(): Boolean = atomicActions().any { action ->
        action.moveDetails?.effects?.effects?.any { effect ->
            effect.kind == BattleMoveEffectKind.STAT_STAGE &&
                effect.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                effect.statStages.values.any { it < 0 }
        } == true
    }

    private fun credibleKnockoutThreat(action: BattleActionCandidate): Boolean {
        val facts = action.facts ?: return false
        val rollFloor = facts.standardDamageRollKoProbabilityRange?.minimum
            ?: if (facts.standardKnockoutAssessment == BattleKnockoutAssessment.GUARANTEED) 1.0 else 0.0
        val accuracy = facts.baseAccuracyProbability
            ?: action.moveDetails?.accuracy?.div(100.0)
            ?: 1.0
        return rollFloor * accuracy >= MINIMUM_CREDIBLE_KO_PROBABILITY
    }

    private fun canonicalId(value: String): String = value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)

    private const val LOW_HP_THRESHOLD = 0.35
    private const val PRESERVE_HP_THRESHOLD = 0.35
    private const val FASTER_PROBABILITY_THRESHOLD = 0.75
    private const val MINIMUM_CREDIBLE_KO_PROBABILITY = 0.50
    private const val MINIMUM_RESPONSE_SHIFT_EVIDENCE = 0.35
    private const val MINIMUM_PATTERN_EXPOSURE = 2
    private const val MINIMUM_PLAN_TURNS = 2.0
    private const val PLAN_PERSISTENCE_TURNS = 3.0
    private const val PLAN_ALIGNMENT_MULTIPLIER = 1.20
    private val FIELD_EFFECT_KINDS = setOf(
        BattleMoveEffectKind.SIDE_CONDITION,
        BattleMoveEffectKind.FIELD_CONDITION,
        BattleMoveEffectKind.WEATHER,
        BattleMoveEffectKind.TERRAIN,
    )
    private val FIELD_EFFECT_TARGETS = setOf(
        BattleMoveEffectTarget.USER_SIDE,
        BattleMoveEffectTarget.TARGET_SIDE,
        BattleMoveEffectTarget.FIELD,
    )
}

internal fun BattleDecisionContext.forPlanOwner(owner: BattlePlanOwner): BattleDecisionContext {
    val planBelongsToOwner = memory.activePlanOwner == null || memory.activePlanOwner == owner
    if (planBelongsToOwner) return this
    return BattleDecisionContext(
        requestId = requestId,
        state = state,
        candidates = candidates,
        deadlineEpochMillis = deadlineEpochMillis,
        memory = BattleTacticalMemoryView(
            activePlan = null,
            activePlanOwner = null,
            tendencies = memory.tendencies,
            predictionCalibration = memory.predictionCalibration,
            turnsSinceLastSwitch = memory.turnsSinceLastSwitch,
            switchesThisBattle = memory.switchesThisBattle,
            switchPressure = memory.switchPressure,
            lastMoveId = memory.lastMoveId,
            sameMoveRepeatCount = memory.sameMoveRepeatCount,
            patternExposureCount = memory.patternExposureCount,
            patternResponseShiftEvidence = memory.patternResponseShiftEvidence,
            opponentResponseVolatility = memory.opponentResponseVolatility,
            nonProgressControlStreak = memory.nonProgressControlStreak,
        ),
        publicActionCatalog = publicActionCatalog,
    )
}
