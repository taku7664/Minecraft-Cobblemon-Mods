package jbro.cobblemon.mcc.betterai.matchup

import java.util.UUID
import kotlin.math.exp
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveOptionView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.PublicIds

internal enum class IntentKind { ATTACK, STATUS, PROTECT, SETUP, SWITCH }

/** One thing an opposing active Pokemon may do this turn, valued from its trainer's side. */
internal data class IntentOption(
    val kind: IntentKind,
    val moveId: String?,
    /** The ally a single-target move aims at; null for spread moves, self moves and switches. */
    val targetId: UUID?,
    val switchInId: UUID?,
    val value: Double,
    val probability: Double,
)

/** What an opposing active Pokemon is expected to do, most likely first. */
internal data class OpponentIntent(val pokemonId: UUID, val activeSlot: Int, val options: List<IntentOption>) {
    /** The chance of each move or switch, targets merged. */
    fun byAction(): Map<String, Double> = options.groupBy { option ->
        option.moveId?.let { "move:$it" } ?: "switch:${option.switchInId}"
    }.mapValues { (_, same) -> same.sumOf { it.probability } }
}

/**
 * The opponent's likely actions, read from the same [MatchupScores] the rules use, from the opponent's
 * side of the table. Each option gets a value in about -1..1 and the values become chances through a
 * softmax at [TEMPERATURE]: a clear best option dominates, close ones share.
 *
 * - An attack: its chance to knock the target out this turn, and the share of the target's HP it takes.
 *   A spread move adds up every ally it hits.
 * - A scored status move: its [StatusMoveMatchupScore], a setup move: the [SweepScore] it builds.
 * - Protect: how likely an ally's best attack is to knock the user out this turn; it fails after itself.
 * - Fake Out: strong on the first turn out, failing after.
 * - A switch: how much better the incoming Pokemon's matchups are than the user's, less the turn it costs.
 *
 * Scored switches ([LocalDecisionTuning.scoredSwitchIntent]) split the two questions. Whether the Pokemon switches
 * at all comes from [switchChance], fitted against what trainers did; which Pokemon comes in stays the softmax
 * over the switch values, and the moves share what is left. The plain softmax put a matchup gain and an attack's
 * value on one scale they do not share, and read a switch far more often than one came.
 *
 * Only the deciding trainer's public view goes in: the opponent's revealed moves and seen bench.
 */
internal object LocalOpponentIntentPredictor {
    fun predict(
        context: BattleDecisionContext,
        scores: MatchupScores,
        scoredSwitches: Boolean = false,
        /** Doubles reads the switch model too, with the singles weights. */
        scoredInDoubles: Boolean = false,
    ): List<OpponentIntent> {
        val state = context.state
        val allies = state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot != null && standing(it) }
        if (allies.isEmpty()) return emptyList()
        val bench = seenBench(state)
        // The switch model was fitted on singles. In doubles, 894 slot positions with a seen bench (3.9% switches):
        // the one softmax predicted 32% (Brier 0.29, the base rate alone 0.038), the singles weights 7% (0.034); a
        // doubles fit on its 35 switches did no better (0.035 cross-validated).
        val scored = scoredSwitches && (state.format == BattleFormat.SINGLE || scoredInDoubles)
        val features = if (scored) switchFeatures(context, scores) else emptyMap()
        return state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && standing(it) }.map { user ->
            val moves = context.publicActionCatalog.forPokemon(user.battlePokemonId)
                .distinctBy { PublicIds.canonical(it.moveId) }
                .flatMap { move(it, user, allies, bench, state, scores) }
            val switches = bench.mapNotNull { switchOption(user, it, allies, scores) }
            val options = if (!scored) softmax(moves + switches) else {
                // Whether it switches, from the fitted model; where to, by the incoming Pokemon's matchups.
                val chance = features[user.battlePokemonId]?.let(::switchChance) ?: 0.0
                softmax(moves).map { it.copy(probability = it.probability * (1.0 - chance)) } +
                    softmax(switches).map { it.copy(probability = it.probability * chance) }
            }
            OpponentIntent(user.battlePokemonId, requireNotNull(user.activeSlot), options.sortedByDescending { it.probability })
        }
    }

    /**
     * The chance [features]' Pokemon switches out this turn: a logistic model fitted on Boss self-play (singles), each
     * side's features read from the other side's public view against what that side then did. Its trainer switches
     * about one turn in ten; more when the Pokemon would fall before acting, when it is worth keeping and when a
     * Pokemon behind it enters well; less when it has a strong attack, is healthy, or has more of its bench seen
     * (a trainer down to one known answer sends it in).
     */
    fun switchChance(features: SwitchFeatures): Double {
        val z = SWITCH_BIAS + SWITCH_THREATENED * features.threatened + SWITCH_ENTRY_GAIN * features.bestGain +
            SWITCH_ATTACK * features.bestAttack + SWITCH_HP * features.hp + SWITCH_PRESERVE * features.preserve +
            SWITCH_BENCH * features.bench
        return 1.0 / (1.0 + exp(-z))
    }

    /** What the switch model reads for one opposing active Pokemon, from its trainer's side. */
    internal class SwitchFeatures(
        val threatened: Double,
        /** Its matchup score against the allies, averaged. */
        val stay: Double,
        /** The best bench member's gain over staying, the entry hit taken ([entryGain]). */
        val bestGain: Double,
        val bestIncoming: UUID?,
        /** The best bench member's free matchups over the user's ([switchOption] before its cost), and who that is. */
        val freeGain: Double,
        val freeIncoming: UUID?,
        val preserve: Double,
        val sweep: Double,
        val stop: Double,
        /** Its best attack's value ([attackValue]) against the allies. */
        val bestAttack: Double,
        val hp: Double,
        val bench: Int,
    )

    /** [SwitchFeatures] for each opposing active Pokemon with a seen bench behind it. */
    fun switchFeatures(context: BattleDecisionContext, scores: MatchupScores): Map<UUID, SwitchFeatures> {
        val state = context.state
        val allies = state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot != null && standing(it) }
        if (allies.isEmpty()) return emptyMap()
        val bench = seenBench(state)
        if (bench.isEmpty()) return emptyMap()
        return state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && standing(it) }.associate { user ->
            val stay = allies.mapNotNull { scores.pokemon(user.battlePokemonId, it.battlePokemonId)?.score }.average().takeIf { !it.isNaN() } ?: 0.0
            val gains = bench.mapNotNull { incoming -> entryGain(user, incoming, allies, scores)?.let { incoming.battlePokemonId to it } }
            val best = gains.maxByOrNull { it.second }
            val free = bench.mapNotNull { incoming -> switchOption(user, incoming, allies, scores) }.maxByOrNull { it.value }
            val stop = bench.maxOfOrNull { incoming -> stopCredit(incoming, allies, scores) } ?: 0.0
            val bestAttack = allies.flatMap { ally ->
                scores.moves(user.battlePokemonId, ally.battlePokemonId).map { attackValue(it, ally) }
            }.maxOrNull() ?: 0.0
            user.battlePokemonId to SwitchFeatures(
                threatened = knockedOutBeforeActing(user, allies, scores),
                stay = stay,
                bestGain = best?.second ?: 0.0,
                bestIncoming = best?.first,
                freeGain = free?.let { it.value + SWITCH_COST } ?: 0.0,
                freeIncoming = free?.switchInId,
                preserve = scores.preserves[user.battlePokemonId]?.score ?: 0.0,
                sweep = scores.sweeps[user.battlePokemonId]?.score ?: 0.0,
                stop = stop,
                bestAttack = bestAttack,
                hp = user.hpFraction,
                bench = bench.size,
            )
        }
    }

    /** The chance an ally's best attack knocks [user] out before it moves this turn. */
    private fun knockedOutBeforeActing(user: BattlePokemonStateView, allies: List<BattlePokemonStateView>, scores: MatchupScores): Double =
        1.0 - allies.fold(1.0) { standing, ally ->
            val pair = scores.pokemon(ally.battlePokemonId, user.battlePokemonId) ?: return@fold standing
            val knockout = pair.subjectMove?.knockoutChanceWithin(1) ?: 0.0
            standing * (1.0 - knockout * pair.subjectMovesFirstProbability)
        }

    /** The incoming Pokemon's matchups after the hit it takes on the way in, less the user's, averaged over the allies. */
    private fun entryGain(
        user: BattlePokemonStateView,
        incoming: BattlePokemonStateView,
        allies: List<BattlePokemonStateView>,
        scores: MatchupScores,
    ): Double? = allies.mapNotNull { ally ->
        val stay = scores.pokemon(user.battlePokemonId, ally.battlePokemonId)?.score ?: return@mapNotNull null
        val enter = scores.pokemon(incoming.battlePokemonId, ally.battlePokemonId) ?: return@mapNotNull null
        // The ally attacks the Pokemon it faces; the incoming one takes that move.
        val aimed = scores.pokemon(ally.battlePokemonId, user.battlePokemonId)?.subjectMove?.moveId
        val hit = aimed?.let { id -> scores.moves(ally.battlePokemonId, incoming.battlePokemonId).firstOrNull { it.moveId == id } }
        val survival = 1.0 - (hit?.knockoutChanceWithin(1) ?: 0.0)
        val taken = hit?.let { it.accuracy * (it.minimumDamageFraction + it.maximumDamageFraction) / 2.0 } ?: 0.0
        val afterEntry = survival * (enter.score - ENTRY_DAMAGE_WEIGHT * taken) - (1.0 - survival)
        afterEntry - stay
    }.takeIf { it.isNotEmpty() }?.average()

    /** The incoming Pokemon's [StopScore] against a boosted ally, scaled by how far that ally has set up. */
    private fun stopCredit(incoming: BattlePokemonStateView, allies: List<BattlePokemonStateView>, scores: MatchupScores): Double =
        allies.maxOfOrNull { ally ->
            val boost = ally.statStages.values.filter { it > 0 }.sum()
            if (boost <= 0) return@maxOfOrNull 0.0
            val stopper = scores.stops[incoming.battlePokemonId]?.takeIf { it.sweeperId == ally.battlePokemonId }?.score ?: 0.0
            stopper * minOf(1.0, boost / FULL_BOOST)
        } ?: 0.0

    private fun move(
        option: BattlePublicMoveOptionView,
        user: BattlePokemonStateView,
        allies: List<BattlePokemonStateView>,
        bench: List<BattlePokemonStateView>,
        state: BattleStateView,
        scores: MatchupScores,
    ): List<IntentOption> {
        val options = valued(option, user, allies, state, scores)
        // A pivot also brings in the best Pokemon behind it, without a switch turn of its own.
        val pivots = option.details.effects?.effects.orEmpty().any { it.kind == BattleMoveEffectKind.SWITCH_USER }
        if (!pivots) return options
        val gain = bench.mapNotNull { switchOption(user, it, allies, scores) }.maxOfOrNull { it.value + SWITCH_COST } ?: return options
        return options.map { it.copy(value = it.value + PIVOT_WEIGHT * gain.coerceAtLeast(0.0)) }
    }

    private fun valued(
        option: BattlePublicMoveOptionView,
        user: BattlePokemonStateView,
        allies: List<BattlePokemonStateView>,
        state: BattleStateView,
        scores: MatchupScores,
    ): List<IntentOption> {
        val moveId = PublicIds.canonical(option.moveId)
        val details = option.details
        if (details.currentPp <= 0) return emptyList()
        val effects = details.effects?.effects.orEmpty()
        fun option(kind: IntentKind, value: Double, targetId: UUID? = null) =
            IntentOption(kind, moveId, targetId, null, value, 0.0)
        if (moveId == FAKE_OUT) {
            return listOf(option(IntentKind.ATTACK, if (firstTurnOut(user, state)) FAKE_OUT_VALUE else FAILS))
        }
        if (effects.any { it.kind == BattleMoveEffectKind.PROTECT_USER }) {
            val best = allies.mapNotNull { scores.moves(it.battlePokemonId, user.battlePokemonId).firstOrNull() }
            val alone = 1.0 - best.fold(1.0) { standing, score -> standing * (1.0 - score.knockoutChanceWithin(1)) }
            // Both allies aiming at it: how far their expected hits together reach past its HP.
            val together = best.sumOf { share(it, user) }
            val threat = maxOf(alone, ((together - DOUBLE_TARGET_FROM) / (1.0 - DOUBLE_TARGET_FROM)).coerceIn(0.0, 1.0))
            val value = if (protectedLastTurn(user, state)) FAILS else PROTECT_SCALE * threat + PROTECT_BASE
            return listOf(option(IntentKind.PROTECT, value))
        }
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) {
            val values = allies.mapNotNull { ally ->
                scores.moves(user.battlePokemonId, ally.battlePokemonId).firstOrNull { it.moveId == moveId }?.let { ally to attackValue(it, ally) }
            }
            if (values.isEmpty()) return emptyList()
            // Knock Off is worth the item it takes as well.
            fun itemBonus(ally: BattlePokemonStateView) = if (moveId == KNOCK_OFF && ally.knownHeldItemId != null) KNOCK_OFF_ITEM else 0.0
            return if (details.targetPattern in SPREAD) {
                listOf(option(IntentKind.ATTACK, values.sumOf { it.second }))
            } else {
                values.map { (ally, value) -> option(IntentKind.ATTACK, value + itemBonus(ally), ally.battlePokemonId) }
            }
        }
        scores.sweeps[user.battlePokemonId]?.takeIf { it.setupMoveId == moveId }?.let {
            return listOf(option(IntentKind.SETUP, it.score + SETUP_OFFSET))
        }
        if (effects.any { it.kind == BattleMoveEffectKind.STAT_STAGE } && effects.all { it.target != BattleMoveEffectTarget.SELECTED_TARGET }) {
            // A stat raise that makes no sweeper.
            return listOf(option(IntentKind.SETUP, UNSCORED))
        }
        val scored = allies.mapNotNull { ally ->
            scores.statusMoves(user.battlePokemonId, ally.battlePokemonId).firstOrNull { it.moveId == moveId }?.let { ally to it.score }
        }
        if (scored.isNotEmpty()) return scored.map { (ally, value) -> option(IntentKind.STATUS, value, ally.battlePokemonId) }
        if (moveId == TRICK_ROOM) {
            val pairs = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && standing(it) }.flatMap { mine ->
                allies.mapNotNull { ally ->
                    val now = scores.pokemon(mine.battlePokemonId, ally.battlePokemonId) ?: return@mapNotNull null
                    val toggled = scores.pokemon(mine.battlePokemonId, ally.battlePokemonId, MatchupSpeedField.TRICK_ROOM_TOGGLED) ?: return@mapNotNull null
                    toggled.score - now.score
                }
            }
            return listOf(option(IntentKind.STATUS, if (pairs.isEmpty()) UNSCORED else pairs.average()))
        }
        val sleeps = effects.any { it.kind == BattleMoveEffectKind.STATUS && it.valueId?.let(PublicIds::canonical) == "slp" }
        if (sleeps) {
            val accuracy = (details.accuracy / 100.0).coerceIn(0.0, 1.0).takeIf { it > 0.0 } ?: 1.0
            return allies.map { ally -> option(IntentKind.STATUS, if (ally.statusId == null) SLEEP_VALUE * accuracy else FAILS, ally.battlePokemonId) }
        }
        return listOf(option(IntentKind.STATUS, UNSCORED))
    }

    private fun attackValue(score: MoveMatchupScore, target: BattlePokemonStateView): Double =
        KNOCKOUT_WEIGHT * score.knockoutChanceWithin(1) + DAMAGE_WEIGHT * share(score, target).coerceAtMost(1.0)

    /** The expected share of [target]'s remaining HP one use takes. */
    private fun share(score: MoveMatchupScore, target: BattlePokemonStateView): Double =
        score.accuracy * (score.minimumDamageFraction + score.maximumDamageFraction) / 2.0 / target.hpFraction.coerceAtLeast(MINIMUM_HP)

    private fun switchOption(
        user: BattlePokemonStateView,
        incoming: BattlePokemonStateView,
        allies: List<BattlePokemonStateView>,
        scores: MatchupScores,
    ): IntentOption? {
        val stay = allies.mapNotNull { scores.pokemon(user.battlePokemonId, it.battlePokemonId)?.score }
        val enter = allies.mapNotNull { scores.pokemon(incoming.battlePokemonId, it.battlePokemonId)?.score }
        if (stay.isEmpty() || enter.isEmpty()) return null
        return IntentOption(IntentKind.SWITCH, null, null, incoming.battlePokemonId, enter.average() - stay.average() - SWITCH_COST, 0.0)
    }

    private fun softmax(options: List<IntentOption>): List<IntentOption> {
        if (options.isEmpty()) return options
        val top = options.maxOf { it.value }
        val weights = options.map { exp((it.value - top) / TEMPERATURE) }
        val total = weights.sum()
        return options.zip(weights) { option, weight -> option.copy(probability = weight / total) }.sortedByDescending { it.probability }
    }

    /** Whether [pokemon] has not moved since it last came in. */
    private fun firstTurnOut(pokemon: BattlePokemonStateView, state: BattleStateView): Boolean {
        val own = state.observedEvents.filter { it.actorPokemonId == pokemon.battlePokemonId }
        val entered = own.lastOrNull { it.kind == BattleObservedEventKind.SWITCHED }?.sequence
            ?: return own.none { it.kind == BattleObservedEventKind.MOVE_USED }
        return own.none { it.kind == BattleObservedEventKind.MOVE_USED && it.sequence > entered }
    }

    /** Whether [pokemon]'s last move, used last turn, was a protecting one; a second in a row mostly fails. */
    private fun protectedLastTurn(pokemon: BattlePokemonStateView, state: BattleStateView): Boolean {
        val last = state.observedEvents.lastOrNull { it.actorPokemonId == pokemon.battlePokemonId && it.kind == BattleObservedEventKind.MOVE_USED }
            ?: return false
        return last.turn >= state.turn - 1 && last.publicValueId?.let(PublicIds::canonical) in PROTECTING_MOVES
    }

    private fun standing(pokemon: BattlePokemonStateView) = !pokemon.fainted && pokemon.hpFraction > 0.0

    /**
     * The opponent's bench as far as it has been seen. One not yet seen has no types, moves or stats, so its
     * matchups read as an even trade: a switch into it looked better than staying in any losing matchup, and the
     * predictor called a switch nine turns in ten where the opponent made one in ten.
     */
    private fun seenBench(state: BattleStateView): List<BattlePokemonStateView> = state.pokemon.filter {
        it.side == BattleSide.OPPONENT && it.activeSlot == null && standing(it) && it.knownTypeIds.isNotEmpty() && it.combatStats != null
    }

    const val TEMPERATURE = 0.25
    // The switch model's weights (see switchChance), fitted by LocalSwitchCalibrationTest on 320 Boss self-play
    // games (3081 positions with a seen bench, 8.6% of them switches): Brier 0.070 cross-validated, 0.078 for the
    // base rate alone, and within a few points of the observed rate up to a predicted 0.6.
    private const val SWITCH_BIAS = -1.069
    private const val SWITCH_THREATENED = 0.736
    private const val SWITCH_ENTRY_GAIN = 0.996
    private const val SWITCH_ATTACK = -0.830
    private const val SWITCH_HP = -0.998
    private const val SWITCH_PRESERVE = 0.397
    private const val SWITCH_BENCH = -0.414
    private const val KNOCKOUT_WEIGHT = 0.6
    private const val DAMAGE_WEIGHT = 0.4
    private const val PROTECT_SCALE = 0.8
    private const val PROTECT_BASE = 0.15
    /** The combined expected share of HP from which a double target starts to threaten a knockout. */
    private const val DOUBLE_TARGET_FROM = 0.5
    private const val PIVOT_WEIGHT = 0.5
    private const val KNOCK_OFF_ITEM = 0.15
    private const val KNOCK_OFF = "knockoff"
    private const val SETUP_OFFSET = -0.1
    private const val SWITCH_COST = 0.4
    /** How much of the entry hit counts against the incoming Pokemon's matchup, on top of the exchange's own HP. */
    private const val ENTRY_DAMAGE_WEIGHT = 0.5
    /** Positive stages at which a sweeper counts as fully set up. */
    private const val FULL_BOOST = 2.0
    private const val FAKE_OUT_VALUE = 0.9
    private const val SLEEP_VALUE = 0.6
    private const val UNSCORED = 0.15
    private const val FAILS = -1.0
    private const val MINIMUM_HP = 0.01
    private const val FAKE_OUT = "fakeout"
    private const val TRICK_ROOM = "trickroom"
    private val SPREAD = setOf(BattleMoveTargetPattern.ALL_OPPONENTS, BattleMoveTargetPattern.ALL_ADJACENT, BattleMoveTargetPattern.ALL_ACTIVE)
    private val PROTECTING_MOVES = setOf("protect", "detect", "spikyshield", "kingsshield", "banefulbunker", "silktrap",
        "burningbulwark", "obstruct", "maxguard")
}
