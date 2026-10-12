package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalNonDamagingMoveEvaluator
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.LocalOpponentIntentPredictor
import jbro.cobblemon.mcc.betterai.matchup.OpponentIntent
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionRank
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * Prices the status moves the one-turn ranking has no evaluator for by playing them out in the native engine.
 *
 * The ranking gives such a move the generic status value (20) whatever it does: Defog scored the same clearing the
 * opponent's hazards as clearing this side's own, Trick Room the same setting the room as ending it. The engine runs
 * every move's real effect, so these moves and the user's three best attacks (by the ranking) are played against the
 * opponent's replies in its three likeliest worlds ([LocalEngineTurnReadout]): the boards valued by the search's
 * leaf, the replies weighed by how likely the opponent is to make them with a share of the worst one. Each move then
 * takes the engine's value moved into the ranking's units by the three attacks' mean offset between the two. No move
 * is named; which moves go through is whatever [LocalNonDamagingMoveEvaluator] could not price.
 *
 * A move whose data declares a lasting effect is played two turns. A move that only cost this side something goes
 * under every publicly failing action.
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
    /** The ranking's best attacks (else other actions) that anchor the engine's values to the ranking's. */
    private const val REFERENCES = 3
    /** Board value to the ranking's units (NativeProductRankAdapter's scale). */
    private const val BOARD_TO_SCORE = 100.0
    /** Declared effects whose value comes after the turn they are made in. */
    private val LASTING = setOf(
        BattleMoveEffectKind.VOLATILE_STATUS, BattleMoveEffectKind.SIDE_CONDITION, BattleMoveEffectKind.SLOT_CONDITION,
        BattleMoveEffectKind.FIELD_CONDITION, BattleMoveEffectKind.WEATHER, BattleMoveEffectKind.TERRAIN,
    )
    private val logger = org.slf4j.LoggerFactory.getLogger("BetterAI")

    fun lasting(candidate: BattleActionCandidate): Boolean =
        candidate.moveDetails?.effects?.effects.orEmpty().any { it.kind in LASTING }

    fun unpriced(candidate: BattleActionCandidate, context: BattleDecisionContext): Boolean =
        candidate.kind == BattleActionKind.USE_MOVE &&
            candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS &&
            LocalNonDamagingMoveEvaluator.score(candidate, context, 1.0).unpriced

    fun adjustments(
        ranked: List<LocalBattleActionRank>,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tuning: LocalDecisionTuning,
        /** The opponent's predicted replies; null predicts them here from the matchup table. */
        intents: List<OpponentIntent>? = null,
        /** The deepest the moves are played; one keeps every move to its own turn (for comparing the two). */
        maxPlies: Int = 2,
    ): Result? {
        // A move the ranking already rules out (it fails publicly) stays out.
        val open = ranked.filter { it.decisionTier > LocalBattleActionPolicy.TIER_PUBLICLY_BAD }
        val status = open.filter { unpriced(it.outcome.candidate, context) }
        if (status.isEmpty()) return null
        // The anchors in the ranking's units: the user's best attacks, what a status move displaces. Attacks are priced
        // by their damage on both scales; a switch is not (the ranking and the engine disagreed by 30 points on one, and
        // set against it Haze came out over Ice Beam). Other actions only when it has no attack.
        val others = open.filter { rank -> status.none { it === rank } }.sortedByDescending { it.comparisonValue }
        val attacks = others.filter {
            it.outcome.candidate.kind == BattleActionKind.USE_MOVE &&
                it.outcome.candidate.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS
        }
        val references = attacks.ifEmpty { others }.take(REFERENCES).ifEmpty { return null }
        val started = System.nanoTime()
        val reading = try {
            val replies = intents ?: LocalOpponentIntentPredictor.predict(context, LocalMatchupScoreCalculator.calculate(context), scoredSwitches = true)
            LocalEngineTurnReadout.read(context, profile.difficulty.tier, status.map { it.outcome.candidate },
                references.map { it.outcome.candidate }, replies, tuning,
                lastingDeclared = status.any { lasting(it.outcome.candidate) }, maxPlies = maxPlies)
        } catch (e: RuntimeException) {
            logger.error("[BetterAI] Status moves could not be played out in the engine; they keep the generic value", e)
            return Result(emptyMap(), "error_${e.javaClass.simpleName}", (System.nanoTime() - started) / 1_000_000L)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        if (reading == null) return Result(emptyMap(), "not_played", elapsed)
        val offsets = references.mapNotNull { rank ->
            reading.values[rank.outcome.candidate.actionId]?.let { rank.comparisonValue - it * BOARD_TO_SCORE }
        }
        if (offsets.isEmpty()) {
            logger.error("[BetterAI] The engine played none of the references {}; status moves keep the generic value",
                references.map { it.outcome.candidate.actionId })
            return Result(emptyMap(), "no_reference", elapsed)
        }
        // The engine's value moved into the ranking's units by the references' mean offset.
        val offset = offsets.average()
        val out = status.mapNotNull { rank ->
            val id = rank.outcome.candidate.moveId?.let(PublicIds::canonical) ?: return@mapNotNull null
            reading.values[id]?.let { value ->
                var final = value * BOARD_TO_SCORE + offset
                // A move that only costs this side something is worse than a turn that does nothing at all: it goes
                // under every publicly failing action (which the ranking puts near PUBLICLY_INERT_TIER_ADJUSTMENT),
                // so it never wins a position where everything else is bad too.
                if (id in reading.selfHarm) {
                    final = minOf(final, LocalBattleActionPolicy.PUBLICLY_INERT_TIER_ADJUSTMENT - SELF_HARM_MARGIN + minOf(0.0, final))
                }
                rank.outcome.candidate.actionId to final - rank.comparisonValue
            }
        }.toMap()
        return Result(out, "priced", elapsed, reading.plies, reading.selfHarm)
    }
}
