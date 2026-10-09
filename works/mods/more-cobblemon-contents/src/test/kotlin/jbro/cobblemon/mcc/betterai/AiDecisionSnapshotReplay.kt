package jbro.cobblemon.mcc.betterai

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import jbro.cobblemon.mcc.internal.ai.BattleBrainOpenContext
import jbro.cobblemon.mcc.internal.ai.BattleDecision
import jbro.cobblemon.mcc.betterai.brain.AiTestDecisionSnapshot
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
    )

    fun load(path: Path): AiTestDecisionSnapshot = AiTestDecisionSnapshot.fromJson(Files.readString(path))

    fun replay(
        snapshot: AiTestDecisionSnapshot,
        legacyOnly: Boolean = true,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Result {
        val weighted = LocalWeightedActionSelector()
        var captured: Pair<List<LocalBattleActionRank>, LocalActionSelection>? = null
        val selector = LocalActionSelector { ranked, seed, mixing ->
            weighted.choose(ranked, seed, mixing).also { captured = ranked to it }
        }
        val brain = if (legacyOnly) {
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
        return Result(decision, ranked, selection)
    }

    /** `./gradlew :more-cobblemon-contents:replayDecisionSnapshot -Psnapshot=<file>` */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) { "Expected a snapshot path and optional 'native'" }
        val snapshot = load(Path.of(args[0]))
        val result = replay(snapshot, legacyOnly = args.getOrNull(1) != "native")
        println("battle=${snapshot.battleId} turn=${snapshot.turn} nativeContinuation=${snapshot.nativeContinuation}")
        result.ranked.forEachIndexed { index, rank ->
            val id = rank.outcome.candidate.actionId
            println(String.format(
                Locale.ROOT,
                "%2d %-48s score=%9.3f draw=%.3f excluded=%s keepHp=%.2f keepHpConfirmed=%.2f",
                index + 1, id, rank.comparisonValue,
                result.selection.probabilitiesByActionId[id] ?: 0.0,
                result.selection.exclusionsByActionId[id] ?: "-",
                rank.worstResponseHpRetention, rank.worstConfirmedResponseHpRetention,
            ))
        }
        println("selected=${result.decision.actionId} tags=${result.decision.tags.sorted()}")
    }
}
