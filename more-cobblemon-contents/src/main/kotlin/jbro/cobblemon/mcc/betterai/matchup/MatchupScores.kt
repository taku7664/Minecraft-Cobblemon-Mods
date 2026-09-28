package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID

/*
 * The matchup scores: one family, one convention.
 *
 * - Every score is a `...Score` whose headline value is `score`, on -1..1 and positive for its subject.
 * - Everything comes from the AI's conservative public reading: its own attacks at the low-damage
 *   stat hypothesis, the opponent's at the high one, so both sides of a pair are read the same way.
 * - One decision's scores live in one [MatchupScores], built once and shared by every consumer.
 *
 * | score | granularity | Speed |
 * |---|---|---|
 * | [MoveMatchupScore] | user x move x target | ignored: what the move does when it lands |
 * | [PokemonMatchupScore] | subject x opponent | the exchange, including who moves first |
 * | [AceScore] | subject, against the whole opposing team | through the matchups |
 * | [AntiAceScore] | subject x the opposing ace, after its setup | through the matchups |
 * | [StatusMoveMatchupScore] | user x status move x target | through the matchups |
 * | [SwitchInScore] | incoming x opponent x the ally it replaces | through the matchups |
 * | [PreserveScore] | subject, within its own team | through the matchups |
 *
 * A score for an action that spends a turn pays for it the same way everywhere: the opponent attacks
 * once meanwhile, and the exchange that follows starts from the HP that leaves.
 */

/**
 * What one move does to one target, turn order aside: the damage it deals and how many hits it needs.
 *
 * [score] is `1 / expectedHitsToKnockout`: a certain one-hit knockout is 1.0, a certain two-hit one 0.5,
 * a move that does nothing 0.0.
 */
internal data class MoveMatchupScore(
    val userId: UUID,
    val moveId: String,
    val targetId: UUID,
    val accuracy: Double,
    /** Least and greatest roll, as fractions of the target's maximum HP, capped at its current HP. */
    val minimumDamageFraction: Double,
    val maximumDamageFraction: Double,
    /**
     * Index `n`: the chance the target is still standing after `n` uses (index 0 is 1.0), misses
     * included. `1 - survivalByUses[n]` is the chance of a knockout within `n` uses.
     */
    val survivalByUses: List<Double>,
    /** Index `n`: the damage dealt by `n` uses, averaged over the cases the target is still standing. */
    val damageWhileStandingByUses: List<Double>,
    val expectedHitsToKnockout: Double,
    val score: Double,
) {
    fun knockoutChanceWithin(uses: Int): Double = 1.0 - survivalByUses[uses.coerceIn(0, survivalByUses.lastIndex)]
}

/** The field a [PokemonMatchupScore] is read under. */
internal enum class MatchupSpeedField {
    /** The field as it stands. */
    CURRENT,

    /** Trick Room the other way round: set when it is down, ended when it is up. */
    TRICK_ROOM_TOGGLED,
}

/**
 * The one-on-one exchange between [subjectId] and [opponentId]: each uses its best attack on the other
 * every turn until one faints.
 *
 * [score] = `win * (0.5 + 0.5 * subjectRemainingHpOnWin) - loss * (0.5 + 0.5 * opponentRemainingHpOnLoss)`:
 * winning with most of your HP left is close to 1.0, being knocked out before denting the opponent close
 * to -1.0. The same exchange read from the opponent's side is the exact negation, see [mirrored].
 */
internal data class PokemonMatchupScore(
    val subjectId: UUID,
    val opponentId: UUID,
    val speedField: MatchupSpeedField,
    /** The subject's best attack on the opponent, or null when it has none that deals damage. */
    val subjectMove: MoveMatchupScore?,
    val opponentMove: MoveMatchupScore?,
    /** Chance the subject's best attack goes first, priority included. */
    val subjectMovesFirstProbability: Double,
    val winProbability: Double,
    /** HP the subject expects to have left, as a fraction of its maximum, in the exchanges it wins. */
    val subjectRemainingHpOnWin: Double,
    /** HP the opponent expects to have left in the exchanges it wins. */
    val opponentRemainingHpOnLoss: Double,
    val score: Double,
) {
    val lossProbability: Double get() = 1.0 - winProbability

    fun mirrored(): PokemonMatchupScore = PokemonMatchupScore(
        subjectId = opponentId,
        opponentId = subjectId,
        speedField = speedField,
        subjectMove = opponentMove,
        opponentMove = subjectMove,
        subjectMovesFirstProbability = 1.0 - subjectMovesFirstProbability,
        winProbability = lossProbability,
        subjectRemainingHpOnWin = opponentRemainingHpOnLoss,
        opponentRemainingHpOnLoss = subjectRemainingHpOnWin,
        score = -score,
    )
}

/**
 * How much of the opposing team [subjectId] can sweep: the share of one-on-ones it wins as it stands, and
 * after its best stat-raising status move.
 *
 * [score] (0..1) is the better of [naturalSweep] and [boostedSweep]. The boosted reading pays for its setup
 * turns: every foe attacks through them, and the exchange that follows starts from the HP they left.
 */
internal data class AceScore(
    val subjectId: UUID,
    /** Mean win chance of its one-on-ones against every living opponent it has seen. */
    val naturalSweep: Double,
    /** The stat-raising status move the boosted reading uses, or null when it has none. */
    val setupMoveId: String?,
    /** How many uses of [setupMoveId] the boosted reading assumes. */
    val setupUses: Int,
    /** Mean over foes of surviving the setup turns times winning the boosted exchange after them. */
    val boostedSweep: Double,
    /** Mean chance it survives the setup turns: what the boost costs. */
    val setupSafety: Double,
    val score: Double,
    /** Per opponent: surviving the setup turns times winning the boosted exchange after them. */
    val boostedByOpponent: Map<UUID, Double> = emptyMap(),
    /** Per opponent: the chance of surviving the setup turns. */
    val setupSurvivalByOpponent: Map<UUID, Double> = emptyMap(),
)

/** A way to stop an ace that has set up. */
internal enum class AntiAceToolKind {
    /** Beats the boosted ace with attacks alone. */
    OUTLASTS,
    /** Unaware: the ace's attack and defence stages stop counting; its Speed still does. */
    IGNORES_BOOSTS,
    /** Haze, Clear Smog, Topsy-Turvy, Spectral Thief. */
    RESETS_BOOSTS,
    /** Roar, Whirlwind, Dragon Tail, Circle Throw: the ace leaves and its stages with it. */
    FORCES_SWITCH,
    /** Locks the ace into the setup move it just used. */
    ENCORE,
    /** Stops the setup before it happens, so it counts only when the subject moves first. */
    TAUNT,
    TRICK_ROOM,
    BURN,
    PARALYSIS,
    /** A move that lowers the ace's stages for certain. */
    STAT_DROP,
    /** A priority attack that knocks the boosted ace out in one hit. */
    PRIORITY_FINISH,
    PERISH_SONG,
    DESTINY_BOND,
}

/** One tool and what it is worth against this ace, 0..1. */
internal data class AntiAceTool(val kind: AntiAceToolKind, val moveId: String?, val value: Double)

/**
 * How well [subjectId] stops [aceId], the opposing side's best [AceScore], once the ace has set up as its
 * ace score assumes.
 *
 * Every tool is priced the same way: the chance the subject gets it off before the boosted ace knocks it
 * out, times what the exchange looks like afterwards. A one-time survival (Focus Sash, Sturdy, Disguise,
 * Multiscale) guarantees the first action and adds [ONE_TIME_SURVIVAL_BONUS], since surviving one hit is
 * a stopper in itself. [score] (0..1) is the best tool plus that bonus.
 */
internal data class AntiAceScore(
    val subjectId: UUID,
    val aceId: UUID,
    /** Chance the subject acts before the boosted ace knocks it out. */
    val actsBeforeKnockout: Double,
    val tools: List<AntiAceTool>,
    /** The item or ability that guarantees surviving one hit, if any. */
    val oneTimeSurvival: String?,
    val score: Double,
) {
    val bestTool: AntiAceTool? get() = tools.maxByOrNull { it.value }

    companion object {
        const val ONE_TIME_SURVIVAL_BONUS = 0.15
    }
}

/**
 * What a status move does to the exchange: the matchup after it lands, the turn spent on it paid for,
 * against the matchup without it.
 *
 * [score] = `survivesTurn * (accuracy * afterLanding + (1 - accuracy) * afterMiss) - (1 - survivesTurn) - before`,
 * clamped to -1..1: a burn on a physical attacker is positive, the same burn on a special one costs the
 * turn and comes out negative.
 */
internal data class StatusMoveMatchupScore(
    val userId: UUID,
    val moveId: String,
    val targetId: UUID,
    val accuracy: Double,
    /** Chance the user survives the target's attack in the turn it spends on the move. */
    val survivesTurn: Double,
    /** The exchange without the move. */
    val before: Double,
    /** The exchange after the move lands, from the HP the turn left. */
    val afterLanding: Double,
    /** The exchange after the move misses: only the turn is gone. */
    val afterMiss: Double,
    val score: Double,
)

/**
 * [incomingId] switching in for [replacedId] in front of [opponentId]: the hit it takes on the way in,
 * then the exchange from what that leaves.
 *
 * The predicted hit is the opponent's best move against the Pokemon it expected to face; the worst hit is
 * its most damaging move against the incoming one, for an opponent that saw the switch coming. [score]
 * reads the predicted hit: `survival * afterEntry - (1 - survival)`.
 */
internal data class SwitchInScore(
    val incomingId: UUID,
    val opponentId: UUID,
    val replacedId: UUID,
    val predictedMoveId: String?,
    val predictedSurvival: Double,
    /** HP left after the entry hazards and the predicted hit, in the cases it survives. */
    val hpAfterEntry: Double,
    val worstMoveId: String?,
    val worstSurvival: Double,
    val afterEntry: PokemonMatchupScore?,
    val score: Double,
)

/**
 * How much of its team's answer to the opposing team goes with [subjectId]: the team's coverage (for each
 * opponent, the best win chance any living teammate has against it, averaged) with and without it.
 *
 * [score] (0..1) is the drop. The only answer to an opponent carries it; one of several carries little.
 */
internal data class PreserveScore(
    val subjectId: UUID,
    val coverageWith: Double,
    val coverageWithout: Double,
    /** Opponents it alone beats (win chance at least one half). */
    val soleAnswerTo: List<UUID>,
    val score: Double,
)

/** One decision's matchup scores, read from the deciding trainer's side of the board. */
internal class MatchupScores(
    private val movesByPair: Map<Pair<UUID, UUID>, List<MoveMatchupScore>>,
    /** Keyed by (ally, opponent); the opponent's view is the mirror. */
    private val pokemonByPair: Map<Triple<UUID, UUID, MatchupSpeedField>, PokemonMatchupScore>,
    /** Whether every pair was scored before the budget ran out. */
    val complete: Boolean,
    /** Both sides' living Pokemon, keyed by battle Pokemon ID. */
    val aces: Map<UUID, AceScore> = emptyMap(),
    /** Both sides' living Pokemon against the other side's ace, keyed by the subject. */
    val antiAces: Map<UUID, AntiAceScore> = emptyMap(),
    /** Active Pokemon's status moves against the opposing actives, keyed by (user, target). */
    private val statusMovesByPair: Map<Pair<UUID, UUID>, List<StatusMoveMatchupScore>> = emptyMap(),
    /** Keyed by (incoming, opponent, replaced). */
    private val switchInsByKey: Map<Triple<UUID, UUID, UUID>, SwitchInScore> = emptyMap(),
    /** Both sides' living Pokemon. */
    val preserves: Map<UUID, PreserveScore> = emptyMap(),
) {
    /** [userId]'s scored status moves against [targetId], best first. */
    fun statusMoves(userId: UUID, targetId: UUID): List<StatusMoveMatchupScore> = statusMovesByPair[userId to targetId].orEmpty()

    fun switchIn(incomingId: UUID, opponentId: UUID, replacedId: UUID): SwitchInScore? =
        switchInsByKey[Triple(incomingId, opponentId, replacedId)]

    val switchIns: Collection<SwitchInScore> get() = switchInsByKey.values

    /** The side's best sweeper, if any of its Pokemon was scored. */
    fun ace(side: jbro.cobblemon.mcc.internal.ai.BattleSide, state: jbro.cobblemon.mcc.internal.ai.BattleStateView): AceScore? =
        state.pokemon.filter { it.side == side }.mapNotNull { aces[it.battlePokemonId] }.maxByOrNull { it.score }

    /** The damaging moves [userId] has against [targetId], best first. */
    fun moves(userId: UUID, targetId: UUID): List<MoveMatchupScore> = movesByPair[userId to targetId].orEmpty()

    fun pokemon(
        subjectId: UUID,
        opponentId: UUID,
        speedField: MatchupSpeedField = MatchupSpeedField.CURRENT,
    ): PokemonMatchupScore? = pokemonByPair[Triple(subjectId, opponentId, speedField)]
        ?: pokemonByPair[Triple(opponentId, subjectId, speedField)]?.mirrored()

    /** Every scored pair, from the deciding trainer's side. */
    val pokemonMatchups: Collection<PokemonMatchupScore> get() = pokemonByPair.values

    companion object {
        val EMPTY = MatchupScores(emptyMap(), emptyMap(), complete = false)
    }
}
