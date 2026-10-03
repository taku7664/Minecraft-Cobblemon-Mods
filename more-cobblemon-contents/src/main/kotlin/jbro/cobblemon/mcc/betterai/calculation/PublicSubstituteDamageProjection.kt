package jbro.cobblemon.mcc.betterai.calculation

import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.betterai.mechanics.*

/** Body utility and damage-based HP transfers are different when a decoy intercepts hits. */
internal object PublicSubstituteDamageProjection {
    data class Summary(val bodyDamage: BattleFractionRange, val expectedBodyDamage: Double,
        val moveDamage: BattleDamageFractionRange, val expectedMoveDamage: Double, val knockoutProbability: Double)
    fun summary(action: BattleActionCandidate, context: BattleDecisionContext, side: BattleSide): Summary? {
        val actor = context.state.pokemon.firstOrNull { it.side == side && it.activeSlot == action.actorSlot && !it.fainted } ?: return null
        val target = LocalPublicMoveTargets.resolve(action, context, side).firstOrNull() ?: return null
        if (!LocalSubstituteRules.present(target) || LocalSubstituteRules.bypasses(action, actor, context.state)) return null
        val branches = PublicMoveOutcomeBranchProjector.project(action, context, side)
        var expectedBody = 0.0; var expectedMove = 0.0; var knockout = 0.0
        val body = mutableListOf<Double>(); val attributed = mutableListOf<Double>()
        for (branch in branches.filter { it.hit }) {
            val hit = LocalDirectHitMechanics.apply(LocalSubstituteRules.seed(context.state, target.battlePokemonId, branch.substituteHpBefore),
                actor.battlePokemonId, target.battlePokemonId, branch.damageFraction, emptyList(),
                LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, target, context.state), hitCount = branch.hitCount)
            body += hit.directDamageFraction
            val hp = requireNotNull(branch.substituteHpBefore)
            for (endpoint in listOf(hp.minimum, hp.maximum).distinct()) {
                attributed += LocalDirectHitMechanics.apply(LocalSubstituteRules.seed(context.state, target.battlePokemonId,
                    BattleDamageFractionRange(endpoint, endpoint)), actor.battlePokemonId, target.battlePokemonId,
                    branch.damageFraction, emptyList(), LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, target, context.state),
                    hitCount = branch.hitCount).moveDamageFraction
            }
            expectedBody += branch.probability * hit.directDamageFraction
            expectedMove += branch.probability * hit.moveDamageFraction
            if (hit.state.pokemon.single { it.battlePokemonId == target.battlePokemonId }.fainted) knockout += branch.probability
        }
        return Summary(BattleFractionRange(body.minOrNull() ?: 0.0, body.maxOrNull() ?: 0.0), expectedBody,
            BattleDamageFractionRange(attributed.minOrNull() ?: 0.0, attributed.maxOrNull() ?: 0.0), expectedMove, knockout)
    }
}
