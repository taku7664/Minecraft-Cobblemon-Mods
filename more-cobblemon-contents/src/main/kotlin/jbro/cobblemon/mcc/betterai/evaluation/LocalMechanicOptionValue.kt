package jbro.cobblemon.mcc.betterai.evaluation

import java.util.Locale
import jbro.cobblemon.mcc.api.ai.BattleActionCandidate
import jbro.cobblemon.mcc.api.ai.BattleActionKind
import jbro.cobblemon.mcc.api.ai.BattleDecisionContext
import jbro.cobblemon.mcc.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.api.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.api.ai.BattleSide
import jbro.cobblemon.mcc.api.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.api.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.mechanics.StandardTypeEffectiveness

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
 * - Dynamax (and other one-shot mechanics) cost a flat share per healthy ally left to use it.
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
            TERA -> teraOptionValue(others, context) * carrierTilt(actor, others, context, profile, tierScale)
            else -> {
                val healthy = others.count { it.hpFraction >= 0.5 }
                DYNAMAX_SCALE * healthy / (healthy + 1.0)
            }
        }
        return base * tierScale * personality
    }

    /** Best Tera gain another ally could still realize, times its chance to get the turn to do it. */
    private fun teraOptionValue(others: List<BattlePokemonStateView>, context: BattleDecisionContext): Double {
        if (context.state.pokemon.none { it.side == BattleSide.OPPONENT && !it.fainted && it.hpFraction > 0.0 }) return 0.0
        val bestGain = others.maxOf { ally -> teraFit(ally, context) }
        val remainingOthers = others.size
        var availability = remainingOthers / (remainingOthers + 1.0)
        if (context.state.remainingPokemonBySide.getValue(BattleSide.OPPONENT) <= 1) availability *= 0.5
        return bestGain * availability * TERA_SCALE
    }

    /**
     * Multiplier on the Tera cost from the carrier lean: below one when [actor] is the clear carrier,
     * above one when another ally is, one when no ally stands out.
     */
    private fun carrierTilt(
        actor: BattlePokemonStateView,
        others: List<BattlePokemonStateView>,
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        tierScale: Double,
    ): Double {
        val strength = tierScale * profile.personality.planPersistence
        if (strength <= 0.0) return 1.0
        val fits = (others + actor).associate { it.battlePokemonId to teraFit(it, context) }
        val ranked = fits.entries.sortedByDescending { it.value }
        if (ranked.size < 2 || ranked[0].value - ranked[1].value < CARRIER_MARGIN) return 1.0
        val lean = CARRIER_TILT * strength
        return if (ranked[0].key == actor.battlePokemonId) 1.0 - lean else 1.0 + lean
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
        else -> id
    }

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private const val TERA = "tera"
    private const val MEGA = "mega"

    /** A fully realized future Tera (a saved KO or a dodged one) is worth most of an HP bar. */
    private const val TERA_SCALE = 80.0
    private const val DYNAMAX_SCALE = 40.0

    /** Largest share the carrier lean moves a Tera cost, at full tier scale and plan persistence. */
    private const val CARRIER_TILT = 0.3

    /** Tera fits closer than this leave no clear carrier. */
    private const val CARRIER_MARGIN = 0.1
}
