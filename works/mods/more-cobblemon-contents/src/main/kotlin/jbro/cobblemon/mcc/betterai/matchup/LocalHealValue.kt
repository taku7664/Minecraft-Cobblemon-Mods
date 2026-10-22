package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.PublicFutureActionFactory
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * What a self-heal is worth, in score points (one HP bar is 100, a Pokemon 200), singles:
 *
 * `((standing healed - standing now) * 100 + (knockout now - knockout healed) * heals first * standing healed * 100) * (1 - blocked)`
 *
 * - the standing ([LocalKnockoutWorth.standings]): what the user still does against every opposing Pokemon left, its
 *   fights' share at its HP and its next hit; read again on the board with the HP the heal restores. A heal raises the
 *   user's matchups whether it was losing or not: the healthier user is a pivot point for the rest of the battle.
 * - this turn: the foe's hit that would knock the user out at its HP now and not at the healed one, at the chance the heal
 *   comes first (after the foe's hit, the heal changes nothing this turn).
 * - blocked: a Heal Block already on the user, or the opponent's predicted move share times its chance to act, go
 *   before the heal, hit and apply Heal Block. Read from the move's effect, not its name. A faster Psychic Noise made
 *   a 75% Arcanine's Morning Sun fail while it was priced at the restored HP (arena, 2026-10-22).
 *
 * A heal that puts its user to sleep (Rest) restores all of its HP and then sleeps through the turns its move code
 * counts: the foe hits it that many times without a reply, so its healed standing is read at the HP left after those
 * hits (`healed - asleep turns * the foe's expected hit`). It used to give up the best other move's value per turn
 * asleep, a price borrowed from another move (user, 2026-10-21).
 *
 * What the turn gives up (the attack) is the attack's own value, compared in the ranking. Read as the HP restored alone
 * (a 50% heal worth 50 against a knockout's 200), a +4 Defence Corviknight at 23% Brave Birded instead of Roosting and
 * fell to the recoil and the next foe (dev server, 2026-10-20); the knockout's floor of a quarter that made up for it had
 * no reading behind it (user, 2026-10-21).
 */
internal object LocalHealValue {
    /** The user's pure self-heals (status moves restoring a share of its HP), by their value; empty where none applies. */
    fun values(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        scores: MatchupScores,
        intents: List<OpponentIntent> = emptyList(),
    ): Map<String, Double> {
        val state = context.state
        val heals = candidates.filter { candidate ->
            candidate.kind == BattleActionKind.USE_MOVE && candidate.mechanic == null &&
                candidate.moveDetails?.damageCategory == BattleMoveDamageCategory.STATUS &&
                (candidate.facts?.selfHealingFractionRange != null ||
                    jbro.cobblemon.mcc.betterai.mechanics.LocalMoveCode.userSleepTurns(candidate.moveId) != null)
        }
        if (heals.isEmpty()) return emptyMap()
        val foe = state.pokemon.singleOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return emptyMap()
        val now = LocalKnockoutWorth.standings(state, scores)
        val cache = LocalProjectedActionCalculationCache()
        return heals.mapNotNull { heal ->
            val user = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == heal.actorSlot && !it.fainted } ?: return@mapNotNull null
            val sleepTurns = jbro.cobblemon.mcc.betterai.mechanics.LocalMoveCode.userSleepTurns(heal.moveId)
            val restored = if (sleepTurns != null) 1.0 else heal.facts?.selfHealingFractionRange?.let { (it.minimum + it.maximum) / 2.0 }
                ?: return@mapNotNull null
            val asleep = sleepTurns?.let { (it - 1).coerceAtLeast(0) } ?: 0
            val foeHit = scores.pokemon(user.battlePokemonId, foe.battlePokemonId)?.opponentMove
                ?.let { (it.minimumDamageFraction + it.maximumDamageFraction) / 2.0 * it.accuracy } ?: 0.0
            val healedHp = (minOf(1.0, user.hpFraction + restored) - asleep * foeHit).coerceAtLeast(0.0)
            if (healedHp <= user.hpFraction) return@mapNotNull heal.actionId to 0.0
            val board = state.derive(pokemon = state.pokemon.map { if (it.battlePokemonId == user.battlePokemonId) it.copyState(hpFraction = healedHp) else it })
            val healedScores = LocalMatchupScoreCalculator.calculate(context.copy(state = board))
            val healed = LocalKnockoutWorth.standings(board, healedScores)
            val standingNow = now[user.battlePokemonId] ?: return@mapNotNull null
            val standingHealed = healed[user.battlePokemonId] ?: return@mapNotNull null
            val pairNow = scores.pokemon(user.battlePokemonId, foe.battlePokemonId)
            val pairHealed = healedScores.pokemon(user.battlePokemonId, foe.battlePokemonId)
            val knockoutNow = pairNow?.opponentMove?.knockoutChanceWithin(1) ?: 0.0
            val knockoutHealed = pairHealed?.opponentMove?.knockoutChanceWithin(1) ?: 0.0
            val healsFirst = LocalPublicTurnOrder.actsFirstProbability(state, BattleSide.ALLY, heal.actorSlot, heal,
                pairNow?.opponentMove?.priority ?: 0) ?: 0.5
            val blocked = blockChance(heal, user, foe, context, intents, cache)
            val value = ((standingHealed - standingNow) * BOARD_TO_SCORE +
                (knockoutNow - knockoutHealed).coerceAtLeast(0.0) * healsFirst * standingHealed * BOARD_TO_SCORE) * (1.0 - blocked)
            jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record("heal:${heal.actionId}", mapOf("hp" to user.hpFraction, "healedHp" to healedHp, "asleep" to asleep.toDouble(), "foeHit" to foeHit, "standingNow" to standingNow, "standingHealed" to standingHealed, "knockoutNow" to knockoutNow, "knockoutHealed" to knockoutHealed, "healsFirst" to healsFirst, "healBlockChance" to blocked, "value" to value))
            heal.actionId to value
        }.toMap()
    }

    /** A known Heal Block, or one that lands before this heal, prevents the heal's own return. */
    private fun blockChance(
        heal: BattleActionCandidate,
        user: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        context: BattleDecisionContext,
        intents: List<OpponentIntent>,
        cache: LocalProjectedActionCalculationCache,
    ): Double {
        if (user.knownVolatileEffectIds.any { PublicIds.canonical(it) == "healblock" }) return 1.0
        val options = intents.firstOrNull { it.pokemonId == foe.battlePokemonId }?.options ?: return 0.0
        val state = context.state
        val actions = cache.slotActions(state, BattleSide.OPPONENT, context.publicActionCatalog, true) {
            PublicFutureActionFactory.slotActions(state, BattleSide.OPPONENT, context.publicActionCatalog, includeMoveHypotheses = true)
        }
        return actions.asSequence().filter { it.kind == BattleActionKind.USE_MOVE && it.actorSlot == foe.activeSlot }
            .map { jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax.asMaxMove(it, foe) }
            .distinctBy { PublicIds.canonical(it.moveId.orEmpty()) }
            .sumOf { action ->
                val effects = action.moveDetails?.effects?.effects.orEmpty().filter {
                    it.kind == BattleMoveEffectKind.VOLATILE_STATUS && it.target == BattleMoveEffectTarget.SELECTED_TARGET &&
                        PublicIds.canonical(it.valueId.orEmpty()) == "healblock"
                }
                if (effects.isEmpty()) return@sumOf 0.0
                val chance = options.filter { it.moveId != null && PublicIds.canonical(it.moveId) == PublicIds.canonical(action.moveId.orEmpty()) &&
                    (it.targetId == null || it.targetId == user.battlePokemonId) }.sumOf { it.probability }
                if (chance <= 0.0) return@sumOf 0.0
                val calculated = cache.getOrCalculate(state, BattleSide.OPPONENT, action, context.publicActionCatalog) {
                    PublicBattleTacticalCalculator.calculate(context.copy(candidates = listOf(action)), BattleSide.OPPONENT)
                }
                val move = calculated.candidates.single()
                if (LocalPublicMechanicsKernel.projectMove(move, calculated, BattleSide.OPPONENT).publiclyNullified) return@sumOf 0.0
                val ability = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityState
                val ignores = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityMechanics.ignoresTargetAbility(move, foe, user, state)
                val damaging = move.moveDetails?.damageCategory != BattleMoveDamageCategory.STATUS
                if (damaging && (ability.effectiveKnownAbility(state, foe) == "sheerforce" ||
                    !ignores && ability.effectiveKnownAbility(state, user) == "shielddust" ||
                    jbro.cobblemon.mcc.internal.ai.BattleShowdownHeldEffects.shieldsSecondaries(
                        jbro.cobblemon.mcc.betterai.mechanics.LocalPublicItemState.activeItemId(state, user)))) return@sumOf 0.0
                val flags = move.moveDetails?.effects?.mechanicFlags.orEmpty()
                if (user.knownVolatileEffectIds.any { PublicIds.canonical(it) == "substitute" } &&
                    "bypasssub" !in flags && ability.effectiveKnownAbility(state, foe) != "infiltrator") return@sumOf 0.0
                val first = LocalPublicTurnOrder.actsFirstProbability(state, BattleSide.OPPONENT, move, BattleSide.ALLY, heal) ?: 0.5
                val accuracy = LocalPublicAccuracy.probability(move, calculated, BattleSide.OPPONENT)
                val effectChance = 1.0 - effects.fold(1.0) { misses, effect -> misses * (1.0 - (effect.probability ?: 1.0)) }
                val acts = LocalKnockoutWorth.actsThisTurn(foe, state)
                jbro.cobblemon.mcc.betterai.brain.LocalDecisionInternals.record("healBlock:${heal.actionId}:${move.moveId}",
                    mapOf("moveChance" to chance, "first" to first, "accuracy" to accuracy, "effectChance" to effectChance, "acts" to acts))
                chance * first * accuracy * effectChance * acts
            }.coerceIn(0.0, 1.0)
    }

    /** One board unit in ranking points ([jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.boardToScore]). */
    private val BOARD_TO_SCORE get() = jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning.CURRENT.boardToScore
}
