package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.evaluation.LocalPokemonWorth
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
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
 *   `accuracy x (1 - P(the target's hit knocks the user out)) x (mean(bench b){ entry hazards(b) + score(user vs b)
 *    - score(user vs target) } - Toxic count lost) x 100 - P(the user moves first otherwise) x the target's expected hit x 100`
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
    enum class Kind { FORCED_SWITCH, TAUNT, WEATHER }

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
        val gains = bench.mapNotNull { coming ->
            val score = scores.pokemon(user.battlePokemonId, coming.battlePokemonId)?.score ?: return@mapNotNull null
            val hazards = minOf(PublicSwitchEntryHazardCalculator.hpLoss(state, BattleSide.OPPONENT, coming) ?: 0.0, coming.hpFraction)
            hazards + (score - now.score)
        }
        if (gains.isEmpty()) return null
        // Moving last at -6, the target's hit lands first: a knockout there and the move never comes.
        val acts = 1.0 - (now.opponentMove?.knockoutChanceWithin(1) ?: 0.0)
        // Moving last at -6 gives the target its hit first where the user would otherwise have struck first.
        val firstHit = now.subjectMovesFirstProbability * (now.opponentMove?.let(::expectedHit) ?: 0.0)
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
        val revealed = target.knownMoveIds.map(PublicIds::canonical).toSet()
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
                    ?: (healShare(target, deniedMove, context) * tuning.boardToScore)
            }
            option.probability * denial * turns
        }
        return accuracy * denied
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
    private const val INGRAIN = "ingrain"
    private const val SUBSTITUTE = "substitute"
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
