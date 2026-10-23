package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.*

/** The target's TryHit ability reaction, shared by turn projection and switch-entry valuation.
 * Call after protection/invulnerability checks, before accuracy (Showdown gen 7+).
 * Null means this ability does not absorb the move; an unchanged state can still mean absorption.
 */
internal object LocalMoveAbsorption {
    fun apply(
        state: BattleStateView,
        action: BattleActionCandidate,
        actor: BattlePokemonStateView,
        target: BattlePokemonStateView,
    ): BattleStateView? {
        if (actor.battlePokemonId == target.battlePokemonId || target.fainted || target.hpFraction <= 0.0 ||
            LocalPublicAbilityMechanics.ignoresTargetAbility(action, actor, target, state)) return null
        val type = LocalPublicMoveDamageInputs.resolvedTypeId(action, actor, state)?.let(PublicIds::canonical)
        val ability = LocalPublicAbilityState.effectiveKnownAbility(state, target) ?: return null
        val boost = when {
            ability == "windrider" && action.moveDetails?.effects?.mechanicFlags.orEmpty().any {
                PublicIds.canonical(it) == "wind" } -> mapOf("attack" to 1)
            ability == "sapsipper" && type == "grass" -> mapOf("attack" to 1)
            ability == "motordrive" && type == "electric" -> mapOf("speed" to 1)
            ability == "lightningrod" && type == "electric" -> mapOf("special_attack" to 1)
            ability == "stormdrain" && type == "water" -> mapOf("special_attack" to 1)
            ability == "wellbakedbody" && type == "fire" -> mapOf("defence" to 2)
            else -> null
        }
        if (boost != null) return LocalStatStageChange.apply(state, target.battlePokemonId, null, boost)
        if (ability == "flashfire" && type == "fire") return LocalReactiveAbilityState.absorb(state, target.battlePokemonId, type)
        val heals = ability in setOf("waterabsorb", "dryskin") && type == "water" ||
            ability == "voltabsorb" && type == "electric" || ability == "eartheater" && type == "ground"
        if (!heals) return null
        if ("healblock" in target.canonicalKnownVolatileEffectIds) return state
        // These four abilities heal floor(baseMaxhp / 4), even while Dynamaxed. Public combat stats
        // describe base maximum HP; hpFraction describes the current (possibly doubled) HP bar.
        val hp = LocalHpArithmetic.healBaseShare(target, 4)
        return state.copyState(pokemon = state.pokemon.map {
            if (it.battlePokemonId == target.battlePokemonId) it.copyState(hpFraction = hp) else it
        })
    }
}
