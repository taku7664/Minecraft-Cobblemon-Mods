package jbro.cobblemon.mcc.betterai.calculation

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.mechanics.*

/** Prices Sniper's ordinary/critical distribution without treating every hit as critical. */
internal object PublicSniperDamageProjection {
    data class Summary(val expectedDamage: Double, val knockoutProbability: Double)
    fun summary(action: BattleActionCandidate, context: BattleDecisionContext, side: BattleSide): Summary? {
        if (!LocalConditionalDamageAbilities.randomSniper(action, context, side)) return null
        PublicSubstituteDamageProjection.summary(action, context, side)?.let {
            return Summary(it.expectedBodyDamage, it.knockoutProbability)
        }
        val actor = context.state.pokemon.firstOrNull { it.side == side && it.activeSlot == action.actorSlot && !it.fainted } ?: return null
        val target = LocalPublicMoveTargets.resolve(action, context, side).firstOrNull() ?: return null
        val chance = PublicMoveOutcomeBranchProjector.criticalChance(action, context, side)
        val accuracy = LocalPublicAccuracy.probability(action, context, side)
        var expected = 0.0
        var knockout = 0.0
        for ((tag, weight) in listOf("projected_noncritical" to 1.0 - chance, "projected_critical" to chance)) {
            if (weight <= 0.0) continue
            val marked = LocalConditionalDamageAbilities.marked(action, tag)
            val rolls = PublicBattleTacticalCalculator.conservativeDamageRollFractions(marked, context, side) ?: return null
            if (rolls.isEmpty()) return null
            for (damage in rolls) {
                val after = LocalDirectHitMechanics.apply(context.state, actor.battlePokemonId, target.battlePokemonId,
                    damage, emptyList(), LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, target, context.state),
                    bypassesSubstitute = LocalPublicAbilityState.effectiveKnownAbility(context.state, actor) == "infiltrator" ||
                        action.moveDetails?.effects?.mechanicFlags.orEmpty().any { PublicIds.canonical(it) == "sound" },
                    hitCount = LocalDeclaredMultiHit.representativeCount(action, actor, context.state))
                    .state.pokemon.single { it.battlePokemonId == target.battlePokemonId }
                val probability = weight * accuracy / rolls.size
                expected += probability * (target.hpFraction - after.hpFraction).coerceAtLeast(0.0)
                if (after.fainted) knockout += probability
            }
        }
        return Summary(expected, knockout)
    }
}
