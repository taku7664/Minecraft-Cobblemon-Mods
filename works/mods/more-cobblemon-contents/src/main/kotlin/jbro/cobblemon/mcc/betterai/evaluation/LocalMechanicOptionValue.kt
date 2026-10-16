package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import jbro.cobblemon.mcc.betterai.mechanics.LocalProjectedActionCalculationCache
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.matchup.LocalAceScore
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupPosition
import jbro.cobblemon.mcc.betterai.matchup.LocalMatchupScoreCalculator
import jbro.cobblemon.mcc.betterai.matchup.MatchupSpeedField

/**
 * What spending a once-per-battle mechanic now gives up, in score units (100 = one HP bar).
 *
 * Replaces the old flat -25 on every mechanic candidate, which priced a last-Pokemon Tera the same
 * as a lead burning the Tera the ace needed, and charged a free Mega Evolution for nothing.
 *
 * - Mega Evolution costs nothing: usually one Pokemon can use it, so no later use is lost. Delaying
 *   it only happens when this turn's projection shows the new typing or ability is worse.
 * - Terastallization costs the best Tera gain another living ally could still get against the
 *   visible opponents, weighted by their threat, times the chance that ally gets to use it.
 * - Dynamax costs the best Dynamax share another living ally that can Dynamax still has, times the chance that ally
 *   gets to use it, at [DYNAMAX_SCALE]. An ally's share is the larger of its Dynamax weight (how much Dynamaxing
 *   raises its matchup win chance against the visible opponents: doubled HP for three turns, Max Moves) and its
 *   standing as the team's ace ([LocalAceScore.matchup], its fresh-board win chance against them), both weighted by
 *   the opponents' threat. Only allies that can and may Dynamax count (a managed team may allow some only), and not
 *   one its trainer's order puts after the actor (it gets the Dynamax only once the actor is gone). It leans
 *   toward the ally with the largest share as Tera leans toward its carrier (user decisions, 2026-10-15/16; it
 *   replaced a flat share per healthy ally, and the ace part was added after a lead kept taking it from the ace).
 * - A Z-Move and any other one-shot mechanic cost a flat share per healthy ally left to use it.
 *
 * The cost is scaled by tier (an Introductory trainer spends on impulse) and by personality (a
 * cautious trainer hoards, an aggressive one spends early). A last Pokemon pays nothing.
 *
 * Tera also leans toward a carrier: the living ally whose Tera does the most against every opponent
 * seen so far. The carrier's Tera costs less and everyone else's costs more, by at most 30%, scaled
 * by tier and plan persistence. It is a tilt, not a plan: a large gain this turn still wins, and when
 * the best two allies are close there is no carrier at all.
 */
internal object LocalMechanicOptionValue {
    fun cost(
        candidate: BattleActionCandidate,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
    ): Double {
        val primitive = if (candidate.kind == BattleActionKind.COMPOSITE) {
            candidate.componentActions.firstOrNull { it.mechanic != null }
        } else {
            candidate
        } ?: return 0.0
        val mechanic = primitive.mechanic ?: return 0.0
        val tierScale = when (profile.difficulty.tier) {
            BattleTrainerTier.INTRODUCTORY -> 0.0
            BattleTrainerTier.STANDARD -> 0.5
            BattleTrainerTier.ADVANCED, BattleTrainerTier.BOSS -> 1.0
        }
        if (tierScale == 0.0) return 0.0
        val personality = (1.0 + (profile.personality.caution - profile.personality.aggression) * 0.5)
            .coerceIn(0.5, 1.5)
        val state = context.state
        val actor = state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot == primitive.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return 0.0
        val others = state.pokemon.filter {
            it.side == BattleSide.ALLY && it.battlePokemonId != actor.battlePokemonId && !it.fainted && it.hpFraction > 0.0
        }
        if (others.isEmpty()) return 0.0
        val base = when (kind(mechanic.mechanicId)) {
            MEGA -> 0.0
            TERA -> teraOptionValue(others, context) * carrierTilt(actor, others, profile, tierScale) { teraFit(it, context) }
            DYNAMAX -> {
                // One its trainer puts after the actor gets it only once the actor is gone (ManagedDynamax's order),
                // so the actor spending it takes nothing from that one's turn.
                val actorRank = dynamaxRank(actor, context)
                val able = others.filter { dynamaxAllowed(it, context) && dynamaxRank(it, context) <= actorRank }
                if (able.isEmpty()) 0.0 else {
                    val fits = dynamaxFits(context)
                    optionValue(able, context, DYNAMAX_SCALE) { fits[it.battlePokemonId] ?: 0.0 } *
                        carrierTilt(actor, able, profile, tierScale) { fits[it.battlePokemonId] ?: 0.0 }
                }
            }
            // A Z-Move and any other one-shot mechanic: a flat share per healthy ally left to use it.
            else -> {
                val healthy = others.count { it.hpFraction >= 0.5 }
                ONE_SHOT_SCALE * healthy / (healthy + 1.0)
            }
        }
        return base * tierScale * personality
    }

    /** Best Tera gain another ally could still realize, times its chance to get the turn to do it. */
    private fun teraOptionValue(others: List<BattlePokemonStateView>, context: BattleDecisionContext): Double =
        optionValue(others, context, TERA_SCALE) { teraFit(it, context) }

    /** Best [fit] another ally could still realize, times its chance to get the turn to do it, in [scale] units. */
    private fun optionValue(
        others: List<BattlePokemonStateView>,
        context: BattleDecisionContext,
        scale: Double,
        fit: (BattlePokemonStateView) -> Double,
    ): Double {
        if (context.state.pokemon.none { it.side == BattleSide.OPPONENT && !it.fainted && it.hpFraction > 0.0 }) return 0.0
        val bestGain = others.maxOf(fit)
        val remainingOthers = others.size
        var availability = remainingOthers / (remainingOthers + 1.0)
        if (context.state.remainingPokemonBySide.getValue(BattleSide.OPPONENT) <= 1) availability *= 0.5
        return bestGain * availability * scale
    }

    /**
     * Multiplier on a mechanic's cost from the carrier lean: below one when [actor] is the clear carrier by [fit],
     * above one when another ally is, one when no ally stands out.
     */
    private fun carrierTilt(
        actor: BattlePokemonStateView,
        others: List<BattlePokemonStateView>,
        profile: BattleTrainerProfile,
        tierScale: Double,
        fit: (BattlePokemonStateView) -> Double,
    ): Double {
        val strength = tierScale * profile.personality.planPersistence
        if (strength <= 0.0) return 1.0
        val fits = (others + actor).associate { it.battlePokemonId to fit(it) }
        val ranked = fits.entries.sortedByDescending { it.value }
        if (ranked.size < 2 || ranked[0].value - ranked[1].value < CARRIER_MARGIN) return 1.0
        val lean = CARRIER_TILT * strength
        return if (ranked[0].key == actor.battlePokemonId) 1.0 - lean else 1.0 + lean
    }

    /**
     * Every ally's Dynamax share, 0..1: the larger of its Dynamax weight ([dynamaxWeights]) and its standing as the
     * ace ([LocalAceScore.matchup]), so an ace keeps it whether or not Dynamax itself adds much to its matchups (user
     * decision, 2026-10-16: the mean halved the hold-back and a lead still took it). Worked out once per decision.
     */
    internal fun dynamaxFits(context: BattleDecisionContext): Map<java.util.UUID, Double> {
        synchronized(dynamaxFitMemo) { dynamaxFitMemo[context]?.let { return it } }
        val weights = dynamaxWeights(context)
        val threat = LocalOpponentThreat.weights(context, BattleTrainerTier.STANDARD)
        val ace = LocalAceScore.matchup(context, threat)
        val fits = weights.mapValues { (id, weight) ->
            // One that cannot or may not Dynamax keeps nothing back, whatever kind of ace it is.
            if (context.state.pokemon.firstOrNull { it.battlePokemonId == id }?.let { dynamaxAllowed(it, context) } == false) 0.0
            else maxOf(weight, ace[id] ?: 0.0)
        }
        synchronized(dynamaxFitMemo) { dynamaxFitMemo[context] = fits }
        return fits
    }

    /** Whether [pokemon] can Dynamax (its species and form) and its trainer lets it (ManagedDynamax, via the build). */
    private fun dynamaxAllowed(pokemon: BattlePokemonStateView, context: BattleDecisionContext): Boolean =
        LocalDynamax.canDynamax(pokemon) && context.exactOwnTeam?.buildFor(pokemon.battlePokemonId)?.dynamaxAllowed != false

    /** [pokemon]'s place in its trainer's Dynamax order; members without one come after every numbered one. */
    private fun dynamaxRank(pokemon: BattlePokemonStateView, context: BattleDecisionContext): Int =
        context.exactOwnTeam?.buildFor(pokemon.battlePokemonId)?.dynamaxPriority ?: Int.MAX_VALUE

    /** Every ally's standing as the ace ([LocalAceScore.matchup]); for the measurement trace. */
    internal fun dynamaxAce(context: BattleDecisionContext): Map<java.util.UUID, Double> =
        LocalAceScore.matchup(context, LocalOpponentThreat.weights(context, BattleTrainerTier.STANDARD))

    /**
     * Every ally's Dynamax weight, 0..1: how much Dynamaxing raises its one-on-one win chance against each visible
     * living opponent (doubled HP for three turns and Max Moves, as the matchup calculation reads a Dynamaxed Pokemon),
     * weighted by the opponents' threat.
     */
    internal fun dynamaxWeights(context: BattleDecisionContext): Map<java.util.UUID, Double> {
        val foes = context.state.pokemon.filter { it.side == BattleSide.OPPONENT && !it.fainted && it.hpFraction > 0.0 }
        val allies = context.state.pokemon.filter { it.side == BattleSide.ALLY && !it.fainted && it.hpFraction > 0.0 }
        val fits = if (foes.isEmpty()) emptyMap() else {
            val weights = LocalOpponentThreat.weights(context, BattleTrainerTier.STANDARD)
            val weightTotal = foes.sumOf { weights[it.battlePokemonId] ?: 1.0 }
            val cache = LocalProjectedActionCalculationCache()
            allies.associate { ally ->
                ally.battlePokemonId to if (!LocalDynamax.canDynamax(ally)) 0.0 else
                    foes.sumOf { foe -> (weights[foe.battlePokemonId] ?: 1.0) * dynamaxGain(ally, foe, context, cache) } / weightTotal
            }
        }
        return fits
    }

    private val dynamaxFitMemo = java.util.WeakHashMap<BattleDecisionContext, Map<java.util.UUID, Double>>()

    /** 0..1: how much Dynamaxing [ally] raises its win chance against [foe], both on the field as the matchups put them. */
    private fun dynamaxGain(
        ally: BattlePokemonStateView,
        foe: BattlePokemonStateView,
        context: BattleDecisionContext,
        cache: LocalProjectedActionCalculationCache,
    ): Double {
        val position = LocalMatchupPosition.face(context, ally, foe, cache) ?: return 0.0
        val plain = LocalMatchupScoreCalculator.pairMatchup(position, ally.battlePokemonId, foe.battlePokemonId,
            MatchupSpeedField.CURRENT, cache)?.winProbability ?: return 0.0
        val dynamaxed = position.copy(state = position.state.copyState(pokemon = position.state.pokemon.map {
            if (it.battlePokemonId != ally.battlePokemonId) it
            else LocalDynamax.start(it, context.exactOwnTeam?.buildFor(it.battlePokemonId)?.gigantamax == true || LocalDynamax.gigantamax(it))
        }))
        val boosted = LocalMatchupScoreCalculator.pairMatchup(dynamaxed, ally.battlePokemonId, foe.battlePokemonId,
            MatchupSpeedField.CURRENT, cache)?.winProbability ?: return 0.0
        return (boosted - plain).coerceIn(0.0, 1.0)
    }

    /** Threat-weighted Tera gain of [ally] against every visible living opponent, 0 without a Tera type. */
    private fun teraFit(ally: BattlePokemonStateView, context: BattleDecisionContext): Double {
        val teraType = context.exactOwnTeam?.builds
            ?.firstOrNull { it.battlePokemonId == ally.battlePokemonId }?.teraTypeId ?: return 0.0
        val foes = context.state.pokemon.filter { it.side == BattleSide.OPPONENT && !it.fainted && it.hpFraction > 0.0 }
        if (foes.isEmpty()) return 0.0
        val weights = LocalOpponentThreat.weights(context, BattleTrainerTier.STANDARD)
        val weightTotal = foes.sumOf { weights[it.battlePokemonId] ?: 1.0 }
        return foes.sumOf { foe -> (weights[foe.battlePokemonId] ?: 1.0) * teraGain(ally, teraType, foe, context) } / weightTotal
    }

    /** 0..1: the larger of the defensive and offensive difference Tera makes in this matchup. */
    private fun teraGain(
        ally: BattlePokemonStateView,
        teraType: String,
        foe: BattlePokemonStateView,
        context: BattleDecisionContext,
    ): Double {
        val attackingTypes = foe.knownBaseStabTypeIds.ifEmpty { foe.knownTypeIds }
        val current = attackingTypes.maxOfOrNull { StandardTypeEffectiveness.multiplier(it, ally.knownTypeIds) } ?: 1.0
        val tera = attackingTypes.maxOfOrNull { StandardTypeEffectiveness.multiplier(it, setOf(teraType)) } ?: 1.0
        val defensive = when {
            current >= 2.0 && tera <= 1.0 -> 1.0
            current >= 1.0 && tera <= 0.5 && tera < current -> 0.5
            else -> 0.0
        }
        val hasTeraTypeAttack = context.publicActionCatalog.forPokemon(ally.battlePokemonId).any { move ->
            move.details.damageCategory != BattleMoveDamageCategory.STATUS && move.details.power > 0.0 &&
                canonical(move.details.typeId) == canonical(teraType)
        }
        val offensive = if (!hasTeraTypeAttack) 0.0 else {
            val stab = if (ally.knownBaseStabTypeIds.any { canonical(it) == canonical(teraType) }) 0.3 else 0.5
            val superEffective = if (StandardTypeEffectiveness.multiplier(teraType, foe.knownTypeIds) >= 2.0) 0.2 else 0.0
            stab + superEffective
        }
        return maxOf(defensive, offensive).coerceAtMost(1.0)
    }

    private fun kind(mechanicId: String): String = when (val id = canonical(mechanicId)) {
        "tera", "terastallize", "terastallization" -> TERA
        "mega", "megaevolution" -> MEGA
        "dynamax", "max" -> DYNAMAX
        else -> id
    }

    private fun canonical(value: String): String =
        PublicIds.canonical(value)

    private const val TERA = "tera"
    private const val MEGA = "mega"
    private const val DYNAMAX = "dynamax"

    /** A fully realized future Tera (a saved KO or a dodged one) is worth most of an HP bar. */
    private const val TERA_SCALE = 80.0
    /**
     * A Dynamax kept for an ally with a full share. A lead's Dynamax gains it 30 to 40 points at once; at 120 an ace's
     * share of 0.75 with two allies left holds it back by 60 (user decision, 2026-10-16).
     */
    private const val DYNAMAX_SCALE = 120.0
    private const val ONE_SHOT_SCALE = 40.0

    /** Largest share the carrier lean moves a Tera cost, at full tier scale and plan persistence. */
    private const val CARRIER_TILT = 0.3

    /** Tera fits closer than this leave no clear carrier. */
    private const val CARRIER_MARGIN = 0.1
}
