package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.mechanics.LocalStatStageChange
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleSide

/**
 * What a debuff (a status move that only puts a status condition or a stat drop on its target: Will-O-Wisp, Thunder
 * Wave, Metal Sound) is worth: how much it lowers the target's mean one-on-one score against every living Pokemon of
 * this side ([PokemonMatchupScore.score], the target's side of each exchange), at the move's accuracy.
 *
 * The one-on-one exchange with the Pokemon in front alone read a burn on a physical attacker that switches out, or comes
 * back later, as worth nothing. The win chance against the whole team ([SweepScore]) was tried first: a burned Garchomp
 * still beat both of a Rotom team's Pokemon, so the burn read as 2% of a knockout. The exchange score also reads the HP
 * the target leaves its winner with, so a burn that halves what it deals counts even where it still wins. Read from the
 * move data; no move is named. Drops applied raw once read Charm on a Defiant Kingambit as a gain (2026-10-12).
 */
internal object LocalDebuffValue {
    data class Reading(val before: Double, val after: Double, val accuracy: Double) {
        /** What the debuff takes off its target's mean exchange score against this team, at its accuracy. */
        val gain: Double get() = (before - after) * accuracy
    }

    /** Whether a status move only weakens its target: every effect a status condition or a stat drop on it. */
    fun isDebuff(move: BattleActionCandidate): Boolean {
        if (move.kind != BattleActionKind.USE_MOVE) return false
        val details = move.moveDetails ?: return false
        if (details.damageCategory != BattleMoveDamageCategory.STATUS) return false
        // A pivot that weakens on its way out (Parting Shot) is a debuff too; its switch is LocalSwitchRules' credit.
        // Read as no debuff, its drops were worth nothing and only the switch was scored (2026-10-13).
        val effects = details.effects?.effects.orEmpty().filter { it.kind != BattleMoveEffectKind.SWITCH_USER }
        return effects.isNotEmpty() && effects.all { effect ->
            effect.target == BattleMoveEffectTarget.SELECTED_TARGET && (
                effect.kind == BattleMoveEffectKind.STATUS ||
                    effect.kind == BattleMoveEffectKind.STAT_STAGE && effect.statStages.isNotEmpty() && effect.statStages.values.all { it < 0 })
        }
    }

    /**
     * The target's mean exchange score now and with the debuff landed, the rest of the board as it is. Null when it is no
     * debuff, no opponent is in front, its status is one the exchange does not play, or no exchange can be read for it.
     * A status on a target that already has one adds nothing.
     */
    fun read(move: BattleActionCandidate, context: BattleDecisionContext, scores: MatchupScores, recovery: Boolean = false): Reading? {
        if (!isDebuff(move)) return null
        val state = context.state
        val target = state.pokemon.firstOrNull { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
            ?: return null
        val team = state.pokemon.filter { it.side == BattleSide.ALLY && !it.fainted && it.hpFraction > 0.0 }.map { it.battlePokemonId }
        fun exchange(table: MatchupScores): Double? = team.mapNotNull { ally -> table.pokemon(target.battlePokemonId, ally)?.score }
            .takeIf { it.isNotEmpty() }?.average()
        val before = exchange(scores) ?: return null
        val effects = move.moveDetails?.effects?.effects.orEmpty()
        val status = effects.firstNotNullOfOrNull { effect -> effect.valueId?.takeIf { effect.kind == BattleMoveEffectKind.STATUS } }
        // A status the exchange does not play (sleep, freeze, poison) reads as no change, which is not what it is worth:
        // Sleep Powder on a Swampert came to 0 and lost its ranking value to a resisted Sludge Bomb (2026-10-12). No
        // reading; the move keeps the value the ranking gave it.
        // The accuracy every other reader takes: stages, No Guard, a Poison type's sure Toxic.
        val accuracy = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAccuracy.probability(move, context, BattleSide.ALLY).coerceIn(0.0, 1.0)
        val drops = effects.filter { it.kind == BattleMoveEffectKind.STAT_STAGE }
            .flatMap { it.statStages.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, changes) -> changes.sum() }
        val poison = status != null && jbro.cobblemon.mcc.internal.ai.PublicIds.canonical(status) in POISONING
        if (status != null && !poison && drops.isEmpty() &&
            jbro.cobblemon.mcc.internal.ai.PublicIds.canonical(status) !in LocalTurnCostScoreCalculator.playedOutStatuses()) return null
        val user = state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        // A status the target is immune to (a Steel type's poison) is not put on it; a move that ignores the target's
        // ability (Mold Breaker) lands it past an ability's immunity.
        val lands = status != null && target.statusId == null &&
            !jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusImmunity.blocked(state, target, status, user,
                ignoreTargetAbility = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityMechanics.ignoresTargetAbility(move, user, target, state),
                abilityIgnoringMove = jbro.cobblemon.mcc.betterai.mechanics.LocalPublicAbilityMechanics.ignoresTargetAbility(move, user, null, state))
        // Poison and Toxic, with or without drops beside them (Toxic Thread): the exchange that plays each turn's residual
        // out (evolving matchups), for the target's pairs with this team only, before and after (user decision
        // 2026-10-19: Toxic is a debuff). Anything else: the pair table, as the ranking reads it.
        val cache = jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache().also { it.matchupRecovery = recovery }
        val evolving = jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.current().copy(evolvingMatchups = true, matchupRecovery = recovery)
        fun poisonPairs(board: jbro.cobblemon.mcc.internal.ai.BattleStateView): Double? = jbro.cobblemon.mcc.betterai.evaluation.LocalActiveTuning.with(evolving) {
            val subject = board.pokemon.first { it.battlePokemonId == target.battlePokemonId }
            team.mapNotNull { allyId ->
                val ally = board.pokemon.firstOrNull { it.battlePokemonId == allyId } ?: return@mapNotNull null
                val faced = LocalMatchupPosition.face(context.copy(state = board), subject, ally, cache) ?: return@mapNotNull null
                LocalMatchupScoreCalculator.pairMatchup(faced, target.battlePokemonId, allyId, MatchupSpeedField.CURRENT, cache)?.score
            }.takeIf { it.isNotEmpty() }?.average()
        }
        fun exchangeOn(board: jbro.cobblemon.mcc.internal.ai.BattleStateView): Double? = if (poison) poisonPairs(board)
            else LocalMatchupScoreCalculator.calculate(context.copy(state = board), recovery = recovery).takeIf { it.complete }?.let(::exchange)
        val plain = if (poison) poisonPairs(state) ?: return null else before
        if (poison && !lands && drops.isEmpty()) return Reading(plain, plain, accuracy)
        val statused = state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId == target.battlePokemonId && lands) it.copyState(statusId = status) else it
        })
        // The drops resolve as Showdown's boost does (LocalStatStageChange: Contrary, Clear Body, Mirror Armor, Defiant,
        // Competitive...), once per ability the target may have, all alike; the ability is only lent for that.
        val abilities: List<String?> = if (drops.isEmpty() || target.knownAbilityId != null) listOf(null)
            else jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector.publicPossibleAbilities(state, target).ifEmpty { listOf(null) }
        val after = abilities.mapNotNull { ability ->
            val lent = if (ability == null) statused else statused.copyState(pokemon = statused.pokemon.map {
                if (it.battlePokemonId == target.battlePokemonId) it.copyState(knownAbilityId = ability) else it
            })
            val dropped = if (drops.isEmpty()) lent
                else LocalStatStageChange.apply(lent, target.battlePokemonId, user?.battlePokemonId, drops)
            val board = if (ability == null) dropped else dropped.copyState(pokemon = dropped.pokemon.map {
                if (it.battlePokemonId == target.battlePokemonId) it.copyState(knownAbilityId = target.knownAbilityId) else it
            })
            // A Lum or Pecha Berry cures the poison as it lands, before any residual (and Unburden starts then).
            exchangeOn(jbro.cobblemon.mcc.betterai.mechanics.LocalPublicStatusBerry.afterUpdate(board))
        }.takeIf { it.size == abilities.size }?.average() ?: return null
        return Reading(plain, after, accuracy)
    }

    private val POISONING = setOf("psn", "tox")
}
