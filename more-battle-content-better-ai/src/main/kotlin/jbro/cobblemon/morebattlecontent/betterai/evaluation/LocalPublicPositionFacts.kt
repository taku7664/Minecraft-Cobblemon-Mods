package jbro.cobblemon.morebattlecontent.betterai.evaluation

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.betterai.mechanics.PublicActiveOpponentTypeChartMultiplier
import jbro.cobblemon.morebattlecontent.betterai.mechanics.PublicSwitchTypeFactsCalculator
import jbro.cobblemon.morebattlecontent.betterai.state.LocalSwitchStateProjector

/**
 * Public position readings shared by scoring, ranking and the fair outcome projector.
 *
 * It lived inside the ranking file, which meant the projector - documented as carrying no utility or
 * ranking concerns - had to import from it. Giving it its own home is what lets the fair layer stop
 * depending on the policy layer.
 */
internal object LocalPublicPositionFacts {
    fun activeAlly(candidate: BattleActionCandidate, context: BattleDecisionContext): BattlePokemonStateView? =
        candidate.actorSlot?.let { actorSlot ->
            context.state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.activeSlot == actorSlot && !it.fainted
            }
        } ?: context.state.pokemon.firstOrNull {
            it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted
        }

    fun switchTarget(candidate: BattleActionCandidate, context: BattleDecisionContext): BattlePokemonStateView? =
        candidate.switchPokemonId?.let { targetId ->
            context.state.pokemon.firstOrNull {
                it.side == BattleSide.ALLY && it.battlePokemonId == targetId && !it.fainted
            }
        }

    /**
     * Expected fraction of [defender]'s HP the opposing side removes in one turn.
     *
     * Both branches return that same quantity:
     *
     * - if an opponent move is public, the projected damage of its best revealed option;
     * - otherwise the worst type-chart multiplier scaled by [LocalDecisionTuning.neutralHitHpFraction],
     *   i.e. "a neutral hit costs about a quarter of a bar, so a 2x hit costs about half".
     *
     * Before the scaling existed this function returned a raw type multiplier (`0..4`) in the second
     * branch and expected HP damage (`0..~1.4`) in the first, and callers subtracted one from the
     * other and multiplied by a single weight. The switch score therefore changed scale by roughly 4x
     * the moment the opponent revealed any move, which is what produced switches with no readable
     * justification.
     */
    fun defensiveExposure(
        defender: BattlePokemonStateView,
        context: BattleDecisionContext,
        replacingSlot: Int? = null,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double? {
        val worstMultiplier = defender.knownTypeIds.takeIf { it.isNotEmpty() }?.let { types ->
            PublicSwitchTypeFactsCalculator.activeOpponentTypeChartMultipliers(types, context)
                .maxOfOrNull(PublicActiveOpponentTypeChartMultiplier::multiplier)
        }
        val typeFallback = worstMultiplier?.let {
            if (tuning.legacyMixedExposureUnits) it else it * tuning.neutralHitHpFraction
        }
        val hasRevealedOpponentMove = context.state.pokemon.asSequence()
            .filter { it.side == BattleSide.OPPONENT && it.activeSlot != null && !it.fainted }
            .any { context.publicActionCatalog.forPokemon(it.battlePokemonId).isNotEmpty() }
        if (!hasRevealedOpponentMove) return typeFallback
        val state = if (defender.side == BattleSide.ALLY && defender.activeSlot == null) {
            val activeSlot = replacingSlot ?: context.state.pokemon.singleOrNull {
                it.side == BattleSide.ALLY && it.activeSlot != null && !it.fainted
            }?.activeSlot ?: return null
            LocalSwitchStateProjector.project(
                context.state,
                BattleSide.ALLY,
                BattleActionCandidate(
                    actionId = "public-exposure-switch:${defender.battlePokemonId}",
                    kind = BattleActionKind.SWITCH,
                    actorSlot = activeSlot,
                    switchPokemonId = defender.battlePokemonId,
                ),
            )
        } else {
            context.state
        }
        return revealedMoveDamageExposure(state, context) ?: typeFallback
    }

    fun isPublicKnockoutThreat(
        defender: BattlePokemonStateView,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Boolean {
        val state = if (defender.activeSlot != null) context.state else return false
        val revealedDamage = revealedMoveDamageExposure(state, context)
        return if (revealedDamage != null) {
            revealedDamage >= defender.hpFraction
        } else {
            defensiveExposure(defender, context, tuning = tuning)
                ?.let { it >= knockoutExposureThreshold(tuning) } == true
        }
    }

    fun isOverwhelmingPublicThreat(
        defender: BattlePokemonStateView,
        context: BattleDecisionContext,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Boolean {
        val revealedDamage = revealedMoveDamageExposure(context.state, context)
        return if (revealedDamage != null) {
            revealedDamage >= defender.hpFraction
        } else {
            defensiveExposure(defender, context, tuning = tuning)
                ?.let { it >= overwhelmingExposureThreshold(tuning) } == true
        }
    }

    /** `2x` incoming, expressed in whatever unit [defensiveExposure] is currently returning. */
    private fun knockoutExposureThreshold(tuning: LocalDecisionTuning): Double =
        if (tuning.legacyMixedExposureUnits) KNOCKOUT_TYPE_EXPOSURE else {
            KNOCKOUT_TYPE_EXPOSURE * tuning.neutralHitHpFraction
        }

    /** `4x` incoming, expressed in whatever unit [defensiveExposure] is currently returning. */
    private fun overwhelmingExposureThreshold(tuning: LocalDecisionTuning): Double =
        if (tuning.legacyMixedExposureUnits) OVERWHELMING_TYPE_EXPOSURE else {
            OVERWHELMING_TYPE_EXPOSURE * tuning.neutralHitHpFraction
        }

    private fun revealedMoveDamageExposure(
        state: jbro.cobblemon.morebattlecontent.api.ai.BattleStateView,
        context: BattleDecisionContext,
    ): Double? = LocalLookaheadStateEvaluator.attackPressure(state, BattleSide.OPPONENT, context)
        .takeIf { it > 0.0 }

    /**
     * Turns this Pokemon survives at the given per-turn exposure.
     *
     * Now that exposure is an HP fraction per turn in both branches, this quotient finally has a
     * meaning: `0.15 / 0.5` is "dies this turn", `1.0 / 0.25` is "four turns". Every threshold that
     * consumes it is stated in turns for the same reason.
     *
     * The floor caps how good "cannot be touched" is allowed to look. Without a cap an immunity
     * reports infinite survival, which lets any immune bench member justify any switch - and the
     * cap has to move together with the exposure unit, because it is the same quantity. Missing that
     * link is what makes unit changes leak: the exposure scale shrank by 4x, so the untouched floor
     * quietly inflated every survival figure by the same factor and every threshold below became
     * meaningless.
     */
    fun survivalPosition(
        hpFraction: Double,
        defensiveExposure: Double,
        tuning: LocalDecisionTuning = LocalDecisionTuning.CURRENT,
    ): Double = (hpFraction / defensiveExposure.coerceAtLeast(tuning.immunityExposureFloor))
        .coerceAtMost(tuning.maximumSurvivalTurns)

    private const val OVERWHELMING_TYPE_EXPOSURE = 4.0
    private const val KNOCKOUT_TYPE_EXPOSURE = 2.0
}
