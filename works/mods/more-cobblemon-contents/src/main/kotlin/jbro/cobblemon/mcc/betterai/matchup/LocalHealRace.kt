package jbro.cobblemon.mcc.betterai.matchup

import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.mechanics.LocalPublicTurnOrder
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicIds

/**
 * The race between a hit and a heal, in the root's units: whether an attack gets through a healer it faces, and
 * whether a heal of our own gets anywhere.
 *
 * The root credits an attack for the HP it takes this turn and a heal for the HP it restores, each as if the other
 * side did nothing. Against a known self-heal that undoes the hit, the attack takes nothing over two turns: Fire
 * Blast into a Roosting Skarmory was credited every turn it was erased. And a heal that the opponent's hit undoes, or
 * that still leaves the user to its next hit, was credited as HP kept. Played out, both are loops that go nowhere.
 *
 * Only what is known counts: an opponent's heal only once it has been used in front of us (a publicly revealed
 * move), and the hits are the matchup table's.
 *
 * - An attack on a healer: when its expected hit is no more than one heal and the healer is in a loop already (it
 *   healed last turn and ended no lower, [LocalRecoveryLoop.healedOffStreak]), the damage credit is taken back
 *   ([LocalDecisionTuning.healRaceWeight] of it). A heal that is merely possible is no loop: played out, Fire Blast
 *   into a full-HP Skarmory that could Roost it off was still the best move (+1.54), Skarmory attacked instead, and
 *   a Roost costs it the turn. When the healer cannot heal out of reach (after this hit and a heal, the next first
 *   hit still knocks it out), the next turn's knockout is credited instead.
 * - A heal of our own: when an opponent's best hit on us is at least the heal, or knocks us out from the healed HP,
 *   the heal's credit is taken back, by the same weight.
 */
internal object LocalHealRace {
    fun adjustments(
        candidates: List<BattleActionCandidate>,
        context: BattleDecisionContext,
        scores: MatchupScores,
        tuning: LocalDecisionTuning,
    ): Map<String, Double> {
        val weight = tuning.healRaceWeight
        if (weight <= 0.0) return emptyMap()
        val state = context.state
        val opponents = state.pokemon.filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && standing(it) }
        if (opponents.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, Double>()
        for (candidate in candidates) {
            val parts = if (candidate.kind == BattleActionKind.COMPOSITE) candidate.componentActions else listOf(candidate)
            var change = 0.0
            for (part in parts) {
                if (part.kind != BattleActionKind.USE_MOVE) continue
                val user = state.pokemon.firstOrNull {
                    it.side == BattleSide.ALLY && it.activeSlot == part.actorSlot && standing(it)
                } ?: continue
                val details = part.moveDetails ?: continue
                val moveId = PublicIds.canonical(part.moveId ?: continue)
                if (details.damageCategory != BattleMoveDamageCategory.STATUS) {
                    val target = target(part, state, opponents) ?: continue
                    change += attack(user, target, moveId, context, scores, tuning)
                } else if (selfHeal(details) > 0.0) {
                    change += heal(user, selfHeal(details), opponents, context, scores, tuning)
                }
            }
            if (change != 0.0) out[candidate.actionId] = change * weight
        }
        return out
    }

    /** The attack's change against a healer it faces; zero when the target has no known heal. */
    private fun attack(
        user: BattlePokemonStateView,
        target: BattlePokemonStateView,
        moveId: String,
        context: BattleDecisionContext,
        scores: MatchupScores,
        tuning: LocalDecisionTuning,
    ): Double {
        val heal = context.publicActionCatalog.forPokemon(target.battlePokemonId)
            .filter { it.knowledge == BattlePublicMoveKnowledge.PUBLICLY_REVEALED && it.details.currentPp > 0 }
            .maxOfOrNull { selfHeal(it.details) } ?: 0.0
        if (heal <= 0.0) return 0.0
        val hit = scores.moves(user.battlePokemonId, target.battlePokemonId).firstOrNull { it.moveId == moveId } ?: return 0.0
        val knockoutNow = hit.knockoutChanceWithin(1)
        if (knockoutNow >= KNOCKOUT_NOW) return 0.0
        val expected = hit.accuracy * (hit.minimumDamageFraction + hit.maximumDamageFraction) / 2.0
        if (expected <= heal) {
            // The heal erases it, and it has been doing so: no progress, whatever this turn's credit said.
            if (jbro.cobblemon.mcc.betterai.evaluation.LocalRecoveryLoop.healedOffStreak(target.battlePokemonId, context) == 0) return 0.0
            return -(1.0 - knockoutNow) * tuning.board(minOf(expected, target.hpFraction))
        }
        // After this hit and a heal, can the next hit, moving first, still knock it out?
        val healed = minOf(1.0, (target.hpFraction - expected).coerceAtLeast(0.0) + heal)
        val reach = rollChance(hit.minimumDamageFraction, hit.maximumDamageFraction, healed) * hit.accuracy
        val first = LocalPublicTurnOrder.speedOrderProbability(context.state, user, target) ?: 0.5
        return (1.0 - knockoutNow) * reach * first * tuning.knockoutMaterialScore * NEXT_TURN_SHARE
    }

    /** A heal of our own against the hits it faces; negative when it goes nowhere. */
    private fun heal(
        user: BattlePokemonStateView,
        heal: Double,
        opponents: List<BattlePokemonStateView>,
        context: BattleDecisionContext,
        scores: MatchupScores,
        tuning: LocalDecisionTuning,
    ): Double {
        val restored = minOf(heal, 1.0 - user.hpFraction)
        if (restored <= 0.0) return 0.0
        val healed = minOf(1.0, user.hpFraction + heal)
        // The worst of what the opponents in front do to it: undo the heal, or knock it out from the healed HP.
        val futile = opponents.maxOfOrNull { opponent ->
            val best = scores.moves(opponent.battlePokemonId, user.battlePokemonId).maxByOrNull {
                it.accuracy * (it.minimumDamageFraction + it.maximumDamageFraction)
            } ?: return@maxOfOrNull 0.0
            val expected = best.accuracy * (best.minimumDamageFraction + best.maximumDamageFraction) / 2.0
            val knockout = rollChance(best.minimumDamageFraction, best.maximumDamageFraction, healed) * best.accuracy
            maxOf(knockout, if (expected >= restored) 1.0 else 0.0)
        } ?: 0.0
        return -futile * tuning.board(restored)
    }

    /** The share of rolls between [minimum] and [maximum] (spread evenly) that reach [hp]. */
    private fun rollChance(minimum: Double, maximum: Double, hp: Double): Double = when {
        maximum < hp -> 0.0
        minimum >= hp || maximum <= minimum -> 1.0
        else -> (maximum - hp) / (maximum - minimum)
    }

    private fun selfHeal(details: BattleMoveCandidateView): Double {
        val effects = details.effects?.effects.orEmpty()
        // Rest trades the turns asleep for the heal; it is not a heal the race reads.
        if (effects.any { it.kind == BattleMoveEffectKind.STATUS && it.target == BattleMoveEffectTarget.USER }) return 0.0
        return effects.filter {
            it.kind == BattleMoveEffectKind.HEAL_FRACTION && it.target == BattleMoveEffectTarget.USER && (it.probability ?: 1.0) >= 1.0
        }.maxOfOrNull { effect -> effect.fractionRange?.let { (it.minimum + it.maximum) / 2.0 } ?: 0.0 } ?: 0.0
    }

    private fun target(
        part: BattleActionCandidate,
        state: jbro.cobblemon.mcc.internal.ai.BattleStateView,
        opponents: List<BattlePokemonStateView>,
    ): BattlePokemonStateView? {
        if (state.format == BattleFormat.SINGLE) return opponents.singleOrNull()
        val slot = part.targets.singleOrNull()?.takeIf { it.side == BattleSide.OPPONENT } ?: return null
        return opponents.firstOrNull { it.activeSlot == slot.slot }
    }

    private fun standing(pokemon: BattlePokemonStateView) = !pokemon.fainted && pokemon.hpFraction > 0.0

    /** A likely knockout this turn needs no race. */
    private const val KNOCKOUT_NOW = 0.5
    /** The next turn's knockout is a turn away and the opponent may act otherwise: half of it. */
    private const val NEXT_TURN_SHARE = 0.5
}
