package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventView
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.PublicSwitchEntryHazardCalculator
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * The status moves the engine used to play out (user decision 2026-10-19: no event-branch search), each by a formula on
 * the matchup table, in the root's units. Read from the move data: a forced switch of the target (Roar, Whirlwind), Taunt
 * put on the target, a weather set. The ranking value of such a move becomes this one.
 *
 * - Forced switch. Return: the target's raises gone, a Pokemon from its bench dragged in through this side's hazards,
 *   likely into a better exchange for the user. Risk: it moves last, so the target's hit lands first and may knock the
 *   user out before it moves; a badly poisoned target comes back with its Toxic count reset.
 *   `accuracy x (1 - P(the target's hit knocks the user out)) x (mean(bench b){ entry hazards(b) + held(b, rest)
 *    - held(target, bench) } - Toxic count lost) x 100 - P(the user moves first otherwise) x the target's expected hit x 100`
 *   held(x, rest) = min(score(user vs x), score(user vs each of rest)): the opponent switches again to whatever is worst
 *   for the user, before the move as after it.
 *   Toxic count lost: the count c it has built, c/16 more each turn it would have stayed (the user's hits to knock it
 *   out), up to its HP.
 * - Taunt. Return: three turns without the target's status moves. Risk: nothing if it only attacks or switches.
 *   `accuracy x sum(revealed status move m) P(m this turn) x denial(m) x turns`; turns are the two after this one and
 *   this one when Taunt goes first. denial: a raise, the user's worth times the win the raise adds the target; a
 *   Protect, the user's hit it would block; a hazard, the layer's worth on this side as the hazard rule reads one; a
 *   heal, the HP it would restore.
 * - Weather. Return and risk alike: the exchanges it changes on both sides.
 *   `mean(ally a x opponent o){ (score with - score without)(a vs o) x min(1, turns / exchange length) } x 100`
 *
 * Exceptions: a Dynamaxed target cannot be forced out nor Taunted; Suction Cups, Guard Dog and Ingrain hold it in;
 * Oblivious, Aroma Veil, a Substitute and a Mental Herb stop Taunt; a move that sets weather and does more (Chilly
 * Reception) is not a weather move here; Cloud Nine and Air Lock cancel weather. Status moves the target has not shown are not counted (no
 * guessed status moves), nor status moves other than raises, Protect, hazards and heals. Unseen bench Pokemon are not read.
 */
internal object LocalStatusMoveValue {
    enum class Kind { FORCED_SWITCH, TAUNT, WEATHER, YAWN }

    data class Reading(val kind: Kind, val value: Double)

    fun kind(move: BattleActionCandidate): Kind? {
        if (move.kind != BattleActionKind.USE_MOVE) return null
        val details = move.moveDetails ?: return null
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) return null
        val effects = details.effects?.effects.orEmpty()
        return when {
            effects.any { it.kind == BattleMoveEffectKind.SWITCH_TARGET && it.target == BattleMoveEffectTarget.SELECTED_TARGET } -> Kind.FORCED_SWITCH
            effects.any { it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                PublicIds.canonical(it.valueId.orEmpty()) == TAUNT } -> Kind.TAUNT
            // A volatile on the target that ends in sleep, read from the move's code (Yawn).
            jbro.cobblemon.mcc.betterai.mechanics.LocalMoveCode.delayedSleepVolatile(move.moveId) != null -> Kind.YAWN
            // A move that only sets the weather: Chilly Reception's switch is worth more than the snow it brings.
            effects.isNotEmpty() && effects.all { it.kind == BattleMoveEffectKind.WEATHER && it.valueId != null } -> Kind.WEATHER
            else -> null
        }
    }

    fun read(
        move: BattleActionCandidate,
        context: BattleDecisionContext,
        scores: MatchupScores,
        intents: List<OpponentIntent>,
        tuning: LocalDecisionTuning,
    ): Reading? {
        val kind = kind(move) ?: return null
        val state = context.state
        val user = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == move.actorSlot && alive(it) } ?: return null
        // Accuracy and evasion stages, No Guard, Compound Eyes and the like, as every other reader takes them.
        val accuracy = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy.probability(move, context, BattleSide.ALLY).coerceIn(0.0, 1.0)
        val value = when (kind) {
            Kind.FORCED_SWITCH -> forcedSwitch(move, context, scores, user, accuracy, tuning)
            Kind.TAUNT -> taunt(move, context, scores, intents, user, accuracy, tuning)
            Kind.WEATHER -> weather(move, context, scores)
            Kind.YAWN -> yawn(move, context, scores, user, accuracy, tuning)
        } ?: return null
        return Reading(kind, value)
    }

    private fun forcedSwitch(
        move: BattleActionCandidate,
        context: BattleDecisionContext,
        scores: MatchupScores,
        user: BattlePokemonStateView,
        accuracy: Double,
        tuning: LocalDecisionTuning,
    ): Double? {
        val state = context.state
        val target = target(move, context) ?: return null
        if (LocalDynamax.active(target) ||
            ability(context, target) in jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector.FORCED_SWITCH_IMMUNITIES ||
            target.knownVolatileEffectIds.any { PublicIds.canonical(it) == INGRAIN }) return 0.0
        val bench = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot == null && alive(it) }
        if (bench.isEmpty()) return 0.0
        val now = scores.pokemon(user.battlePokemonId, target.battlePokemonId) ?: return null
        fun score(pokemon: BattlePokemonStateView) = scores.pokemon(user.battlePokemonId, pokemon.battlePokemonId)?.score
        // The target forced out comes back without its stat stages, as any switch leaves them.
        val cache = jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()
        val reset = state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == target.battlePokemonId) it.copyState(statStages = emptyMap()) else it })
        val returning = reset.pokemon.first { it.battlePokemonId == target.battlePokemonId }
        val targetBack = LocalMatchupPosition.face(context.copy(state = reset), user, returning, cache)
            ?.let { LocalMatchupScoreCalculator.pairMatchup(it, user.battlePokemonId, target.battlePokemonId, MatchupSpeedField.CURRENT, cache)?.score }
            ?: now.score
        // The opponent can switch again, before the move and after it, unless it is held in (a trap, Shadow Tag): in front
        // stays whichever of the one there and the rest of its Pokemon is worst for the user (the target, forced out, is
        // among the rest). Read without it, a Whirlwind was paid for a bad matchup the opponent would leave on its own anyway.
        // Read on each one's own board: a trap on the target (Mean Look, a binding move) ends with the switch, and a trapping
        // ability holds the one dragged in by its own type and item.
        fun canLeave(board: jbro.cobblemon.mcc.internal.ai.BattleStateView) =
            jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory.slotActions(board, BattleSide.OPPONENT, context.publicActionCatalog)
                .any { it.kind == BattleActionKind.SWITCH && it.actorSlot == target.activeSlot }
        fun held(inFront: Double, rest: List<Double>, free: Boolean) = if (!free) inFront else minOf(inFront, rest.minOrNull() ?: inFront)
        val before = held(now.score, bench.mapNotNull(::score), canLeave(state) ||
            jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState.activeItemId(state, target) == SHED_SHELL)
        // A Pokemon dragged or sent in as the switch makes it: a Healing Wish waiting on the slot first, then the hazards'
        // HP, its entry ability (a Trace copying Shadow Tag included).
        fun enter(board: jbro.cobblemon.mcc.internal.ai.BattleStateView, coming: BattlePokemonStateView) =
            jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector.project(board, BattleSide.OPPONENT,
                BattleActionCandidate("drag:${coming.battlePokemonId}", BattleActionKind.SWITCH, actorSlot = target.activeSlot ?: 0,
                    switchPokemonId = coming.battlePokemonId,
                    facts = jbro.cobblemon.mcc.internal.ai.BattleCandidateFactsView(
                        switchEntryHpLossFraction = PublicSwitchEntryHazardCalculator.hpLoss(board, BattleSide.OPPONENT, coming) ?: 0.0)))
        fun standing(board: jbro.cobblemon.mcc.internal.ai.BattleStateView, id: java.util.UUID) =
            board.pokemon.firstOrNull { it.battlePokemonId == id }?.takeIf { !it.fainted && it.hpFraction > 0.0 }
        fun worth(pokemon: BattlePokemonStateView) = if (pokemon.battlePokemonId == target.battlePokemonId) targetBack else score(pokemon)
        // One the hazards knock out on entry: the opponent sends whichever of the rest is worst for the user, through the
        // same hazards, until one stands (the HP each loses on the way in is the user's gain).
        fun replaced(board: jbro.cobblemon.mcc.internal.ai.BattleStateView, fallen: Double): Double =
            board.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot == null && alive(it) }.mapNotNull { next ->
                val worth = worth(next) ?: return@mapNotNull null
                val entered = enter(board, next)
                val after = standing(entered, next.battlePokemonId)
                next.hpFraction - (after?.hpFraction ?: 0.0) + if (after != null) worth else replaced(entered, worth)
            }.minOrNull() ?: fallen
        val gains = bench.mapNotNull { coming ->
            val score = score(coming) ?: return@mapNotNull null
            val dragged = enter(reset, coming)
            val dragging = standing(dragged, coming.battlePokemonId)
            val lost = coming.hpFraction - (dragging?.hpFraction ?: 0.0)
            if (dragging == null) return@mapNotNull lost + replaced(dragged, score) - before
            val rest = bench.filter { it.battlePokemonId != coming.battlePokemonId }.mapNotNull(::score) + targetBack
            // Shed Shell frees it.
            val free = canLeave(dragged) ||
                jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState.activeItemId(dragged, dragging) == SHED_SHELL
            lost + held(score, rest, free) - before
        }
        if (gains.isEmpty()) return null
        // The target's hits as they land on the user this turn: from the move table, not the exchange's, which thins them
        // for the user's flinching attack or reads them as never knocking out a user that heals them off. This turn's
        // threat is the likeliest knockout and the most damage, not the fastest knockout over the exchange (a 30% OHKO
        // move beside a sure 2HKO). A paralysed or sleeping target may not move at all.
        val hits = scores.moves(target.battlePokemonId, user.battlePokemonId)
        val targetActs = LocalKnockoutWorth.actsThisTurn(target, context.state)
        // Moving last at -6, the target's hit lands first: a knockout there and the move never comes.
        val acts = 1.0 - targetActs * (hits.maxOfOrNull { it.knockoutChanceWithin(1) } ?: 0.0)
        // Moving last at -6 gives the target its hit first where the user would otherwise have struck first.
        val firstHit = now.subjectMovesFirstProbability * targetActs * (hits.maxOfOrNull(::expectedHit) ?: 0.0)
        return (accuracy * acts * (gains.average() - toxicCountLost(context, target, now)) - firstHit) * tuning.boardToScore
    }

    /**
     * The Toxic damage a badly poisoned target no longer takes once forced out: its count restarts when it comes back,
     * c/16 less each turn it would have stayed in front of the user (the user's hits to knock it out), up to its HP.
     */
    private fun toxicCountLost(context: BattleDecisionContext, target: BattlePokemonStateView, now: PokemonMatchupScore): Double {
        val count = jbro.cobblemon.mcc.betterai.mechanics.LocalBadPoisonCounter.seed(context.state)[target.battlePokemonId] ?: return 0.0
        if (ability(context, target) in POISON_DAMAGE_IGNORED) return 0.0
        val turns = now.subjectMove?.expectedHitsToKnockout?.takeIf { it.isFinite() }
            ?: (jbro.cobblemon.mcc.betterai.mechanics.LocalBadPoisonCounter.MAXIMUM_BAD_POISON_TURN - count).toDouble()
        return minOf(target.hpFraction, count / 16.0 * turns.coerceAtLeast(1.0))
    }

    private fun taunt(
        move: BattleActionCandidate,
        context: BattleDecisionContext,
        scores: MatchupScores,
        intents: List<OpponentIntent>,
        user: BattlePokemonStateView,
        accuracy: Double,
        tuning: LocalDecisionTuning,
    ): Double? {
        val target = target(move, context) ?: return null
        // Taunt fails or is shaken off: Dynamax, Oblivious or Aroma Veil, a Substitute, a Mental Herb.
        if (LocalDynamax.active(target) || ability(context, target) in TAUNT_BLOCKING_ABILITIES ||
            target.knownVolatileEffectIds.any { PublicIds.canonical(it) == SUBSTITUTE } ||
            jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState.activeItemId(context.state, target) == MENTAL_HERB) return 0.0
        val pair = scores.pokemon(user.battlePokemonId, target.battlePokemonId) ?: return null
        // Shown, or read from the real set by this tier (not a guess).
        val revealed = target.knownMoveIds.map(PublicIds::canonical).toSet() +
            context.publicActionCatalog.inferredMovesForPokemon(target.battlePokemonId)?.slots.orEmpty()
                .filter { it.source == jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveSource.DIFFICULTY_SET_READ }
                .mapNotNull { it.moveId?.let(PublicIds::canonical) }
        val intent = intents.firstOrNull { it.pokemonId == target.battlePokemonId } ?: return 0.0
        val cache = LocalProjectedActionCalculationCache()
        val setups by lazy { LocalMatchupScoreCalculator.setupMoves(context, target) }
        val worth = LocalPokemonWorth.knockoutScore(user.battlePokemonId, tuning)
        val denied = intent.options.filter { option ->
            option.kind in DENIED && option.moveId != null && PublicIds.canonical(option.moveId) in revealed
        }.sumOf { option ->
            val moveId = PublicIds.canonical(requireNotNull(option.moveId))
            // This turn counts only when Taunt goes first, read from the two moves themselves (Prankster, Protect's +4).
            val deniedMove = statusAction(target, option.moveId, context)
            val first = deniedMove?.let {
                jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder.actsFirstProbability(context.state, BattleSide.ALLY, move, BattleSide.OPPONENT, it)
            } ?: pair.subjectMovesFirstProbability
            val turns = LATER_TURNS + first
            val denial = when (option.kind) {
                IntentKind.SETUP -> {
                    val stages = setups[moveId] ?: return@sumOf 0.0
                    val boosted = LocalMatchupScoreCalculator.boostedWin(context, target, user, stages, scores, cache, setupTurn = true)
                        ?: return@sumOf 0.0
                    (boosted - pair.lossProbability).coerceAtLeast(0.0) * worth
                }
                IntentKind.PROTECT -> (pair.subjectMove?.let(::expectedHit) ?: 0.0) * tuning.boardToScore
                // A hazard it would lay on this side: the layer's worth as the hazard rule reads one, mirrored.
                else -> hazardOnThisSide(target, option.moveId, context, scores)
                    ?: phazedEntryLoss(user, deniedMove, context)?.let { it * tuning.boardToScore }
                    ?: (healShare(target, deniedMove, context) * tuning.boardToScore)
            }
            option.probability * denial * turns
        }
        return accuracy * denied
    }

    /**
     * Yawn. Return: at the end of next turn the target falls asleep unless it leaves, so the opponent picks the lesser
     * loss of the two, as it would pick its Pokemon in front ([forcedSwitch]'s `held`). Risk: the turn, the target's hit
     * landing first as any other status move's.
     * ```
     * stays   = the user's matchup against the target asleep (LocalSleepClock: one to three turns from a fresh sleep)
     * leaves  = the user's matchup against the worst for it of the rest (the target's stat stages go with it)
     * before  = the worst for the user of staying and leaving now
     * threat  = the target's mean win against every Pokemon on this side, x their number x a Pokemon's worth: now, asleep,
     *           and back without its stat stages; the opponent keeps the larger of the last two
     * value   = accuracy x P(the user moves at all) x (min(stays, leaves) - before + threat now - threat after) x 100
     * ```
     * Nothing on a target that is asleep or statused already, drowsy, Dynamaxed or kept from sleep (Insomnia, Electric
     * or Misty Terrain, Safeguard). Read as any status move (20), a Torkoal's Yawn lost to Stealth Rock in front of a
     * Mega Gyarados setting up with Dragon Dance (hand-played Flint, 2026-10-20).
     */
    private fun yawn(
        move: BattleActionCandidate,
        context: BattleDecisionContext,
        scores: MatchupScores,
        user: BattlePokemonStateView,
        accuracy: Double,
        tuning: LocalDecisionTuning,
    ): Double? {
        val state = context.state
        val target = target(move, context) ?: return null
        if (LocalDynamax.active(target) || target.statusId != null ||
            target.knownVolatileEffectIds.any { PublicIds.canonical(it) in jbro.cobblemon.mcc.betterai.mechanics.LocalMoveCode.delayedSleepVolatiles ||
                PublicIds.canonical(it) == SUBSTITUTE } ||
            jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity.blocked(state, target, SLEEP, user)) return 0.0
        val now = scores.pokemon(user.battlePokemonId, target.battlePokemonId) ?: return null
        val bench = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot == null && alive(it) }
        fun score(pokemon: BattlePokemonStateView) = scores.pokemon(user.battlePokemonId, pokemon.battlePokemonId)?.score
        val rest = bench.mapNotNull(::score)
        val before = minOf(now.score, rest.minOrNull() ?: now.score)
        // The target asleep from a sleep just seen starting, so the table delays its hits by the turns it sleeps.
        val fell = BattleObservedEventView(
            sequence = (state.observedEvents.maxOfOrNull { it.sequence } ?: 0L) + 1L, turn = state.turn,
            kind = BattleObservedEventKind.STATUS_CHANGED, actorPokemonId = target.battlePokemonId, publicValueId = SLEEP,
        )
        val asleepState = jbro.cobblemon.mcc.internal.ai.BattleStateView(state.battleId, state.format, state.turn,
            state.pokemon.map { if (it.battlePokemonId == target.battlePokemonId) it.copyState(statusId = SLEEP) else it },
            state.field, state.remainingPokemonBySide, state.observedEvents + fell, state.inferences)
        val cache = jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache()
        val asleepContext = context.copy(state = asleepState)
        val sleeper = asleepState.pokemon.first { it.battlePokemonId == target.battlePokemonId }
        val stays = LocalMatchupPosition.face(asleepContext, user, sleeper, cache)
            ?.let { LocalMatchupScoreCalculator.pairMatchup(it, user.battlePokemonId, target.battlePokemonId, MatchupSpeedField.CURRENT, cache)?.score }
            ?: return null
        // It can leave for one not yet seen too: that one's matchup is unknown, read as the target's own.
        val unseen = (state.remainingPokemonBySide[BattleSide.OPPONENT] ?: 0) -
            state.pokemon.count { it.side == BattleSide.OPPONENT && alive(it) } > 0
        val leaves = listOfNotNull(rest.minOrNull(), if (unseen) now.score else null).minOrNull()
        val after = if (leaves == null) stays else minOf(stays, leaves)
        // What the target threatens on this whole side: a boosted sweeper's turn of Dragon Dance is a threat to every
        // Pokemon here, not to the one in front alone.
        val side = state.pokemon.filter { it.side == BattleSide.ALLY && alive(it) }
        val reset = state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == target.battlePokemonId) it.copyState(statStages = emptyMap()) else it })
        fun threat(board: BattleDecisionContext): Double? = side.mapNotNull { ally ->
            val foe = board.state.pokemon.first { it.battlePokemonId == target.battlePokemonId }
            val mine = board.state.pokemon.first { it.battlePokemonId == ally.battlePokemonId }
            LocalMatchupPosition.face(board, mine, foe, cache)
                ?.let { LocalMatchupScoreCalculator.pairMatchup(it, ally.battlePokemonId, target.battlePokemonId, MatchupSpeedField.CURRENT, cache)?.lossProbability }
        }.takeIf { it.isNotEmpty() }?.average()
        val threatNow = threat(context)
        val threatAfter = listOfNotNull(threat(asleepContext), if (leaves == null) null else threat(context.copy(state = reset))).maxOrNull()
        val sideGain = if (threatNow == null || threatAfter == null) 0.0
            else (threatNow - threatAfter).coerceAtLeast(0.0) * side.size * tuning.livingPokemonValue
        // Its hit first: a knockout there and Yawn never comes.
        val hits = scores.moves(target.battlePokemonId, user.battlePokemonId)
        val acts = 1.0 - (1.0 - now.subjectMovesFirstProbability) * LocalKnockoutWorth.actsThisTurn(target, state) *
            (hits.maxOfOrNull { it.knockoutChanceWithin(1) } ?: 0.0)
        return accuracy * acts * (after - before + sideGain) * tuning.boardToScore
    }

    private fun weather(move: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores): Double? {
        val state = context.state
        val id = move.moveDetails?.effects?.effects.orEmpty().firstNotNullOfOrNull { effect ->
            effect.valueId?.takeIf { effect.kind == BattleMoveEffectKind.WEATHER }
        }?.let(PublicIds::canonical) ?: return null
        if (state.pokemon.any { it.activeSlot != null && alive(it) && ability(context, it) in WEATHER_CANCELLING }) return 0.0
        val field = state.field
        val board = state.derive(field = BattleFieldStateView(BattleTimedEffectView(id, WEATHER_TURNS), field.terrain,
            field.roomEffects, field.globalEffects, field.sideConditions))
        // Within the decision's time, as the rules' own table is; an unfinished table gives no reading.
        val with = LocalMatchupScoreCalculator.calculate(context.copy(state = board),
            shouldContinue = { System.currentTimeMillis() < context.deadlineEpochMillis }).takeIf { it.complete } ?: return null
        val allies = state.pokemon.filter { it.side == BattleSide.ALLY && alive(it) }
        val opponents = state.pokemon.filter { it.side == BattleSide.OPPONENT && alive(it) }
        val changes = allies.flatMap { ally ->
            opponents.mapNotNull { opponent ->
                val without = scores.pokemon(ally.battlePokemonId, opponent.battlePokemonId) ?: return@mapNotNull null
                val raining = with.pokemon(ally.battlePokemonId, opponent.battlePokemonId) ?: return@mapNotNull null
                // The weather lasts its turns; an exchange that runs longer feels it only that share of the way.
                val length = minOf(without.subjectMove?.expectedHitsToKnockout ?: EXCHANGE_CAP,
                    without.opponentMove?.expectedHitsToKnockout ?: EXCHANGE_CAP, EXCHANGE_CAP).coerceAtLeast(1.0)
                (raining.score - without.score) * minOf(1.0, WEATHER_TURNS / length)
            }
        }
        return changes.takeIf { it.isNotEmpty() }?.average()?.times(BOARD_TO_SCORE)
    }

    /** [target]'s revealed status move [moveId] as an action, or null when it has not shown it. */
    private fun statusAction(target: BattlePokemonStateView, moveId: String?, context: BattleDecisionContext): BattleActionCandidate? {
        val details = context.publicActionCatalog.forPokemon(target.battlePokemonId)
            .firstOrNull { PublicIds.canonical(it.moveId) == PublicIds.canonical(moveId.orEmpty()) }?.details ?: return null
        return BattleActionCandidate(actionId = "taunt:denied", kind = BattleActionKind.USE_MOVE,
            actorSlot = target.activeSlot ?: 0, moveSlot = 0, moveId = moveId, moveDetails = details)
    }

    /** The worth of the hazard layer [moveId] would lay on this side, or null when it lays none. */
    private fun hazardOnThisSide(target: BattlePokemonStateView, moveId: String?, context: BattleDecisionContext, scores: MatchupScores): Double? {
        val move = statusAction(target, moveId, context) ?: return null
        val id = LocalHazardEncoreCredit.hazardId(move) ?: return null
        return LocalHazardEncoreCredit.layerValue(id, context, scores, BattleSide.ALLY) ?: 0.0
    }

    /**
     * A move that forces the user out (Whirlwind, Roar), as the denied move: the HP the Pokemon dragged in (any of the
     * rest, evenly) loses to the hazards on this side. Null when it forces no switch; 0 when the user cannot be forced
     * out or none is left to drag in. Read as no heal, a Hippowdon's shown Whirlwind into three layers of Spikes left
     * Aerodactyl's Taunt at 0 (hand-played Roark, 2026-10-20). The user's own stat stages lost are not read.
     */
    private fun phazedEntryLoss(user: BattlePokemonStateView, move: BattleActionCandidate?, context: BattleDecisionContext): Double? {
        val effects = move?.moveDetails?.effects?.effects.orEmpty()
        if (effects.none { it.kind == BattleMoveEffectKind.SWITCH_TARGET && it.target == BattleMoveEffectTarget.SELECTED_TARGET }) return null
        val state = context.state
        if (LocalDynamax.active(user) ||
            ability(context, user) in jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector.FORCED_SWITCH_IMMUNITIES ||
            user.knownVolatileEffectIds.any { PublicIds.canonical(it) == INGRAIN }) return 0.0
        val bench = state.pokemon.filter { it.side == BattleSide.ALLY && it.activeSlot == null && alive(it) }
        if (bench.isEmpty()) return 0.0
        return bench.map { minOf(it.hpFraction, PublicSwitchEntryHazardCalculator.hpLoss(state, BattleSide.ALLY, it) ?: 0.0) }.average()
    }

    /**
     * The share of maximum HP a heal of its user restores, up to what it has lost, as the calculator resolves it: the
     * weather's share for Synthesis, the target's Attack for Strength Sap.
     */
    private fun healShare(target: BattlePokemonStateView, move: BattleActionCandidate?, context: BattleDecisionContext): Double {
        move ?: return 0.0
        val calculated = jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator.calculate(
            context.copy(candidates = listOf(move)), BattleSide.OPPONENT)
        val heal = calculated.candidates.singleOrNull()?.facts?.selfHealingFractionRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0
        return minOf(heal, 1.0 - target.hpFraction)
    }

    private fun target(move: BattleActionCandidate, context: BattleDecisionContext): BattlePokemonStateView? {
        val slot = move.targets.firstOrNull { it.side == BattleSide.OPPONENT }?.slot
        return context.state.pokemon.firstOrNull {
            it.side == BattleSide.OPPONENT && it.activeSlot != null && alive(it) && (slot == null || it.activeSlot == slot)
        }
    }

    private fun expectedHit(move: MoveMatchupScore): Double =
        move.accuracy * (move.minimumDamageFraction + move.maximumDamageFraction) / 2.0

    private fun ability(context: BattleDecisionContext, pokemon: BattlePokemonStateView): String? =
        jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState.effectiveKnownAbility(context.state, pokemon)?.let(PublicIds::canonical)

    private fun alive(pokemon: BattlePokemonStateView) = !pokemon.fainted && pokemon.hpFraction > 0.0

    private const val TAUNT = "taunt"
    private const val SLEEP = "slp"
    private const val INGRAIN = "ingrain"
    private const val SUBSTITUTE = "substitute"
    private const val SHED_SHELL = "shedshell"
    /** No damage from poison: Magic Guard takes none, Poison Heal heals instead. */
    private val POISON_DAMAGE_IGNORED = setOf("magicguard", "poisonheal")
    private const val MENTAL_HERB = "mentalherb"
    private val TAUNT_BLOCKING_ABILITIES = setOf("oblivious", "aromaveil")
    private val WEATHER_CANCELLING = setOf("cloudnine", "airlock")
    private val DENIED = setOf(IntentKind.SETUP, IntentKind.PROTECT, IntentKind.STATUS)
    /** Taunt lasts three turns: the two after this one, and this one when it goes first. */
    private const val LATER_TURNS = 2.0
    private const val WEATHER_TURNS = 5
    private const val EXCHANGE_CAP = 6.0
    private const val BOARD_TO_SCORE = 100.0
}
