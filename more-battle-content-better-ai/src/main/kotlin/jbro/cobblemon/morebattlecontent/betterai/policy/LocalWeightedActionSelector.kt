package jbro.cobblemon.morebattlecontent.betterai.policy

import java.util.SplittableRandom
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveEffectTarget
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerPersonality
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow

internal data class LocalActionSelection(
    val rank: LocalBattleActionRank,
    val seed: Long,
    val shortlistSize: Int,
    val probability: Double,
)

internal fun interface LocalActionSelector {
    fun choose(
        ranked: List<LocalBattleActionRank>,
        seed: Long,
        context: LocalActionMixingContext,
    ): LocalActionSelection
}

internal data class LocalActionMixingContext(
    val personality: BattleTrainerPersonality,
    val memory: BattleTacticalMemoryView,
    val style: LocalTrainerStyle,
    val riskBudget: Double,
    val uncertainConditionalActionIds: Set<String> = emptySet(),
    val alreadyBoostedSetupActionIds: Set<String> = emptySet(),
    val overcommittedSetupActionIds: Set<String> = emptySet(),
    val tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
) {
    companion object {
        fun balanced(
            riskTolerance: Double,
            tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
        ) = LocalActionMixingContext(
            personality = BattleTrainerPersonality.balanced().copy(riskTolerance = riskTolerance),
            memory = BattleTacticalMemoryView.empty(),
            style = LocalTrainerStyle.BALANCED,
            riskBudget = riskTolerance,
            tuning = tuning,
        )
    }
}

/**
 * Converts the local Brain's own ranking into a reproducible mixed strategy.
 *
 * Mechanical no-op actions never receive exploratory probability while another useful action
 * exists. Personality changes only the shape of the distribution; it does not change legality or
 * public mechanics.
 */
internal class LocalWeightedActionSelector : LocalActionSelector {
    override fun choose(
        ranked: List<LocalBattleActionRank>,
        seed: Long,
        context: LocalActionMixingContext,
    ): LocalActionSelection {
        require(ranked.isNotEmpty()) { "Weighted action selection requires at least one ranked action" }

        val best = ranked.first()
        val credibleStayAlternativeExists = ranked.any { rank ->
            isCredibleDamagingStay(rank) &&
                best.comparisonValue - rank.comparisonValue <= context.tuning.maximumReasonableScoreGap
        }
        val viable = ranked.filter { rank ->
            canReceiveWeight(
                rank,
                rank === best,
                credibleStayAlternativeExists,
                context.memory,
                context.riskBudget,
                context.alreadyBoostedSetupActionIds,
                context.overcommittedSetupActionIds,
            )
        }.ifEmpty { listOf(emergencyFallback(ranked, context.overcommittedSetupActionIds)) }
        val countShortlist = viable.take(shortlistSize(viable.size, context.tuning))
        val bestScore = countShortlist.first().comparisonValue
        val allowedGap = minOf(
            context.tuning.maximumReasonableScoreGap,
            adaptiveRegretGap(context.riskBudget, bestScore, context.tuning),
        )
        val shortlist = countShortlist.filter { rank ->
            val conditionalScale = if (
                rank.outcome.candidate.actionId in context.uncertainConditionalActionIds
            ) {
                UNCERTAIN_CONDITION_REGRET_SCALE
            } else {
                1.0
            }
            bestScore - rank.comparisonValue <= allowedGap * conditionalScale
        }
        if (shortlist.size == 1) {
            return LocalActionSelection(shortlist.single(), seed, 1, 1.0)
        }

        val floor = shortlist.minOf(LocalBattleActionRank::comparisonValue)
        val style = context.style
        val riskTolerance = context.riskBudget
        val exponent = MAX_WEIGHT_EXPONENT -
            (MAX_WEIGHT_EXPONENT - MIN_WEIGHT_EXPONENT) * riskTolerance +
            if (context.memory.activePlan == null) 0.0 else context.personality.planPersistence * PLAN_SHARPNESS
        val weights = shortlist.map { rank ->
            val scoreWeight = (rank.comparisonValue - floor + MINIMUM_POSITIVE_WEIGHT)
                .coerceAtLeast(MINIMUM_POSITIVE_WEIGHT)
                .pow(exponent)
            scoreWeight * riskMultiplier(rank, riskTolerance) * patternMultiplier(rank, shortlist, context, style) *
                switchHysteresisMultiplier(rank, shortlist, context.memory) *
                nonProgressControlMultiplier(rank, context.memory) *
                LocalBattleMind.planAlignment(rank.outcome.candidate, context.memory)
        }
        val total = weights.sum()
        if (!total.isFinite() || total <= 0.0) {
            return LocalActionSelection(shortlist.first(), seed, shortlist.size, 1.0)
        }

        val draw = SplittableRandom(seed).nextDouble(total)
        var cumulative = 0.0
        shortlist.indices.forEach { index ->
            cumulative += weights[index]
            if (draw < cumulative || index == shortlist.lastIndex) {
                return LocalActionSelection(
                    rank = shortlist[index],
                    seed = seed,
                    shortlistSize = shortlist.size,
                    probability = weights[index] / total,
                )
            }
        }
        error("Weighted action selection did not resolve a candidate")
    }

    fun choose(
        ranked: List<LocalBattleActionRank>,
        seed: Long,
        riskTolerance: Double,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): LocalActionSelection = choose(ranked, seed, LocalActionMixingContext.balanced(riskTolerance, tuning))

    fun shortlistSize(
        candidateCount: Int,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Int {
        require(candidateCount > 0)
        if (candidateCount == 1) return 1
        return ceil(candidateCount * tuning.shortlistFraction).toInt().coerceAtLeast(MINIMUM_MIXED_CHOICES)
            .coerceAtMost(candidateCount)
    }

    private fun canReceiveWeight(
        rank: LocalBattleActionRank,
        bestRanked: Boolean,
        credibleStayAlternativeExists: Boolean,
        memory: BattleTacticalMemoryView,
        riskBudget: Double,
        alreadyBoostedSetupActionIds: Set<String>,
        overcommittedSetupActionIds: Set<String>,
    ): Boolean =
        !rank.outcome.publiclyInert &&
            !rank.outcome.entryFaints &&
            rank.executionProbability >= MINIMUM_EXPLORATORY_EXECUTION_PROBABILITY &&
            switchIsSafeEnough(rank, bestRanked, credibleStayAlternativeExists, riskBudget) &&
            selfSetupHasFuture(
                rank,
                bestRanked,
                credibleStayAlternativeExists,
                memory,
                alreadyBoostedSetupActionIds,
                overcommittedSetupActionIds,
            ) &&
            rank.outcome.candidate.kind != BattleActionKind.FORFEIT &&
            rank.outcome.candidate.kind != BattleActionKind.WAIT

    private fun switchIsSafeEnough(
        rank: LocalBattleActionRank,
        bestRanked: Boolean,
        credibleStayAlternativeExists: Boolean,
        riskBudget: Double,
    ): Boolean {
        if (rank.outcome.candidate.kind != BattleActionKind.SWITCH) return true
        if (!bestRanked) return rank.worstResponseHpRetention >= exploratorySwitchHpRetention(riskBudget)
        if (!credibleStayAlternativeExists) return true
        return rank.worstResponseHpRetention >= MINIMUM_BEST_SWITCH_HP_RETENTION
    }

    private fun isCredibleDamagingStay(rank: LocalBattleActionRank): Boolean =
        rank.outcome.candidate.kind == BattleActionKind.USE_MOVE &&
            rank.outcome.executableDamageActions > 0 &&
            !rank.outcome.publiclyInert &&
            !rank.outcome.entryFaints &&
            rank.executionProbability >= MINIMUM_EXPLORATORY_EXECUTION_PROBABILITY

    private fun selfSetupHasFuture(
        rank: LocalBattleActionRank,
        bestRanked: Boolean,
        credibleDamagingStayExists: Boolean,
        memory: BattleTacticalMemoryView,
        alreadyBoostedSetupActionIds: Set<String>,
        overcommittedSetupActionIds: Set<String>,
    ): Boolean {
        if (!isSelfSetup(rank)) return true
        if (rank.outcome.candidate.actionId in overcommittedSetupActionIds) return false
        if (rank.worstResponseHpRetention <= 0.0) return false
        if (!credibleDamagingStayExists) return true
        if (!bestRanked && rank.outcome.candidate.actionId in alreadyBoostedSetupActionIds) return false
        val moveId = rank.outcome.candidate.moveId?.let(::canonical) ?: return true
        val repeatsSameSetup = memory.lastMoveId?.let(::canonical) == moveId && memory.sameMoveRepeatCount >= 2
        return !repeatsSameSetup
    }

    private fun emergencyFallback(
        ranked: List<LocalBattleActionRank>,
        overcommittedSetupActionIds: Set<String>,
    ): LocalBattleActionRank = ranked.firstOrNull { rank ->
        rank.outcome.candidate.actionId !in overcommittedSetupActionIds &&
            !rank.outcome.publiclyInert &&
            !rank.outcome.entryFaints &&
            rank.outcome.candidate.kind != BattleActionKind.FORFEIT &&
            rank.outcome.candidate.kind != BattleActionKind.WAIT
    } ?: ranked.first()

    private fun isSelfSetup(rank: LocalBattleActionRank): Boolean {
        val details = rank.outcome.candidate.moveDetails ?: return false
        if (details.power > 0.0) return false
        return details.effects?.effects.orEmpty().any { effect ->
            effect.kind == BattleMoveEffectKind.STAT_STAGE &&
                effect.target == BattleMoveEffectTarget.USER &&
                effect.statStages.values.any { it > 0 }
        }
    }

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)

    private fun exploratorySwitchHpRetention(riskBudget: Double): Double =
        MAXIMUM_EXPLORATORY_SWITCH_HP_RETENTION -
            (MAXIMUM_EXPLORATORY_SWITCH_HP_RETENTION - MINIMUM_EXPLORATORY_SWITCH_HP_RETENTION) *
            riskBudget.coerceIn(0.0, 1.0)

    /**
     * How much worse than the best action a candidate may be and still receive exploratory weight.
     *
     * Scaled to the magnitude of the decision. A flat `45..80` score gap is "up to 80% of a health
     * bar of regret" whether the turn is worth three health bars or a tenth of one, so on quiet turns
     * it swept in actions that were not close at all - a tempo-losing switch kept landing in the same
     * shortlist as a clean attack purely because the absolute difference happened to be under 45.
     *
     * Expressing it as a fraction of the best action's own magnitude keeps "close enough to be worth
     * mixing" meaning the same thing at every scale. The absolute floor keeps near-zero-value turns
     * mixing at all; the ceiling keeps a huge swing turn from mixing in genuinely bad actions.
     */
    private fun adaptiveRegretGap(
        riskBudget: Double,
        bestScore: Double,
        tuning: LocalDecisionTuning,
    ): Double {
        val risk = riskBudget.coerceIn(0.0, 1.0)
        if (tuning.relativeRegretGap <= 0.0) {
            return tuning.minimumRegretGapScore +
                (tuning.maximumRegretGapScore - tuning.minimumRegretGapScore) * risk
        }
        val fraction = tuning.relativeRegretGap +
            (tuning.relativeRegretGapAtHighRisk - tuning.relativeRegretGap) * risk
        return (kotlin.math.abs(bestScore) * fraction)
            .coerceIn(tuning.minimumRegretGapScore, tuning.maximumRegretGapScore)
    }

    private fun riskMultiplier(rank: LocalBattleActionRank, riskTolerance: Double): Double {
        val candidate = rank.outcome.candidate
        val atomic = candidate.componentActions.ifEmpty { listOf(candidate) }
        val risk = atomic.map { action ->
            val accuracyRisk = 1.0 - (
                action.facts?.baseAccuracyProbability ?: action.moveDetails?.accuracy?.div(100.0) ?: 1.0
            ).coerceIn(0.0, 1.0)
            val damageSpread = action.facts?.standardDamageFractionRange?.let { range ->
                (range.maximum - range.minimum).coerceIn(0.0, 1.0)
            } ?: 0.0
            val recoilRisk = action.facts?.selfRecoilFractionRange?.maximum ?: 0.0
            (accuracyRisk + damageSpread + recoilRisk).coerceIn(0.0, 1.0)
        }.average()
        return exp((riskTolerance - 0.5) * risk * RISK_TILT)
    }

    private fun patternMultiplier(
        rank: LocalBattleActionRank,
        shortlist: List<LocalBattleActionRank>,
        context: LocalActionMixingContext,
        style: LocalTrainerStyle,
    ): Double {
        val lastMove = context.memory.lastMoveId ?: return 1.0
        val repeats = context.memory.sameMoveRepeatCount
        if (repeats < MINIMUM_PATTERN_REPEAT) return 1.0
        val adaptationEvidence = context.memory.patternResponseShiftEvidence
        if (context.memory.patternExposureCount < MINIMUM_PATTERN_REPEAT ||
            adaptationEvidence < MINIMUM_ADAPTATION_EVIDENCE
        ) return 1.0
        val hasAlternative = shortlist.any { it.outcome.candidate.moveId != null && it.outcome.candidate.moveId != lastMove }
        if (!hasAlternative) return 1.0
        val pressure = (repeats - MINIMUM_PATTERN_REPEAT + 1).coerceAtMost(MAX_PATTERN_PRESSURE).toDouble()
        val breakDrive = context.personality.information * (0.65 + style.mixupDisposition * 0.70) * pressure *
            adaptationEvidence
        val persistDrive = context.personality.planPersistence * pressure
        return if (rank.outcome.candidate.moveId == lastMove) {
            exp((persistDrive - breakDrive) * PATTERN_TILT)
        } else {
            exp((breakDrive - persistDrive * 0.35) * PATTERN_TILT)
        }
    }

    private fun switchHysteresisMultiplier(
        rank: LocalBattleActionRank,
        shortlist: List<LocalBattleActionRank>,
        memory: BattleTacticalMemoryView,
    ): Double = if (
        rank !== shortlist.first() &&
        rank.outcome.candidate.kind == BattleActionKind.SWITCH &&
        memory.turnsSinceLastSwitch?.let { it <= 1 } == true
    ) {
        RECENT_SWITCH_EXPLORATION_MULTIPLIER
    } else {
        1.0
    }

    private fun nonProgressControlMultiplier(
        rank: LocalBattleActionRank,
        memory: BattleTacticalMemoryView,
    ): Double {
        if (memory.nonProgressControlStreak < MINIMUM_NON_PROGRESS_STREAK) return 1.0
        if (rank.outcome.executableDamageActions > 0 || rank.outcome.candidate.kind == BattleActionKind.SWITCH) return 1.0
        val pressure = (memory.nonProgressControlStreak - MINIMUM_NON_PROGRESS_STREAK + 1)
            .coerceAtMost(MAX_NON_PROGRESS_PRESSURE)
        return exp(-pressure * NON_PROGRESS_TILT)
    }

    private companion object {
        const val MINIMUM_MIXED_CHOICES = 2
        const val MINIMUM_POSITIVE_WEIGHT = 1.0
        const val UNCERTAIN_CONDITION_REGRET_SCALE = 0.50
        const val MIN_WEIGHT_EXPONENT = 0.5
        const val MAX_WEIGHT_EXPONENT = 1.6
        const val PLAN_SHARPNESS = 0.25
        const val RISK_TILT = 2.0
        const val PATTERN_TILT = 0.45
        const val MINIMUM_PATTERN_REPEAT = 2
        const val MAX_PATTERN_PRESSURE = 3
        const val MINIMUM_ADAPTATION_EVIDENCE = 0.35
        const val RECENT_SWITCH_EXPLORATION_MULTIPLIER = 0.25
        const val MINIMUM_NON_PROGRESS_STREAK = 2
        const val MAX_NON_PROGRESS_PRESSURE = 4
        const val NON_PROGRESS_TILT = 0.75
        const val MINIMUM_EXPLORATORY_EXECUTION_PROBABILITY = 0.25
        const val MINIMUM_BEST_SWITCH_HP_RETENTION = 0.50
        const val MINIMUM_EXPLORATORY_SWITCH_HP_RETENTION = 0.60
        const val MAXIMUM_EXPLORATORY_SWITCH_HP_RETENTION = 0.75
    }
}

internal object LocalHighestRankedActionSelector : LocalActionSelector {
    override fun choose(
        ranked: List<LocalBattleActionRank>,
        seed: Long,
        context: LocalActionMixingContext,
    ): LocalActionSelection = LocalActionSelection(ranked.first(), seed, 1, 1.0)
}

internal object LocalActionChoiceSeed {
    fun derive(
        battleId: UUID,
        turn: Int,
        ranked: List<LocalBattleActionRank>,
    ): Long {
        var hash = FNV_OFFSET_BASIS
        hash = mix(hash, battleId.mostSignificantBits)
        hash = mix(hash, battleId.leastSignificantBits)
        hash = mix(hash, turn.toLong())
        ranked.forEach { rank ->
            rank.outcome.candidate.actionId.forEach { character -> hash = mix(hash, character.code.toLong()) }
            hash = mix(hash, rank.comparisonValue.toBits())
        }
        return hash
    }

    private fun mix(current: Long, value: Long): Long = (current xor value) * FNV_PRIME

    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
}
