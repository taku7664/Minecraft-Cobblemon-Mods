package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalNonDamagingMoveEvaluator
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.betterai.simulation.NativeInitialProductWorldPlanner
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Prices the status moves the one-turn ranking has no evaluator for by playing the turn out in the native engine.
 *
 * The ranking gives such a move the generic status value (20) whatever it does: Defog scored the same clearing the
 * opponent's hazards as clearing this side's own, Trick Room the same setting the room as ending it. The engine runs
 * every move's real effect, so these moves and the user's three best attacks (by the ranking) go through a
 * one-turn native search over the opponent's three likeliest worlds, scored by the search's own leaf
 * ([jbro.cobblemon.mcc.betterai.evaluation.LocalLookaheadStateEvaluator]). Each move is moved to where the engine puts
 * it against those: its ranking value becomes the engine's value moved by the three actions' mean offset between the
 * ranking and the engine. No move
 * is named; which moves go through is whatever [LocalNonDamagingMoveEvaluator] could not price.
 *
 * The engine needs the opponent's preview and this side's exact team, which every brain battle hands over, and runs
 * to the decision's own deadline. If it still cannot run, the moves keep the generic value and the failure is logged
 * as an error to fix.
 */
internal object LocalNativeStatusValue {
    data class Result(
        val adjustments: Map<String, Double>,
        val status: String,
        val elapsedMillis: Long,
        val plies: Int = 0,
        /** The moves held under every publicly failing action ([LocalEngineTurnReadout.Reading.selfHarm]). */
        val selfHarm: Set<String> = emptySet(),
    )

    /** The opponent's likeliest worlds the engine plays the turn in. */
    const val WORLDS = 3
    /** How far under the publicly failing actions' level a self-harming move goes. */
    private const val SELF_HARM_MARGIN = 100.0
    /** The opponent's replies followed past the first turn when a move is played two turns. */
    private const val SECOND_TURN_REPLIES = 3
    /** Declared effects whose value comes after the turn they are made in. */
    private val LASTING = setOf(
        BattleMoveEffectKind.VOLATILE_STATUS, BattleMoveEffectKind.SIDE_CONDITION, BattleMoveEffectKind.SLOT_CONDITION,
        BattleMoveEffectKind.FIELD_CONDITION, BattleMoveEffectKind.WEATHER, BattleMoveEffectKind.TERRAIN,
    )

    fun lasting(candidate: BattleActionCandidate): Boolean =
        candidate.moveDetails?.effects?.effects.orEmpty().any { it.kind in LASTING }

    /** The ranking's best attacks (else other actions) that anchor the engine's values to the ranking's. */
    private const val REFERENCES = 3
    /** Far above a one-turn search's need (80 to 221 nodes over eight to twelve actions): the turn always completes. */
    private const val NODE_LIMIT = 20_000
    private val logger = org.slf4j.LoggerFactory.getLogger("BetterAI")

    private val planner = NativeInitialProductWorldPlanner(worldLimit = { _, _ -> WORLDS })
    private val defaultEvaluator = NativeInitialProductDecisionEvaluator(planner::plan, planner::planMidBattle, planner::planLive)

    /** Plays one turn: the candidates are the whole decision's, the excluded ones mapped but not searched. */
    fun interface Source {
        fun evaluate(
            context: BattleDecisionContext,
            profile: BattleTrainerProfile,
            tuning: LocalDecisionTuning,
            budget: LocalLookaheadBudget,
            rules: NativeRulePriorities,
        ): NativeInitialProductDecisionEvaluation
    }

    val DEFAULT_SOURCE = Source { context, profile, tuning, budget, rules -> defaultEvaluator.evaluate(context, profile, tuning, budget, rules = rules) }

    fun unpriced(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean =
        candidate.kind == BattleActionKind.USE_MOVE &&
            candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS &&
            LocalNonDamagingMoveEvaluator.score(candidate, context, 1.0).unpriced

    fun adjustments(
        ranked: List<LocalBattleActionRank>,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        source: Source,
        /** The deepest the moves are played; one keeps every move to its own turn (for comparing the two). */
        maxPlies: Int = 2,
    ): Result? {
        // A move the ranking already rules out (it fails publicly) stays out.
        val open = ranked.filter { it.decisionTier > LocalBattleActionPolicy.TIER_PUBLICLY_BAD }
        val status = open.filter { unpriced(it.outcome.candidate, context) }
        if (status.isEmpty()) return null
        // The anchors in the ranking's units: the user's best attacks, what a status move displaces. Attacks are priced
        // by their damage on both scales; a switch is not (the ranking and the engine disagreed by 30 points on one, and
        // a two-turn engine value set against it lifted Haze over Ice Beam). Other actions only when it has no attack.
        val others = open.filter { rank -> status.none { it === rank } }.sortedByDescending { it.comparisonValue }
        val attacks = others.filter {
            it.outcome.candidate.kind == BattleActionKind.USE_MOVE &&
                it.outcome.candidate.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS
        }
        val references = attacks.ifEmpty { others }.take(REFERENCES).ifEmpty { return null }
        val started = System.nanoTime()
        // A move that leaves something behind (a volatile, a side, slot or field condition, weather, terrain) pays off on
        // the turn after: Substitute takes the next hit, Taunt blocks the next status move, Leech Seed drains at the
        // next end of turn. The leaf reads none of those, so such moves are played one turn further, where this side
        // attacks and the opponent's three likeliest replies answer. The rest settle in the turn itself.
        // Read off the engine's board as well: a move whose effect lives in a script (Haze) declares nothing, but what
        // it left behind shows there. The same reading finds the moves that only cost this side something.
        val reading = LocalEngineTurnReadout.read(context, profile.difficulty.tier, status.map { it.outcome.candidate },
            references.first().outcome.candidate)
        val leavesSomething = status.any { lasting(it.outcome.candidate) } || reading?.changed?.isNotEmpty() == true
        val plies = if (leavesSomething) minOf(2, maxPlies) else 1
        val budget = LocalLookaheadBudgetPolicy.forTier(profile.difficulty.tier)
            // No clock of its own: the decision's deadline (45 s) is the only limit, so the turn is never cut short.
            .copy(nativePlies = plies, nativeNodeLimit = NODE_LIMIT, timeMillis = LocalLookaheadBudgetPolicy.MAX_TIME_MILLIS,
                opponentResponseLimit = SECOND_TURN_REPLIES, finalPlyAttacksOnly = true)
        val evaluation = try {
            // Every candidate goes in, so a live root's actions all map; only these moves and the references are searched.
            val searched = (status + references).mapTo(hashSetOf()) { it.outcome.candidate.actionId }
            source.evaluate(context, profile, tuning, budget,
                NativeRulePriorities(excludedActionIds = context.candidates.map { it.actionId }.filterTo(hashSetOf()) { it !in searched }))
        } catch (e: RuntimeException) {
            logger.error("[BetterAI] Status moves could not be played out in the engine; they keep the generic value", e)
            return Result(emptyMap(), "error_${e.javaClass.simpleName}", (System.nanoTime() - started) / 1_000_000L)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        if (evaluation.status != NativeInitialProductDecisionStatus.AVAILABLE) {
            // Not a fallback to rely on: every battle a brain plays carries the preview and own team the engine needs,
            // so this is a defect to fix, logged as one.
            logger.error("[BetterAI] Status moves could not be played out in the engine ({}, search {}, run {} {}, plan {}); " +
                "they keep the generic value", evaluation.status, evaluation.searchStatus, evaluation.failedRunStatus,
                evaluation.failedRunDetail, evaluation.planIssues.map { it.code })
            return Result(emptyMap(), evaluation.status.name.lowercase(), elapsed)
        }
        val native = evaluation.ranked.associate { it.outcome.candidate.actionId to it.comparisonValue }
        val offsets = references.mapNotNull { rank -> native[rank.outcome.candidate.actionId]?.let { rank.comparisonValue - it } }
        if (offsets.isEmpty()) {
            logger.error("[BetterAI] The engine ranked none of the references {}; status moves keep the generic value",
                references.map { it.outcome.candidate.actionId })
            return Result(emptyMap(), "no_reference", elapsed)
        }
        // The engine's value moved into the ranking's units by the references' mean offset.
        val offset = offsets.average()
        val selfHarm = reading?.selfHarm.orEmpty()
        val out = status.mapNotNull { rank ->
            val id = rank.outcome.candidate.actionId
            native[id]?.let { value ->
                var final = value + offset
                // A move that only costs this side something is worse than a turn that does nothing at all: it goes
                // under every publicly failing action (which the ranking puts near PUBLICLY_INERT_TIER_ADJUSTMENT),
                // so it never wins a position where everything else is bad too.
                if (rank.outcome.candidate.moveId?.let(PublicIds::canonical) in selfHarm) {
                    final = minOf(final, LocalBattleActionPolicy.PUBLICLY_INERT_TIER_ADJUSTMENT - SELF_HARM_MARGIN + minOf(0.0, final))
                }
                id to final - rank.comparisonValue
            }
        }.toMap()
        return Result(out, "priced", elapsed, plies, selfHarm)
    }
}
