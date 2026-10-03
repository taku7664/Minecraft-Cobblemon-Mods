package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.*

/** Entry hazards need a future switch. The denominator is the original selected team size. */
internal object LocalEntryHazardValue {
    val layerLimits = mapOf("stealthrock" to 1, "spikes" to 3, "toxicspikes" to 2, "stickyweb" to 1,
        "steelsurge" to 1, "gmaxsteelsurge" to 1)

    fun factor(state: BattleStateView, affectedSide: BattleSide, source: BattleDecisionContext? = null): Double {
        val remaining = state.remainingPokemonBySide.getValue(affectedSide)
        val active = state.pokemon.count { it.side == affectedSide && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        val switches = (remaining - active).coerceAtLeast(0)
        // Fainted public members remain in the state: remaining + fainted preserves the start count
        // through knockouts and revival. Preview/exact-own selection is authoritative when supplied.
        val maximum = when (affectedSide) {
            BattleSide.OPPONENT -> source?.opponentTeamPreview?.selectionSize
            BattleSide.ALLY -> source?.exactOwnTeam?.builds?.size
        } ?: (remaining + state.pokemon.count { it.side == affectedSide && (it.fainted || it.hpFraction <= 0.0) })
        return if (maximum > 0) (switches.toDouble() / maximum).coerceIn(0.0, 1.0) else 0.0
    }

    fun installationFactor(candidate: BattleActionCandidate, context: BattleDecisionContext): Double? {
        val actor = context.state.pokemon.firstOrNull { it.side == BattleSide.ALLY && it.activeSlot == candidate.actorSlot && !it.fainted } ?: return null
        val effect = candidate.moveDetails?.effects?.effects.orEmpty().firstOrNull {
            it.kind == BattleMoveEffectKind.SIDE_CONDITION && PublicIds.canonical(it.valueId.orEmpty()) in layerLimits
        }
        val affected = when (effect?.target) {
            BattleMoveEffectTarget.USER_SIDE -> actor.side
            BattleMoveEffectTarget.TARGET_SIDE -> if (actor.side == BattleSide.ALLY) BattleSide.OPPONENT else BattleSide.ALLY
            else -> if (PublicIds.canonical(candidate.moveId.orEmpty()) in layerLimits) BattleSide.OPPONENT else return null
        }
        return factor(context.state, affected, context)
    }
}
