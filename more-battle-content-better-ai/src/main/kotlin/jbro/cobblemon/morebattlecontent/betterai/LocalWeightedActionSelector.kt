package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleTacticalMemoryView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerPersonality
import java.util.SplittableRandom
import java.util.UUID
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
) {
    companion object {
        fun balanced(riskTolerance: Double) = LocalActionMixingContext(
            personality = BattleTrainerPersonality.balanced().copy(riskTolerance = riskTolerance),
            memory = BattleTacticalMemoryView.empty(),
            style = LocalTrainerStyle.BALANCED,
            riskBudget = riskTolerance,
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
        val viable = ranked.filter { rank ->
            canReceiveWeight(rank, rank === best, context.riskBudget)
        }.ifEmpty { listOf(best) }
        val countShortlist = viable.take(shortlistSize(viable.size))
        val allowedGap = MAXIMUM_REASONABLE_SCORE_GAP
        val bestScore = countShortlist.first().comparisonValue
        val shortlist = countShortlist.filter { rank -> bestScore - rank.comparisonValue <= allowedGap }
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
    ): LocalActionSelection = choose(ranked, seed, LocalActionMixingContext.balanced(riskTolerance))

    fun shortlistSize(candidateCount: Int): Int {
        require(candidateCount > 0)
        if (candidateCount == 1) return 1
        return ceil(candidateCount * SHORTLIST_FRACTION).toInt().coerceAtLeast(MINIMUM_MIXED_CHOICES)
            .coerceAtMost(candidateCount)
    }

    private fun canReceiveWeight(
        rank: LocalBattleActionRank,
        bestRanked: Boolean,
        riskBudget: Double,
    ): Boolean =
        !rank.outcome.publiclyInert &&
            !rank.outcome.entryFaints &&
            rank.executionProbability >= MINIMUM_EXPLORATORY_EXECUTION_PROBABILITY &&
            (bestRanked || rank.outcome.candidate.kind != BattleActionKind.SWITCH ||
                rank.worstResponseHpRetention >= exploratorySwitchHpRetention(riskBudget)) &&
            rank.outcome.candidate.kind != BattleActionKind.FORFEIT &&
            rank.outcome.candidate.kind != BattleActionKind.WAIT

    private fun exploratorySwitchHpRetention(riskBudget: Double): Double =
        MAXIMUM_EXPLORATORY_SWITCH_HP_RETENTION -
            (MAXIMUM_EXPLORATORY_SWITCH_HP_RETENTION - MINIMUM_EXPLORATORY_SWITCH_HP_RETENTION) *
            riskBudget.coerceIn(0.0, 1.0)

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
        const val SHORTLIST_FRACTION = 0.40
        const val MINIMUM_MIXED_CHOICES = 2
        const val MINIMUM_POSITIVE_WEIGHT = 1.0
        const val MAXIMUM_REASONABLE_SCORE_GAP = 199.0
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
