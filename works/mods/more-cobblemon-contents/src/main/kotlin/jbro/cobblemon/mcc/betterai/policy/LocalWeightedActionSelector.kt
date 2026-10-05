package jbro.cobblemon.mcc.betterai.policy

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import java.util.SplittableRandom
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleTacticalMemoryView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerPersonality
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import kotlin.math.ceil
import kotlin.math.exp

internal data class LocalActionSelection(
    val rank: LocalBattleActionRank,
    val seed: Long,
    val shortlistSize: Int,
    val probability: Double,
    /** Exact draw probabilities; empty only for selectors that do not expose their distribution. */
    val probabilitiesByActionId: Map<String, Double> = emptyMap(),
    /** Why each ranked action outside the draw received no weight; empty for selectors without a pool. */
    val exclusionsByActionId: Map<String, String> = emptyMap(),
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
    /**
     * Candidates the choosing rules ruled out, with the reason each is excluded under: `setup_gate`
     * ([jbro.cobblemon.mcc.betterai.matchup.LocalSetupGate]), `status_wasted`
     * ([jbro.cobblemon.mcc.betterai.matchup.LocalStatusMoveTriage]).
     */
    val ruleExclusions: Map<String, String> = emptyMap(),
    /** Switches taking a hit for a doomed partner on purpose: keeping the incoming Pokemon's HP is not their point. */
    val sacrificeSwitchIds: Set<String> = emptySet(),
    val tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    /** The deciding tier's `decisionRegretBand`, multiplied into the regret band. One is shipped. */
    val decisionRegretBand: Double = 1.0,
    /** The deciding tier's `decisionShortlistWidth`, multiplied into the shortlist fraction. */
    val decisionShortlistWidth: Double = 1.0,
    /**
     * The comparison values already came from complete native state transitions.
     *
     * When true, old root metadata such as projected switch HP or setup vetoes must not remove a
     * candidate a second time. Regret-band weighting and trainer personality still choose among the
     * native-ranked candidates.
     */
    val authoritativeSimulationScores: Boolean = false,
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
    private data class ChoicePool(
        val ranks: List<LocalBattleActionRank>,
        val bestScore: Double,
        val drawGap: Double,
        val exclusions: Map<String, String>,
    )

    /** Exact pre-weight pool used by choose; zero-weight/fallback handling may narrow it further. */
    fun shortlist(ranked: List<LocalBattleActionRank>, context: LocalActionMixingContext): List<LocalBattleActionRank> =
        preparePool(ranked, context).ranks

    private fun preparePool(ranked: List<LocalBattleActionRank>, context: LocalActionMixingContext): ChoicePool {
        require(ranked.isNotEmpty()) { "Weighted action selection requires at least one ranked action" }

        val selectionUniverse = ranked
        val best = selectionUniverse.first()
        val credibleStays = selectionUniverse.filter { rank ->
            isCredibleDamagingStay(rank) &&
                best.comparisonValue - rank.comparisonValue <= context.tuning.maximumReasonableScoreGap
        }
        val credibleStayAlternativeExists = credibleStays.isNotEmpty()
        // The HP the best credible stay keeps: a switch that keeps more is never vetoed for keeping too little.
        val stayRetention = credibleStays.takeIf { it.isNotEmpty() }?.let { stays ->
            StayRetention(stays.maxOf { it.worstResponseHpRetention }, stays.maxOf { it.worstConfirmedResponseHpRetention })
        }
        val exclusions = linkedMapOf<String, String>()
        val eligible = selectionUniverse.filter { rank ->
            val reason = if (dominatedKnockout(rank, selectionUniverse)) {
                "dominated_knockout"
            } else if (context.authoritativeSimulationScores) {
                when (rank.outcome.candidate.kind) {
                    BattleActionKind.FORFEIT -> "forfeit"
                    BattleActionKind.WAIT -> "wait"
                    else -> null
                }
            } else {
                weightExclusion(
                    rank,
                    rank === best,
                    credibleStayAlternativeExists,
                    stayRetention,
                    rank.outcome.candidate.actionId in context.sacrificeSwitchIds,
                    context.memory,
                    context.riskBudget,
                    context.alreadyBoostedSetupActionIds,
                    context.overcommittedSetupActionIds,
                    context.ruleExclusions,
                )
            }
            reason?.let { exclusions[rank.outcome.candidate.actionId] = it }
            reason == null
        }
        val viable = eligible.ifEmpty {
            listOf(emergencyFallback(selectionUniverse, context.overcommittedSetupActionIds + context.ruleExclusions.keys)).also { fallback ->
                exclusions.remove(fallback.single().outcome.candidate.actionId)
            }
        }
        val countShortlist = viable.take(
            shortlistSize(viable.size, context.tuning, context.decisionShortlistWidth),
        )
        viable.drop(countShortlist.size).forEach { exclusions[it.outcome.candidate.actionId] = "shortlist_count" }
        val bestScore = countShortlist.first().comparisonValue
        // The tier multiplier is applied after the tuning ceiling, not before it.
        //
        // The tier may widen which plausible mistakes are considered, but it must not flatten their
        // draw weights too. Keeping the unscaled gap separately prevents the old eightfold beginner
        // band from turning a 138-point deficit into almost the same probability as the best move.
        val drawGap = minOf(
            context.tuning.maximumReasonableScoreGap,
            adaptiveRegretGap(context.riskBudget, bestScore, context.tuning),
        )
        val allowedGap = drawGap * context.decisionRegretBand
        // The regret gap used to be a single cliff: anything further than `allowedGap` behind the
        // best was removed outright. That cliff was where trainer character went to die - the
        // shortlist collapsed to one entry in a third of positions, two thirds once a cautious
        // valuation made the favourite more dominant, and a list of one cannot express a personality
        // however it is weighted.
        //
        // It is now two things that were being conflated. Within `allowedGap` the trainer is choosing
        // between real alternatives, and weight decays smoothly rather than falling off an edge.
        // Past `ABSURD_REGRET_MULTIPLE` times that, the action is not a close call at all and stays
        // excluded outright - a player who watched an AI pick a move worth less than half of the
        // obvious one would call it broken, not characterful, and removing that boundary entirely did
        // exactly that in the regression suite.
        val absurdGap = allowedGap * ABSURD_REGRET_MULTIPLE
        val shortlist = countShortlist.filter { rank ->
            val reason = when {
                bestScore - rank.comparisonValue > absurdGap * conditionalScale(rank, context) -> "regret_gap"
                !isPlausibleRelativeToBest(bestScore, rank.comparisonValue, context.tuning) -> "score_ratio"
                else -> null
            }
            reason?.let { exclusions[rank.outcome.candidate.actionId] = it }
            reason == null
        }.ifEmpty {
            listOf(countShortlist.first()).also { exclusions.remove(it.single().outcome.candidate.actionId) }
        }
        return ChoicePool(shortlist, bestScore, drawGap, exclusions)
    }

    override fun choose(
        ranked: List<LocalBattleActionRank>,
        seed: Long,
        context: LocalActionMixingContext,
    ): LocalActionSelection {
        val pool = preparePool(ranked, context)
        val shortlist = pool.ranks
        val bestScore = pool.bestScore
        val drawGap = pool.drawGap
        if (shortlist.size == 1) {
            return LocalActionSelection(
                shortlist.single(), seed, 1, 1.0,
                mapOf(shortlist.single().outcome.candidate.actionId to 1.0),
                pool.exclusions,
            )
        }

        val style = context.style
        val riskTolerance = context.riskBudget
        // Sharpness of the draw, deliberately independent of the difficulty handicap.
        //
        // Difficulty already has a lever: it widens `allowedGap`, so a weaker tier treats a larger
        // band of actions as live alternatives. Letting that same multiplier flatten the decay as
        // well applies the handicap twice, and the second application means something different and
        // wrong - not "allowed to make a plausible mistake" but "nearly blind to its own scores".
        //
        // A plan in progress still sharpens: sticking to a line is what having a plan means.
        val sharpness = BASE_DRAW_SHARPNESS +
            if (context.memory.activePlan == null) 0.0 else context.personality.planPersistence * PLAN_SHARPNESS
        val weights = shortlist.map { rank ->
            // Weight decays with regret against the best action, on a scale set by how much regret
            // the baseline evaluator tolerates. Difficulty changes membership, not this scale.
            //
            // It used to be multiplied by `(score - floor)^exponent`, and that term was the real
            // reason the draw stayed effectively deterministic even after the shortlist was widened:
            // the lowest-scoring member of any shortlist sits exactly at `floor`, so its weight was
            // always the epsilon, whatever the exponent. The last candidate could never be chosen -
            // not because it was bad, but because it was last. Softening the cliff had barely moved
            // the favourite's 94% share until this went with it.
            val regret = (bestScore - rank.comparisonValue).coerceAtLeast(0.0)
            exp(-sharpness * regret / (drawGap * conditionalScale(rank, context))) *
                riskMultiplier(rank, riskTolerance) * patternMultiplier(rank, shortlist, context, style) *
                switchHysteresisMultiplier(rank, shortlist, context.memory, context.decisionShortlistWidth) *
                nonProgressControlMultiplier(rank, context.memory) *
                LocalBattleMind.planAlignment(rank.outcome.candidate, context.memory)
        }
        val total = weights.sum()
        if (!total.isFinite() || total <= 0.0) {
            return LocalActionSelection(
                shortlist.first(), seed, shortlist.size, 1.0,
                mapOf(shortlist.first().outcome.candidate.actionId to 1.0),
                pool.exclusions,
            )
        }
        val probabilities = shortlist.indices.associate { index ->
            shortlist[index].outcome.candidate.actionId to weights[index] / total
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
                    probabilitiesByActionId = probabilities,
                    exclusionsByActionId = pool.exclusions,
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
        width: Double = 1.0,
    ): Int {
        require(candidateCount > 0)
        require(width > 0.0)
        if (candidateCount == 1) return 1
        // The floor stays at two rather than scaling with the tier.
        //
        // It exists so that a position with two legal actions is still a choice, which is true at
        // every difficulty. Scaling it would mean a Boss position with two candidates could be cut to
        // one, and that is not a stronger trainer, it is a trainer with the draw switched off.
        return ceil(candidateCount * tuning.shortlistFraction * width).toInt()
            .coerceAtLeast(MINIMUM_MIXED_CHOICES)
            .coerceAtMost(candidateCount)
    }

    /** Null when the action may receive weight; otherwise a stable reason code for diagnostics. */
    private fun weightExclusion(
        rank: LocalBattleActionRank,
        bestRanked: Boolean,
        credibleStayAlternativeExists: Boolean,
        stayRetention: StayRetention?,
        sacrifice: Boolean,
        memory: BattleTacticalMemoryView,
        riskBudget: Double,
        alreadyBoostedSetupActionIds: Set<String>,
        overcommittedSetupActionIds: Set<String>,
        ruleExclusions: Map<String, String>,
    ): String? = when {
        rank.outcome.publiclyInert -> "publicly_inert"
        rank.outcome.entryFaints -> "entry_faints"
        // Only an action that uses a move can fail to go through. For a switch the "execution" is the
        // incoming Pokemon surviving the turn, which the switch rules below already price; applying the
        // gate too kept a Trick Room team from sending in Ursaluna as its replacement.
        //
        // The gate keeps an unlikely move out of the exploratory draw; it never takes the best-ranked one
        // away. The execution probability is the worst over the opponent's replies, and the ranking has
        // already priced those replies, so vetoing the best on it counted the same risk twice: a Boss Flutter
        // Mane dropped a 168-point Moonblast for a -88 Thunder Wave because one Sucker Punch would stop it,
        // and a Calyrex dropped a winning Glacial Lance for a losing switch.
        !bestRanked && rank.executionProbability < MINIMUM_EXPLORATORY_EXECUTION_PROBABILITY && usesMove(rank.outcome.candidate) ->
            "low_execution_probability"
        rank.outcome.candidate.kind == BattleActionKind.FORFEIT -> "forfeit"
        rank.outcome.candidate.kind == BattleActionKind.WAIT -> "wait"
        rank.outcome.candidate.actionId in ruleExclusions -> ruleExclusions.getValue(rank.outcome.candidate.actionId)
        else -> (if (sacrifice) null else switchExclusion(rank, bestRanked, credibleStayAlternativeExists, stayRetention, riskBudget, memory))
            ?: if (selfSetupHasFuture(
                    rank,
                    bestRanked,
                    credibleStayAlternativeExists,
                    memory,
                    alreadyBoostedSetupActionIds,
                    overcommittedSetupActionIds,
                )
            ) null else "setup_without_future"
    }

    private data class StayRetention(val worst: Double, val confirmed: Double)

    private fun switchExclusion(
        rank: LocalBattleActionRank,
        bestRanked: Boolean,
        credibleStayAlternativeExists: Boolean,
        stayRetention: StayRetention?,
        riskBudget: Double,
        memory: BattleTacticalMemoryView,
    ): String? {
        if (rank.outcome.candidate.kind != BattleActionKind.SWITCH) return null
        // Damping alone leaves a nonzero chance of arbitrarily long exploratory switch chains.
        // Pressure is accumulated tempo debt, not an exact consecutive-switch counter. Restrict
        // only another recent, lower-ranked exploration while a credible damaging stay exists;
        // the best escape and positions without a credible attack retain their existing safety rules.
        if (!bestRanked && credibleStayAlternativeExists &&
            memory.turnsSinceLastSwitch?.let { it <= 1 } == true &&
            memory.switchPressure >= REPEATED_SWITCH_PRESSURE
        ) return "repeated_switch_pressure"
        // The HP gates ask whether the switch-in keeps enough. Staying in that keeps less is no safer: a Roserade at
        // 41% kept clicking a quartered Giga Drain into Ferrothorn and fainted because both switches kept "only" 44%
        // and 56%.
        if (!bestRanked) {
            val required = exploratorySwitchHpRetention(riskBudget)
            if (stayRetention != null && rank.worstResponseHpRetention > stayRetention.worst) return null
            return if (rank.worstResponseHpRetention >= required) null
            else "exploratory_switch_hp_retention_below_${format(required)}"
        }
        if (!credibleStayAlternativeExists) return null
        if (stayRetention != null && rank.worstConfirmedResponseHpRetention > stayRetention.confirmed) return null
        // Overriding the ranking needs confirmed evidence. The full worst case counts expected move
        // slots at full strength and the score already priced them, so a speculative slot alone
        // must not veto the best action; live play showed a best switch far ahead of every stay
        // dropped in favour of much weaker actions.
        return if (rank.worstConfirmedResponseHpRetention >= MINIMUM_BEST_SWITCH_HP_RETENTION) null
        else "best_switch_confirmed_hp_retention_below_${format(MINIMUM_BEST_SWITCH_HP_RETENTION)}"
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    /**
     * True when another move does the same knockout more surely: same actor and single target, a
     * knockout on every damage roll for both, better accuracy, no later turn order, no more recoil,
     * no different mechanic, and a score at least as high.
     *
     * Risk appetite is a real trait - a trainer who is behind may gamble on an 80% move that could
     * turn the game. It is not a reason to miss a knockout a 100% move was certain to take: nothing
     * is gained by the gamble, so a player would call it a mistake, not character. This removes only
     * that strictly dominated case; any difference in effect keeps both moves in the draw.
     */
    private fun dominatedKnockout(rank: LocalBattleActionRank, universe: List<LocalBattleActionRank>): Boolean {
        val candidate = rank.outcome.candidate
        if (!isSingleTargetKnockout(rank)) return false
        val accuracy = accuracyOf(rank)
        return universe.any { other ->
            val alternative = other.outcome.candidate
            other !== rank &&
                isSingleTargetKnockout(other) &&
                alternative.actorSlot == candidate.actorSlot &&
                alternative.targets == candidate.targets &&
                alternative.mechanic?.mechanicId == candidate.mechanic?.mechanicId &&
                accuracyOf(other) > accuracy + DOMINANCE_EPSILON &&
                (alternative.facts?.actsFirstProbability ?: 0.0) + DOMINANCE_EPSILON >=
                (candidate.facts?.actsFirstProbability ?: 0.0) &&
                (alternative.facts?.selfRecoilFractionRange?.maximum ?: 0.0) <=
                (candidate.facts?.selfRecoilFractionRange?.maximum ?: 0.0) &&
                other.comparisonValue >= rank.comparisonValue
        }
    }

    private fun isSingleTargetKnockout(rank: LocalBattleActionRank): Boolean {
        val candidate = rank.outcome.candidate
        return candidate.kind == BattleActionKind.USE_MOVE && candidate.targets.size == 1 &&
            (candidate.facts?.standardDamageRollKoProbabilityRange?.minimum ?: 0.0) >= 1.0
    }

    private fun accuracyOf(rank: LocalBattleActionRank): Double =
        rank.outcome.effectiveAccuracyProbability
            ?: rank.outcome.candidate.facts?.baseAccuracyProbability
            ?: rank.outcome.candidate.moveDetails?.accuracy?.div(100.0)
            ?: 1.0

    private fun usesMove(candidate: BattleActionCandidate): Boolean =
        candidate.kind == BattleActionKind.USE_MOVE || candidate.componentActions.any(::usesMove)

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
        PublicIds.canonical(value)

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

    /**
     * Difficulty may admit a genuine mistake, but it may not turn an obviously broken action into a
     * personality choice. The selector's own contract calls a move worth less than half of the clear
     * answer absurd; enforce that statement as a score ratio rather than an absolute gap, because the
     * scale of one decision can span several health bars while another barely moves the board.
     *
     * Near zero the ratio is unstable and the normal absolute regret band remains authoritative.
     */
    private fun isPlausibleRelativeToBest(
        bestScore: Double,
        candidateScore: Double,
        tuning: LocalDecisionTuning,
    ): Boolean = bestScore < tuning.minimumRegretGapScore ||
        candidateScore >= bestScore / MAXIMUM_PLAUSIBLE_SCORE_MULTIPLE

    private fun riskMultiplier(rank: LocalBattleActionRank, riskTolerance: Double): Double {
        val atomic = rank.outcome.componentOutcomes.ifEmpty { listOf(rank.outcome) }
        val risk = atomic.map { outcome ->
            val action = outcome.candidate
            val accuracyRisk = 1.0 - (
                outcome.effectiveAccuracyProbability
                    ?: action.facts?.baseAccuracyProbability
                    ?: action.moveDetails?.accuracy?.div(100.0)
                    ?: 1.0
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

    /**
     * Damping on an exploratory switch made immediately after switching.
     *
     * Divided by the tier's shortlist width, because the constant alone holds down the wrong
     * quantity. What must stay bounded is the *total* probability that the draw switches again, and
     * that is the damped weight summed over every switch in the shortlist - so a tier holding twice
     * as many alternatives carries roughly twice the switch mass at the same multiplier. The virtual
     * league caught it the first time a tier was widened: a side switched voluntarily on three
     * consecutive turns against a ceiling of two, in a run where every other measure was comfortably
     * inside its bound.
     *
     * At width one this is the shipped constant exactly, so Boss and Advanced are untouched.
     */
    private fun switchHysteresisMultiplier(
        rank: LocalBattleActionRank,
        shortlist: List<LocalBattleActionRank>,
        memory: BattleTacticalMemoryView,
        shortlistWidth: Double,
    ): Double = if (
        rank !== shortlist.first() &&
        rank.outcome.candidate.kind == BattleActionKind.SWITCH &&
        memory.turnsSinceLastSwitch?.let { it <= 1 } == true
    ) {
        RECENT_SWITCH_EXPLORATION_MULTIPLIER / shortlistWidth.coerceAtLeast(1.0)
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
        const val REPEATED_SWITCH_PRESSURE = 2.0
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
         * The independent score-ratio guard below is the final boundary for tiers that deliberately
         * widen this band.
         */
        const val ABSURD_REGRET_MULTIPLE = 1.0
        const val MAXIMUM_PLAUSIBLE_SCORE_MULTIPLE = 2.0

        /**
         * Decay rate of weight against regret, in units of the baseline adaptive regret band.
         *
         * Three means an action exactly at the edge of the band keeps `exp(-3)` of the best action's
         * weight. With one, a neutral 30-point deficit behind a 100-point leader was still selected
         * in 38% of seeded draws. Keep the runner-up possible without treating that gap as a near tie.
         */
        const val BASE_DRAW_SHARPNESS = 3.0
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
        const val DOMINANCE_EPSILON = 1e-9
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
        perspectivePokemonIds: Collection<UUID> = emptyList(),
    ): Long {
        var hash = FNV_OFFSET_BASIS
        hash = mix(hash, battleId.mostSignificantBits)
        hash = mix(hash, battleId.leastSignificantBits)
        hash = mix(hash, turn.toLong())
        if (perspectivePokemonIds.isNotEmpty()) {
            val perspective = perspectivePokemonIds.sortedBy(UUID::toString)
            hash = mix(hash, perspective.size.toLong())
            perspective.forEach { pokemonId ->
                hash = mix(hash, pokemonId.mostSignificantBits)
                hash = mix(hash, pokemonId.leastSignificantBits)
            }
        }
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
