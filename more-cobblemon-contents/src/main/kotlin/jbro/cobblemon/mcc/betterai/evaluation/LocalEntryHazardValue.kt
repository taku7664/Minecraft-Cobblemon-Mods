package jbro.cobblemon.mcc.betterai.evaluation

import jbro.cobblemon.mcc.internal.ai.*

/** Entry hazards need a future switch. Full bench availability retains the whole hazard value. */
internal object LocalEntryHazardValue {
    val layerLimits = mapOf("stealthrock" to 1, "spikes" to 3, "toxicspikes" to 2, "stickyweb" to 1,
        "steelsurge" to 1, "gmaxsteelsurge" to 1)

    fun factor(state: BattleStateView, affectedSide: BattleSide, source: BattleDecisionContext? = null): Double {
        val remaining = state.remainingPokemonBySide.getValue(affectedSide)
        val active = state.pokemon.count { it.side == affectedSide && it.activeSlot != null && !it.fainted && it.hpFraction > 0.0 }
        val switches = (remaining - active).coerceAtLeast(0)
        // Fainted public members remain in the state: remaining + fainted preserves the start count
        // through knockouts and revival. Preview/exact-own selection is authoritative when supplied.
        val selectedTeamSize = when (affectedSide) {
            BattleSide.OPPONENT -> source?.opponentTeamPreview?.selectionSize
            BattleSide.ALLY -> source?.exactOwnTeam?.builds?.size
        } ?: (remaining + state.pokemon.count { it.side == affectedSide && (it.fainted || it.hpFraction <= 0.0) })
        // Use the format's original active capacity, not its currently living actives: a knockout
        // must not change how many reserves the full team could originally have kept on its bench.
        val activeCapacity = when (state.format) {
            BattleFormat.SINGLE -> 1
            BattleFormat.DOUBLE -> 2
        }
        val maximumBench = (selectedTeamSize - activeCapacity).coerceAtLeast(0)
        return if (maximumBench > 0) (switches.toDouble() / maximumBench).coerceIn(0.0, 1.0) else 0.0
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
