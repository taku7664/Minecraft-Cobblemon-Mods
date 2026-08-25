package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleStrategyBrief
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator

/**
 * Per-candidate score decomposition for one decision.
 *
 * The local path collapses a dozen contributions into a single `comparisonValue`, so when a choice
 * looks wrong there is no way to tell which term produced it. This pulls the terms back apart at the
 * boundaries that actually exist in the pipeline, and reports them for every legal action rather than
 * just the winner.
 */
internal data class LocalCandidateBreakdown(
    val actionId: String,
    val kind: BattleActionKind,
    val decisionTier: Int,
    /** Everything the flat scorer produced, knockout material included. */
    val tacticalUtility: Double,
    /** The knockout material inside [tacticalUtility]. */
    val knockoutUtility: Double,
    /** Signed contribution of the recursive search, after the coverage discount. */
    val lookaheadUtility: Double,
    val comparisonValue: Double,
    val expectedDamageFraction: Double,
    val survivalTurnImprovement: Double?,
    val executionProbability: Double,
    val worstResponseHpRetention: Double,
) {
    /** Score with the search contribution removed, i.e. what the flat heuristic alone decided. */
    val heuristicOnlyValue: Double get() = comparisonValue - lookaheadUtility

    fun format(): String = buildString {
        append(actionId.padEnd(22))
        append(" tier=").append(decisionTier)
        append(" total=").append(fixed(comparisonValue))
        append(" heur=").append(fixed(heuristicOnlyValue))
        append(" tactical=").append(fixed(tacticalUtility))
        append(" ko=").append(fixed(knockoutUtility))
        append(" look=").append(fixed(lookaheadUtility))
        append(" dmg=").append(fixed(expectedDamageFraction * 100))
        append(" survΔ=").append(survivalTurnImprovement?.let(::fixed) ?: "-")
        append(" exec=").append(fixed(executionProbability))
        append(" keepHp=").append(fixed(worstResponseHpRetention))
    }

    private fun fixed(value: Double): String = String.format("%.1f", value)
}

internal data class LocalDecisionBreakdown(
    val tuningId: String,
    val lookaheadCoverage: Double,
    val lookaheadDepth: Int,
    val publicResponseIncomplete: Boolean,
    val candidates: List<LocalCandidateBreakdown>,
) {
    val chosenByRanking: LocalCandidateBreakdown? get() = candidates.firstOrNull()

    fun format(label: String): String = buildString {
        appendLine(
            "[$label] tuning=$tuningId coverage=${String.format("%.3f", lookaheadCoverage)} " +
                "depth=$lookaheadDepth incomplete=$publicResponseIncomplete",
        )
        candidates.forEach { appendLine("    " + it.format()) }
    }
}

internal object LocalDecisionInstrumentation {
    /**
     * Re-runs the production ranking pipeline and reports every stage instead of only the winner.
     *
     * Deliberately reuses [LocalBattleActionPolicy] and [LocalRecursiveLookaheadEvaluator] rather than
     * reimplementing the maths, so a measurement can never drift away from what ships.
     */
    fun inspect(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile = BattleTrainerProfile.balanced(),
        strategy: BattleStrategyBrief? = null,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): LocalDecisionBreakdown {
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        val base = LocalBattleActionPolicy.rank(calculated, strategy, profile, tuning)
        val lookahead = LocalRecursiveLookaheadEvaluator.evaluate(base, calculated, profile, tuning)
        return LocalDecisionBreakdown(
            tuningId = tuning.id,
            lookaheadCoverage = lookahead.publicResponseCoverage,
            lookaheadDepth = lookahead.depthCompleted,
            publicResponseIncomplete = lookahead.publicResponseIncomplete,
            candidates = lookahead.ranked.map { rank ->
                LocalCandidateBreakdown(
                    actionId = rank.outcome.candidate.actionId,
                    kind = rank.outcome.candidate.kind,
                    decisionTier = rank.decisionTier,
                    tacticalUtility = rank.outcome.tacticalUtility,
                    knockoutUtility = rank.outcome.knockoutUtility,
                    lookaheadUtility = rank.lookaheadUtility,
                    comparisonValue = rank.comparisonValue,
                    expectedDamageFraction = rank.outcome.expectedDamageFraction,
                    survivalTurnImprovement = rank.outcome.survivalPositionImprovement,
                    executionProbability = rank.executionProbability,
                    worstResponseHpRetention = rank.worstResponseHpRetention,
                )
            },
        )
    }

    /** Same decision under both tunings, for side-by-side reading. */
    fun compare(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile = BattleTrainerProfile.balanced(),
        strategy: BattleStrategyBrief? = null,
    ): Pair<LocalDecisionBreakdown, LocalDecisionBreakdown> =
        inspect(context, profile, strategy, LocalDecisionTuning.LEGACY) to
            inspect(context, profile, strategy, LocalDecisionTuning.CURRENT)
}
