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
        // The regret gap used to be a single cliff: anything further than `allowedGap` behind the
        // best was removed outright. That cliff was where trainer character went to die - the
        // shortlist collapsed to one entry in a third of positions, two thirds once a cautious
        // valuation made the favourite more dominant, and a list of one cannot express a personality
        // however it is weighted.
        //
        // It is now two things that were being conflated. Within `allowedGap` the trainer is choosing
        // between real alternatives, and weight decays smoothly rather than falling off an edge.
        // Past `ABSURD_REGRET_MULTIPLE` times that, the action is not a close call at all and stays
        // excluded outright - a player who watched an AI pick a move three times worse than the
        // obvious one would call it broken, not characterful, and removing that boundary entirely did
        // exactly that in the regression suite.
        val absurdGap = allowedGap * ABSURD_REGRET_MULTIPLE
        val shortlist = countShortlist.filter { rank ->
            bestScore - rank.comparisonValue <= absurdGap * conditionalScale(rank, context)
        }.ifEmpty { listOf(countShortlist.first()) }
        if (shortlist.size == 1) {
            return LocalActionSelection(shortlist.single(), seed, 1, 1.0)
        }

        val style = context.style
        val riskTolerance = context.riskBudget
        // Sharpness of the draw, deliberately independent of risk appetite.
        //
        // Risk already has a lever: it widens `allowedGap`, so a bold trainer treats a larger band of
        // actions as live alternatives. Letting it flatten the decay as well applied it twice, and
        // the second application meant something different and wrong - not "willing to take a close
        // alternative" but "willing to take a bad move". A dominated action 95 points behind kept
        // over half the weight of the best one.
        //
        // A plan in progress still sharpens: sticking to a line is what having a plan means.
        val sharpness = BASE_DRAW_SHARPNESS +
            if (context.memory.activePlan == null) 0.0 else context.personality.planPersistence * PLAN_SHARPNESS
        val weights = shortlist.map { rank ->
            // Weight decays with regret against the best action, on a scale set by how much regret
            // this trainer tolerates. Nothing else shapes it.
            //
            // It used to be multiplied by `(score - floor)^exponent`, and that term was the real
            // reason the draw stayed effectively deterministic even after the shortlist was widened:
            // the lowest-scoring member of any shortlist sits exactly at `floor`, so its weight was
            // always the epsilon, whatever the exponent. The last candidate could never be chosen -
            // not because it was bad, but because it was last. Softening the cliff had barely moved
            // the favourite's 94% share until this went with it.
            val regret = (bestScore - rank.comparisonValue).coerceAtLeast(0.0)
            exp(-sharpness * regret / (allowedGap * conditionalScale(rank, context))) *
                riskMultiplier(rank, riskTolerance) * patternMultiplier(rank, shortlist, context, style) *
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

    /**
     * Scale applied to an action whose success depends on a condition that is not resolved yet.
     *
     * The scale is below one, so uncertainty *narrows* the band: an action that may simply fail is
     * held to a stricter standard before it is worth mixing in, which is what the old cliff meant by
     * it too.
     */
    private fun conditionalScale(rank: LocalBattleActionRank, context: LocalActionMixingContext): Double =
        if (rank.outcome.candidate.actionId in context.uncertainConditionalActionIds) {
            UNCERTAIN_CONDITION_REGRET_SCALE
        } else {
            1.0
        }

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
        const val UNCERTAIN_CONDITION_REGRET_SCALE = 0.50
        /**
         * How far past the regret band an action stops being a choice and becomes a mistake.
         *
         * One, meaning the band itself is the boundary - the same exclusion the old cliff made. It
         * was briefly two, on the theory that a wider band would give trainer character more room,
         * and it did: persona divergence went from 7.5% to 32.5%. But the room it opened is populated
         * by actions between 45% and 160% worse than the best available, and the regression suite
         * caught the AI playing one of them. That is not character, it is a worse player, and the
         * measurement that looked like success could not tell the two apart.
         *
         * What the band admits is unchanged. What changed is the shape inside it.
         */
        const val ABSURD_REGRET_MULTIPLE = 1.0

        /**
         * Decay rate of weight against regret, in units of the trainer's own regret band.
         *
         * One means an action exactly at the edge of the band keeps `1/e` of the best action's
         * weight. Replaced the pair of weight exponents that shaped the old power-law term; those
         * were calibrated for a different formula and reusing them here made the risky end far too
         * flat.
         */
        const val BASE_DRAW_SHARPNESS = 1.0
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
