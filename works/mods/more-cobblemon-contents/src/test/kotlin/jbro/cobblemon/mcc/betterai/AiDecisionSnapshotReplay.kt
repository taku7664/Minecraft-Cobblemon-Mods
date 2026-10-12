package jbro.cobblemon.mcc.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleDecision
import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
import jbro.cobblemon.mcc.betterai.brain.LocalDecisionStages
import jbro.cobblemon.mcc.betterai.brain.LocalTacticalBrain
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelection
import jbro.cobblemon.mcc.betterai.policy.LocalActionSelector
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.policy.LocalWeightedActionSelector
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionEvaluation
import jbro.cobblemon.mcc.betterai.search.NativeInitialProductDecisionStatus

/**
 * Replays a decision saved by an AI test battle (`logs/betterai-decisions/<battle>/turn-NNN-*.json`).
 *
 * Legacy-only replay is exact for turns that did not continue a native session: the pipeline is
 * deterministic, and the request deadline keeps the remaining time it had at capture. Pass
 * `legacyOnly = false` to let the opening native path run against the bundled test engine, which is
 * not the server's patched rule set.
 */
internal object AiDecisionSnapshotReplay {
    data class Result(
        val decision: BattleDecision,
        val ranked: List<LocalBattleActionRank>,
        val selection: LocalActionSelection,
        /** Every candidate's value at each step, when the replay ran the game's searchless brain. */
        val stages: LocalDecisionStages? = null,
    )

    fun load(path: Path): AiTestDecisionSnapshot = AiTestDecisionSnapshot.fromJson(Files.readString(path))

    fun replay(
        snapshot: AiTestDecisionSnapshot,
        legacyOnly: Boolean = true,
        nowEpochMillis: Long = System.currentTimeMillis(),
        /** The brain the game runs (MoreCobblemonContentsBetterAi): no search, the quick decider on. */
        searchless: Boolean = false,
    ): Result {
        val weighted = LocalWeightedActionSelector()
        var captured: Pair<List<LocalBattleActionRank>, LocalActionSelection>? = null
        val selector = LocalActionSelector { ranked, seed, mixing ->
            weighted.choose(ranked, seed, mixing).also { captured = ranked to it }
        }
        var stages: LocalDecisionStages? = null
        val brain = if (searchless) {
            LocalTacticalBrain(actionSelector = selector, searchless = true, stageTrace = { stages = it })
        } else if (legacyOnly) {
            LocalTacticalBrain(
                actionSelector = selector,
                nativeInitialDecision = { _, _, _, _, _, _ ->
                    NativeInitialProductDecisionEvaluation(NativeInitialProductDecisionStatus.NOT_APPLICABLE)
                },
            )
        } else {
            LocalTacticalBrain(actionSelector = selector)
        }
        val session = brain.openSession(BattleBrainOpenContext(
            battleId = snapshot.battleId,
            format = snapshot.context.state.format,
            strategy = snapshot.strategy,
            trainerProfile = snapshot.trainerProfile,
            trainerPersonaId = snapshot.trainerPersonaId,
        ))
        val remaining = snapshot.context.deadlineEpochMillis - snapshot.capturedAtEpochMillis
        val context = snapshot.context.copy(deadlineEpochMillis = nowEpochMillis + remaining.coerceAtLeast(0L))
        val decision = brain.decide(session, context).toCompletableFuture().get()
        val (ranked, selection) = captured ?: error("The replayed decision never reached the selector")
        return Result(decision, ranked, selection, stages)
    }

    /** `./gradlew :more-cobblemon-contents:replayDecisionSnapshot -Psnapshot=<file>` */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) { "Expected a snapshot path and optional 'native' or 'search'" }
        val snapshot = load(Path.of(args[0]))
        val mode = args.getOrNull(1)
        val result = replay(snapshot, legacyOnly = mode != "native", searchless = mode == null)
        println("battle=${snapshot.battleId} turn=${snapshot.turn} nativeContinuation=${snapshot.nativeContinuation}")
        result.ranked.forEachIndexed { index, rank ->
            val id = rank.outcome.candidate.actionId
            println(String.format(
                Locale.ROOT,
                "%2d %-48s score=%9.3f draw=%.3f excluded=%s keepHp=%.2f keepHpConfirmed=%.2f " +
                    "utility=%.3f damage=%.3f knockout=%.3f stages=%.3f item=%.3f accuracy=%s",
                index + 1, id, rank.comparisonValue,
                result.selection.probabilitiesByActionId[id] ?: 0.0,
                result.selection.exclusionsByActionId[id] ?: "-",
                rank.worstResponseHpRetention, rank.worstConfirmedResponseHpRetention,
                rank.outcome.tacticalUtility, rank.outcome.expectedDamageFraction, rank.outcome.knockoutUtility,
                rank.outcome.statStageUtility, rank.outcome.itemUtility, rank.outcome.effectiveAccuracyProbability ?: "-",
            ))
        }
        result.stages?.let(::printStages)
        println("selected=${result.decision.actionId} tags=${result.decision.tags.sorted()}")
    }

    private fun printStages(stages: LocalDecisionStages) {
        fun value(map: Map<String, Double>, id: String) = map[id]?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "-"
        val ids = (stages.base.keys + stages.refined.keys).distinct().sortedByDescending { stages.refined[it] ?: Double.NEGATIVE_INFINITY }
        println("stages nativeStatus=${stages.nativeStatusStatus} drawn=${stages.drawn} quick=${stages.quick}:${stages.quickReason} selected=${stages.selected}")
        ids.forEach { id ->
            val rules = stages.rules.mapNotNull { (rule, values) -> values[id]?.takeIf { it != 0.0 }?.let { "$rule=${String.format(Locale.ROOT, "%.3f", it)}" } }
            println("  $id base=${value(stages.base, id)} tier=${stages.tiers[id] ?: "-"} native=${value(stages.nativeStatus, id)} " +
                "rules=$rules afterRules=${value(stages.afterRules, id)} kind=${value(stages.afterKindWeights, id)} " +
                "refined=${value(stages.refined, id)} quick=${stages.quickBonus[id].orEmpty()} " +
                "ruleExcluded=${stages.ruleExclusions[id] ?: "-"} draw=${value(stages.drawProbabilities, id)} drawExcluded=${stages.drawExclusions[id] ?: "-"}")
        }
    }
}
